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
        Context context = ApplicationProvider.getApplicationContext();

        LocalLanguageModel.warmUp(context);
        long warmUpDeadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
        while (!LocalLanguageModel.isReady() && System.nanoTime() < warmUpDeadline) {
            Thread.sleep(500);
        }
        assertTrue("5B-modellen blev inte redo inom fyra minuter.", LocalLanguageModel.isReady());

        String firstAnswer = ask(context,
            "Svara p\u00e5 svenska med exakt ett ord: Vad heter Sveriges huvudstad?",
            "instrumented-gemma-5b-first");
        assertTrue("Svaret saknade Stockholm: " + firstAnswer,
            firstAnswer.toLowerCase(Locale.ROOT).contains("stockholm"));
        assertFalse("Privat tanketext l\u00e4ckte till svaret.",
            firstAnswer.contains("<|channel") || firstAnswer.contains("<think>"));

        long secondStarted = System.nanoTime();
        String secondAnswer = ask(context,
            "Svara endast med siffran: Vad \u00e4r tv\u00e5 plus tv\u00e5?",
            "instrumented-gemma-5b-second");
        long secondDurationSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - secondStarted);
        assertTrue("Svaret p\u00e5 tv\u00e5 plus tv\u00e5 saknade 4: " + secondAnswer,
            secondAnswer.contains("4"));
        assertTrue("Chattbytet laddade om modellen och tog " + secondDurationSeconds + " sekunder.",
            secondDurationSeconds < 30);
        assertTrue("Modellen var inte kvar i minnet efter chattbytet.", LocalLanguageModel.isReady());
    }

    private static String ask(Context context, String prompt, String conversationId) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        StringBuilder answer = new StringBuilder();
        AtomicReference<String> error = new AtomicReference<>("");

        LocalLanguageModel.ask(
            context,
            prompt,
            conversationId,
            conversationId,
            64,
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

        assertTrue("5B-modellen svarade inte inom en minut.", finished.await(1, TimeUnit.MINUTES));
        assertTrue("5B-modellen gav fel: " + error.get(), error.get().isEmpty());
        return answer.toString();
    }
}
