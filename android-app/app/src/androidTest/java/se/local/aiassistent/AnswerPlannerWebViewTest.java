package se.local.aiassistent;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.annotation.SuppressLint;
import android.content.Context;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AnswerPlannerWebViewTest {
    @SuppressLint("SetJavaScriptEnabled")
    @Test
    public void plannerCoversEveryQuestionPartAndRejectsInventedNumbers() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        AtomicReference<WebView> webViewReference = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            WebView.setDataDirectorySuffix("planner-test");
            WebView webView = new WebView(context);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setDomStorageEnabled(true);
            webView.getSettings().setAllowFileAccess(true);
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    loaded.countDown();
                }
            });
            webViewReference.set(webView);
            webView.loadUrl("file:///android_asset/index.html");
        });

        assertTrue("Junovas sida laddades inte.", loaded.await(45, TimeUnit.SECONDS));
        WebView webView = webViewReference.get();
        assertNotNull(webView);

        CountDownLatch evaluated = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>("");
        String checks = "(() => {"
            + "const shared = questionTaskQueries('Var ligger Liseberg och vilka åkattraktioner finns?');"
            + "const separate = questionTaskQueries('Vem är Messi? Vad är kvantfysik?');"
            + "const facts = [{ text: 'Portarna öppnar 10.00 och tornet är 330 meter högt.' }];"
            + "const values = ["
            + "shared.length === 2,"
            + "/liseberg/i.test(shared[1].query),"
            + "separate.length === 2 && !/messi/i.test(separate[1].query),"
            + "!answerMatchesQuestionRequest('Var ligger Liseberg och när öppnar det?', 'Liseberg ligger i Göteborg.'),"
            + "answerMatchesQuestionRequest('Var ligger Liseberg och när öppnar det?', 'Liseberg ligger i Göteborg och öppnar klockan 10.'),"
            + "!answerMatchesQuestionRequest('Är Saltholmen en bra badplats?', 'Den ägs av Göteborgs stad.'),"
            + "!hasUnsupportedPreciseNumbers('När öppnar det?', 'Det öppnar 10:00.', facts, 'general'),"
            + "hasUnsupportedPreciseNumbers('Hur högt är tornet?', 'Det är 350 meter högt.', facts, 'general')"
            + "];"
            + "return (values.every(Boolean) ? 'PASS:' : 'FAIL:') + values.join(',');"
            + "})()";

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            webView.evaluateJavascript(checks, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    result.set(value == null ? "" : value);
                    evaluated.countDown();
                }
            })
        );

        assertTrue("Junovas JavaScript-test avslutades inte.", evaluated.await(20, TimeUnit.SECONDS));
        assertTrue("Frågeplaneraren misslyckades: " + result.get(), result.get().contains("PASS:"));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(webView::destroy);
    }
}
