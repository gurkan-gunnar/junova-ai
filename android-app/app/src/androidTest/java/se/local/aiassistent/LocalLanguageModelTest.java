package se.local.aiassistent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LocalLanguageModelTest {
    @Test
    public void testGemma5BAnswersInSwedish() throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        StringBuilder answer = new StringBuilder();
        AtomicReference<String> error = new AtomicReference<>("");
        Context context = ApplicationProvider.getApplicationContext();

        LocalLanguageModel.ask(
            context,
            "Svara p\u00e5 svenska med exakt ett ord: Vad heter Sveriges huvudstad?",
            "instrumented-gemma-5b",
            "instrumented-gemma-5b",
            512,
            new LocalLanguageModel.Callback() {
                @Override
                public void onToken(String requestId, String token) {
                    answer.append(token);
                }

                @Override
                public void onComplete(String requestId) {
                    finished.countDown();
                }

                @Override
                public void onError(String requestId, String message) {
                    error.set(message == null ? "Ok\u00e4nt modellfel" : message);
                    finished.countDown();
                }
            });

        assertTrue("5B-modellen svarade inte inom fem minuter.", finished.await(5, TimeUnit.MINUTES));
        assertTrue("5B-modellen gav fel: " + error.get(), error.get().isEmpty());
        String visibleAnswer = answer.toString();
        assertTrue("Svaret saknade Stockholm: " + visibleAnswer,
            visibleAnswer.toLowerCase(Locale.ROOT).contains("stockholm"));
        assertFalse("Privat tanketext l\u00e4ckte till svaret.",
            visibleAnswer.contains("<|channel") || visibleAnswer.contains("<think>"));
    }
}
