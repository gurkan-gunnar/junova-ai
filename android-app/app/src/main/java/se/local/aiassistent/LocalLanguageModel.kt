package se.local.aiassistent

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File

/** Runs the installed GGUF model completely on the phone. */
object LocalLanguageModel {
    private const val MODEL_NAME = "gemma-4-E2B_q4_0-it.gguf"
    private const val FALLBACK_MODEL_NAME = "Qwen2.5-3B-Instruct-Q4_K_M.gguf"
    private const val LEGACY_MODEL_NAME = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"
    private const val MIN_PREDICT_LENGTH = 64
    private const val MAX_PREDICT_LENGTH = 288
    private const val SYSTEM_PROMPT =
        "You are Junova AI. Answer the newest question directly in natural Swedish or English, matching the user's language. " +
        "Stay on the exact subject and requested property. Answer every numbered part in order. " +
        "Use supplied evidence before memory, preserve its numbers and units exactly, and never invent facts or sources. " +
        "Ask one brief question when an essential meaning is ambiguous. Start with the answer, keep private reasoning hidden, and be concise."

    interface Callback {
        fun onToken(requestId: String, token: String)
        fun onComplete(requestId: String)
        fun onError(requestId: String, message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var engine: InferenceEngine? = null
    @Volatile private var activeConversationId: String? = null
    @Volatile private var warmUpRunning = false

    @JvmStatic
    fun warmUp(context: Context) {
        startWarmUp(context.applicationContext, "junova-warm-up")
    }

    @JvmStatic
    fun isReady(): Boolean = engine?.state?.value is InferenceEngine.State.ModelReady

    @JvmStatic
    fun ask(context: Context, prompt: String, requestId: String, conversationId: String, predictLength: Int, callback: Callback) {
        val appContext = context.applicationContext
        if (!isReady()) {
            if (findModelFile(appContext) == null) {
                callback.onError(requestId, "Junova 5B-modellen saknas i appens modeller.")
                return
            }
            startWarmUp(appContext, conversationId)
            callback.onError(requestId, "Junova värmer modellen och använder sitt snabba faktasvar under tiden.")
            return
        }
        scope.launch {
            val response = StringBuilder()
            try {
                val loadedEngine = ensureModel(appContext, conversationId)
                val safePredictLength = predictLength.coerceIn(MIN_PREDICT_LENGTH, MAX_PREDICT_LENGTH)
                withTimeout(generationTimeoutMillis(safePredictLength)) {
                    loadedEngine.sendUserPrompt(
                        prompt.trim(),
                        safePredictLength,
                    ).collect { token ->
                        response.append(token)
                    }
                }
                val answer = visibleAnswer(response.toString())
                require(answer.isNotBlank()) { "Resonemanget hann inte fram till ett färdigt svar. Försök igen." }
                callback.onToken(requestId, answer)
                callback.onComplete(requestId)
            } catch (_: TimeoutCancellationException) {
                val partialAnswer = visibleAnswer(response.toString())
                if (partialAnswer.isNotBlank()) {
                    callback.onToken(requestId, partialAnswer)
                    callback.onComplete(requestId)
                } else {
                    callback.onError(requestId, "Tidsgränsen nåddes. Junova använder sitt snabba faktasvar.")
                }
            } catch (error: Exception) {
                callback.onError(requestId, error.message ?: "Den lokala modellen kunde inte starta.")
            }
        }
    }

    private fun startWarmUp(context: Context, conversationId: String) {
        synchronized(this) {
            if (warmUpRunning || isReady()) return
            warmUpRunning = true
        }
        scope.launch {
            try {
                ensureModel(context, conversationId)
            } catch (_: Exception) {
                // A later request can retry after a failed warm-up.
            } finally {
                warmUpRunning = false
            }
        }
    }

    private fun generationTimeoutMillis(predictLength: Int): Long = when {
        predictLength <= 96 -> 20_000L
        predictLength <= 180 -> 28_000L
        predictLength <= 240 -> 34_000L
        else -> 42_000L
    }

    private suspend fun ensureModel(context: Context, conversationId: String): InferenceEngine {
        val inference = engine ?: AiChat.getInferenceEngine(context).also { engine = it }
        repeat(120) {
            when (inference.state.value) {
                is InferenceEngine.State.ModelReady -> {
                    if (activeConversationId != null && activeConversationId != conversationId) {
                        inference.resetConversation(SYSTEM_PROMPT)
                    }
                    activeConversationId = conversationId
                    return inference
                }
                is InferenceEngine.State.Initialized -> {
                    val modelFile = findModelFile(context)
                    require(modelFile != null && modelFile.isFile && modelFile.canRead()) { "Junova 5B-modellen saknas i appens modeller." }
                    inference.loadModel(modelFile.absolutePath)
                    inference.setSystemPrompt(SYSTEM_PROMPT)
                    activeConversationId = conversationId
                    return inference
                }
                is InferenceEngine.State.Error -> inference.cleanUp()
                else -> delay(100)
            }
        }
        throw IllegalStateException("Den lokala modellen tog för lång tid att starta.")
    }

    private fun findModelFile(context: Context): File? {
        val internalDirectory = File(context.filesDir, "models").apply { mkdirs() }
        val externalDirectory = context.getExternalFilesDir("models")
        val candidates = buildList {
            add(File(internalDirectory, MODEL_NAME))
            if (externalDirectory != null) add(File(externalDirectory, MODEL_NAME))
            add(File(internalDirectory, FALLBACK_MODEL_NAME))
            if (externalDirectory != null) add(File(externalDirectory, FALLBACK_MODEL_NAME))
            add(File(internalDirectory, LEGACY_MODEL_NAME))
            if (externalDirectory != null) add(File(externalDirectory, LEGACY_MODEL_NAME))
        }
        return candidates.firstOrNull { it.isFile && it.canRead() }
    }

    private fun visibleAnswer(raw: String): String {
        var answer = raw
        val channelStarts = listOf("<|channel>thought", "<|channel|>thought")
        val channelStart = channelStarts.map(answer::indexOf).filter { it >= 0 }.minOrNull()
        if (channelStart != null) {
            val channelEnds = listOf("<channel|>", "<|end|>")
                .map { marker -> answer.indexOf(marker, channelStart) to marker.length }
                .filter { it.first >= 0 }
            val channelEnd = channelEnds.minByOrNull { it.first } ?: return ""
            answer = answer.substring(channelEnd.first + channelEnd.second)
        }

        val thinkStart = answer.indexOf("<think>")
        if (thinkStart >= 0) {
            val thinkEnd = answer.indexOf("</think>", thinkStart)
            if (thinkEnd < 0) return ""
            answer = answer.substring(thinkEnd + "</think>".length)
        }

        return answer
            .replace(Regex("<\\|channel(?:\\||>)(?:final|assistant)?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<\\|(?:end|think|start_of_turn|end_of_turn)\\|>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<channel\\|>|<unused\\d+>", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}
