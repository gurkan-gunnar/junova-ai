package se.local.aiassistent;

import android.annotation.SuppressLint;
import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.ClipData;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebSettings;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 42;
    private static final int MAX_WEB_PAGES = 3;
    private static final String STORE_KEY_ALIAS = "ai_assistent_store_key_v1";
    private static final String MIGRATION_FILE = "ai-assistent-migration.json";
    private static final int MAX_MIGRATION_BYTES = 24 * 1024 * 1024;
    private static final int MAX_IMAGE_DATA_URL_BYTES = 8 * 1024 * 1024;
    private static final String ANSWER_CHANNEL_ID = "junova_answers";
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private final ExecutorService webExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean appInForeground = true;
    private long lastNotificationAt = 0L;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createAnswerNotificationChannel();

        webView = new WebView(this);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                intent.setType("image/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                if (fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception error) {
                    MainActivity.this.filePathCallback = null;
                    return false;
                }
            }
        });
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.addJavascriptInterface(new DeviceStore(), "AndroidStore");
        webView.addJavascriptInterface(new MigrationBridge(), "MigrationBridge");
        webView.addJavascriptInterface(new NativeWeb(), "NativeWeb");
        webView.addJavascriptInterface(new NativeLanguage(), "LocalLanguage");
        webView.addJavascriptInterface(new NativeVision(), "NativeVision");
        webView.addJavascriptInterface(new AppEvents(), "AppEvents");

        setContentView(webView);
        requestRuntimePermissions();
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), 7);
    }

    private void createAnswerNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
            ANSWER_CHANNEL_ID,
            "Färdiga svar",
            NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Visar när Junova AI har svarat i bakgrunden.");
        manager.createNotificationChannel(channel);
    }

    private void showAnswerNotification(String preview) {
        if (appInForeground) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        long now = System.currentTimeMillis();
        if (now - lastNotificationAt < 5000L) return;
        lastNotificationAt = now;

        Intent openApp = new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
            this,
            1001,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String body = preview == null || preview.trim().isEmpty()
            ? "Ditt svar är färdigt."
            : preview.trim();
        if (body.length() > 120) body = body.substring(0, 120) + "…";
        Notification notification = new Notification.Builder(this, ANSWER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_junova_notification)
            .setContentTitle("Junova AI har svarat")
            .setContentText(body)
            .setStyle(new Notification.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(1001, notification);
    }

    private class DeviceStore {
        private SharedPreferences prefs() {
            return getSharedPreferences("ai_assistent_device_store", MODE_PRIVATE);
        }

        @JavascriptInterface
        public void save(String key, String value) {
            try {
                prefs().edit().putString(key, encryptStoreValue(value == null ? "" : value)).apply();
            } catch (Exception ignored) {
                // Never fall back to writing private chat data as plain text.
            }
        }

        @JavascriptInterface
        public String load(String key) {
            String stored = prefs().getString(key, "");
            if (stored == null || stored.isEmpty()) return "";
            try {
                String plain = decryptStoreValue(stored);
                if (!stored.startsWith("v1:")) save(key, plain);
                return plain;
            } catch (Exception ignored) {
                return "";
            }
        }
    }

    private class MigrationBridge {
        private File backupFile() {
            File directory = getExternalFilesDir(null);
            return directory == null ? null : new File(directory, MIGRATION_FILE);
        }

        @JavascriptInterface
        public boolean backupRequested() {
            return getIntent() != null && getIntent().getBooleanExtra("exportMigration", false);
        }

        @JavascriptInterface
        public String packageName() {
            return getPackageName();
        }

        @JavascriptInterface
        public boolean writeBackup(String json) {
            if (json == null) return false;
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0 || bytes.length > MAX_MIGRATION_BYTES) return false;
            File file = backupFile();
            if (file == null) return false;
            try (FileOutputStream output = new FileOutputStream(file, false)) {
                output.write(bytes);
                output.flush();
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }

        @JavascriptInterface
        public String loadBackup() {
            File file = backupFile();
            if (file == null || !file.isFile() || file.length() <= 0 || file.length() > MAX_MIGRATION_BYTES) return "";
            try {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return "";
            }
        }
    }

    private SecretKey getOrCreateStoreKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(STORE_KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(STORE_KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
            STORE_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
        return generator.generateKey();
    }

    private String encryptStoreValue(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateStoreKey());
        String iv = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP);
        String encrypted = Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        return "v1:" + iv + ":" + encrypted;
    }

    private String decryptStoreValue(String stored) throws Exception {
        if (!stored.startsWith("v1:")) return stored;
        String[] parts = stored.split(":", 3);
        if (parts.length != 3) throw new IllegalArgumentException("Invalid encrypted value");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateStoreKey(), new GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    private class NativeWeb {
        @JavascriptInterface
        public void search(String query, String requestId) {
            final String safeQuery = query == null ? "" : query.trim();
            final String safeRequestId = requestId == null ? "" : requestId;
            webExecutor.execute(() -> deliverSearchResult(safeRequestId, searchPublicWeb(safeQuery)));
        }

        @JavascriptInterface
        public void open(String url) {
            if (url == null || !url.matches("https://.+")) return;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {
                // A source link should never break the chat if no browser is available.
            }
        }
    }

    private class NativeLanguage {
        @JavascriptInterface
        public void ask(String prompt, String requestId, int maxTokens, String conversationId) {
            String safePrompt = prompt == null ? "" : prompt.trim();
            String safeRequestId = requestId == null ? "" : requestId;
            String safeConversationId = conversationId == null ? "default" : conversationId;
            if (safePrompt.isEmpty()) return;
            LocalLanguageModel.ask(getApplicationContext(), safePrompt, safeRequestId, safeConversationId, Math.max(96, Math.min(maxTokens, 768)), new LocalLanguageModel.Callback() {
                @Override
                public void onToken(String id, String token) {
                    deliverLanguageEvent("window.__localModelToken", id, token);
                }

                @Override
                public void onComplete(String id) {
                    deliverLanguageEvent("window.__localModelComplete", id, "");
                    runOnUiThread(() -> showAnswerNotification("Ditt lokala 3B-svar är färdigt."));
                }

                @Override
                public void onError(String id, String message) {
                    deliverLanguageEvent("window.__localModelError", id, message);
                }
            });
        }
    }

    private class NativeVision {
        @JavascriptInterface
        public void analyze(String dataUrl, String requestId) {
            final String safeDataUrl = dataUrl == null ? "" : dataUrl;
            final String safeRequestId = requestId == null ? "" : requestId;
            webExecutor.execute(() -> analyzeImageData(safeDataUrl, safeRequestId));
        }
    }

    private class AppEvents {
        @JavascriptInterface
        public void answerReady(String preview) {
            runOnUiThread(() -> showAnswerNotification(preview));
        }
    }

    private void analyzeImageData(String dataUrl, String requestId) {
        try {
            if (dataUrl.isEmpty() || dataUrl.length() > MAX_IMAGE_DATA_URL_BYTES) {
                deliverImageResult(requestId, null, "Bilden är tom eller för stor.");
                return;
            }
            int comma = dataUrl.indexOf(',');
            String encoded = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
            byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) {
                deliverImageResult(requestId, null, "Bilden kunde inte avkodas.");
                return;
            }

            InputImage image = InputImage.fromBitmap(bitmap, 0);
            JSONObject payload = new JSONObject();
            payload.put("width", bitmap.getWidth());
            payload.put("height", bitmap.getHeight());
            payload.put("text", "");
            payload.put("labels", new JSONArray());

            TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            ImageLabeler labeler = ImageLabeling.getClient(
                new ImageLabelerOptions.Builder().setConfidenceThreshold(0.45f).build());
            AtomicInteger remaining = new AtomicInteger(2);
            Runnable completeOne = () -> {
                if (remaining.decrementAndGet() == 0) deliverImageResult(requestId, payload, "");
            };

            recognizer.process(image)
                .addOnSuccessListener(result -> {
                    try {
                        String text = result.getText() == null ? "" : result.getText().trim();
                        synchronized (payload) {
                            payload.put("text", text.substring(0, Math.min(text.length(), 5000)));
                        }
                    } catch (Exception ignored) {
                        // Labels and local image statistics remain useful without OCR text.
                    }
                })
                .addOnCompleteListener(task -> {
                    recognizer.close();
                    completeOne.run();
                });

            labeler.process(image)
                .addOnSuccessListener(labels -> {
                    try {
                        JSONArray output = new JSONArray();
                        for (ImageLabel label : labels) {
                            if (output.length() >= 8) break;
                            JSONObject item = new JSONObject();
                            item.put("name", label.getText());
                            item.put("confidence", Math.round(label.getConfidence() * 1000f) / 1000f);
                            output.put(item);
                        }
                        synchronized (payload) {
                            payload.put("labels", output);
                        }
                    } catch (Exception ignored) {
                        // OCR text can still be returned if labels fail to serialize.
                    }
                })
                .addOnCompleteListener(task -> {
                    labeler.close();
                    completeOne.run();
                });
        } catch (Exception error) {
            deliverImageResult(requestId, null, "Bildanalysen misslyckades.");
        }
    }

    private void deliverImageResult(String requestId, JSONObject result, String error) {
        try {
            JSONObject payload = result == null ? new JSONObject() : result;
            payload.put("id", requestId == null ? "" : requestId);
            payload.put("error", error == null ? "" : error);
            String script = "window.__nativeImageResult && window.__nativeImageResult(" + payload.toString() + ");";
            webView.post(() -> webView.evaluateJavascript(script, null));
        } catch (Exception ignored) {
            // A failed image callback must not interrupt the chat.
        }
    }

    private void deliverLanguageEvent(String receiver, String requestId, String value) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("id", requestId);
            payload.put("value", value == null ? "" : value);
            String script = receiver + " && " + receiver + "(" + payload.toString() + ");";
            webView.post(() -> webView.evaluateJavascript(script, null));
        } catch (Exception ignored) {
            // A bad token must not stop the local model.
        }
    }

    private void deliverSearchResult(String requestId, JSONArray results) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("id", requestId);
            payload.put("results", results);
            String script = "window.__nativeWebResult && window.__nativeWebResult(" + payload.toString() + ");";
            webView.post(() -> webView.evaluateJavascript(script, null));
        } catch (Exception ignored) {
            // The browser can fall back to its built-in knowledge search.
        }
    }

    private JSONArray searchPublicWeb(String query) {
        JSONArray output = new JSONArray();
        if (query.length() < 2) return output;
        try {
            String searchUrl = "https://html.duckduckgo.com/html/?q=" + Uri.encode(query);
            String searchHtml = fetchPage(searchUrl, 5500, 220000);
            List<WebHit> hits = parseSearchResults(searchHtml);
            for (WebHit hit : hits) {
                if (output.length() >= MAX_WEB_PAGES) break;
                try {
                    String pageHtml = fetchPage(hit.url, 5500, 260000);
                    String text = readableText(pageHtml);
                    if (text.length() < 120) continue;
                    JSONObject item = new JSONObject();
                    item.put("title", hit.title);
                    item.put("url", hit.url);
                    item.put("text", text.substring(0, Math.min(text.length(), 1600)));
                    output.put(item);
                } catch (Exception ignored) {
                    // A single blocked page should not stop the rest of the search.
                }
            }
        } catch (Exception ignored) {
            // Returning an empty result lets the page use its normal fallback search.
        }
        return output;
    }

    private static class WebHit {
        final String title;
        final String url;

        WebHit(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }

    private List<WebHit> parseSearchResults(String html) {
        List<WebHit> hits = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Pattern resultPattern = Pattern.compile("(?is)<a[^>]*class=\\\"[^\\\"]*result__a[^\\\"]*\\\"[^>]*href=\\\"([^\\\"]+)\\\"[^>]*>(.*?)</a>");
        Matcher matcher = resultPattern.matcher(html);
        while (matcher.find() && hits.size() < 8) {
            String url = decodeResultUrl(htmlDecode(matcher.group(1)));
            String title = htmlDecode(matcher.group(2).replaceAll("(?is)<[^>]+>", " ")).replaceAll("\\s+", " ").trim();
            if (!url.matches("https?://.+") || title.length() < 2 || !seen.add(url)) continue;
            hits.add(new WebHit(title, url));
        }
        return hits;
    }

    private String decodeResultUrl(String url) {
        try {
            Uri uri = Uri.parse(url.startsWith("//") ? "https:" + url : url);
            String target = uri.getQueryParameter("uddg");
            return target == null ? uri.toString() : URLDecoder.decode(target, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return url;
        }
    }

    private String fetchPage(String address, int timeoutMs, int maxCharacters) throws Exception {
        String currentAddress = address;
        for (int redirectCount = 0; redirectCount <= 4; redirectCount++) {
            if (!isSafePublicWebUrl(currentAddress)) throw new IllegalStateException("Unsafe web address");
            URL currentUrl = new URL(currentAddress);
            HttpURLConnection connection = (HttpURLConnection) currentUrl.openConnection();
            try {
                connection.setConnectTimeout(timeoutMs);
                connection.setReadTimeout(timeoutMs);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Junova-AI/3.30");
                connection.setRequestProperty("Accept-Language", "sv-SE,sv;q=0.9,en;q=0.7");
                connection.setInstanceFollowRedirects(false);
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.trim().isEmpty()) throw new IllegalStateException("Invalid redirect");
                    currentAddress = new URL(currentUrl, location).toString();
                    continue;
                }
                if (status < 200 || status >= 300) throw new IllegalStateException("Web page unavailable");
                String contentType = connection.getContentType();
                if (contentType != null) {
                    String normalizedType = contentType.toLowerCase(Locale.ROOT);
                    if (!normalizedType.startsWith("text/html") &&
                        !normalizedType.startsWith("text/plain") &&
                        !normalizedType.startsWith("application/xhtml+xml")) {
                        throw new IllegalStateException("Unsupported web content");
                    }
                }
                StringBuilder content = new StringBuilder();
                try (InputStream stream = connection.getInputStream();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    char[] buffer = new char[4096];
                    int read;
                    while ((read = reader.read(buffer)) != -1 && content.length() < maxCharacters) {
                        content.append(buffer, 0, Math.min(read, maxCharacters - content.length()));
                    }
                }
                return content.toString();
            } finally {
                connection.disconnect();
            }
        }
        throw new IllegalStateException("Too many redirects");
    }

    private boolean isSafePublicWebUrl(String address) {
        try {
            URL url = new URL(address);
            if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null) return false;
            if (url.getHost() == null || url.getHost().trim().isEmpty()) return false;
            if (url.getPort() != -1 && url.getPort() != 443) return false;
            InetAddress[] resolvedAddresses = InetAddress.getAllByName(url.getHost());
            if (resolvedAddresses.length == 0) return false;
            for (InetAddress resolved : resolvedAddresses) {
                if (resolved.isAnyLocalAddress() || resolved.isLoopbackAddress() || resolved.isLinkLocalAddress() ||
                    resolved.isSiteLocalAddress() || resolved.isMulticastAddress()) return false;
                byte[] bytes = resolved.getAddress();
                if (bytes.length == 4) {
                    int first = bytes[0] & 0xff;
                    int second = bytes[1] & 0xff;
                    if (first == 0 || first >= 240 || (first == 100 && second >= 64 && second <= 127)) return false;
                } else if (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc) {
                    return false;
                }
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String readableText(String html) {
        String withoutNoise = html
            .replaceAll("(?is)<(script|style|noscript|svg|nav|footer|header|form)[^>]*>.*?</\\1>", " ")
            .replaceAll("(?is)<!--.*?-->", " ")
            .replaceAll("(?is)<[^>]+>", " ");
        return htmlDecode(withoutNoise).replaceAll("\\s+", " ").trim();
    }

    private String htmlDecode(String value) {
        return value.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">");
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && filePathCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                ClipData clipData = data.getClipData();
                if (clipData != null && clipData.getItemCount() > 0) {
                    int count = Math.min(clipData.getItemCount(), 2);
                    result = new Uri[count];
                    for (int index = 0; index < count; index++) {
                        result[index] = clipData.getItemAt(index).getUri();
                    }
                } else {
                    result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                }
            }
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        appInForeground = true;
    }

    @Override
    protected void onStop() {
        appInForeground = false;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        webExecutor.shutdownNow();
        super.onDestroy();
    }
}
