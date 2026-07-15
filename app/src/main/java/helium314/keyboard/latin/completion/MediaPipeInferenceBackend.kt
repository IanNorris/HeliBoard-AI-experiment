// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

import android.content.Context
import androidx.annotation.RequiresApi
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions

/**
 * [InferenceBackend] backed by MediaPipe's LLM Inference API.
 *
 * This is the only inherently device-only piece of the completion feature: everything above it
 * (engine, provider, prompt/response handling, download) is unit-tested on the JVM, while actual
 * inference quality, latency and memory can only be measured on a real device.
 *
 * MediaPipe requires API 24+, so this class is annotated accordingly and MUST only be constructed
 * after a runtime [android.os.Build.VERSION.SDK_INT] check; no other code references MediaPipe types
 * so the app still installs and runs on older devices with the feature disabled.
 *
 * All methods are expected to run on the provider's single worker thread (never the IME thread):
 * model creation and generation are both blocking and can take hundreds of ms to seconds.
 */
@RequiresApi(24)
class MediaPipeInferenceBackend @JvmOverloads constructor(
    private val context: Context,
    private val maxTokens: Int = 64,
    private val topK: Int = 40,
) : InferenceBackend {

    @Volatile private var engine: LlmInference? = null

    override val isLoaded: Boolean get() = engine != null

    override fun load(modelPath: String) {
        if (engine != null) return
        val options = LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(maxTokens)
            .setMaxTopK(topK)
            .build()
        engine = LlmInference.createFromOptions(context, options)
    }

    override fun generate(prompt: String, maxTokens: Int): String {
        val e = engine ?: return ""
        // blocking single-shot generation; length is bounded by the options' maxTokens
        return e.generateResponse(prompt) ?: ""
    }

    override fun close() {
        val e = engine ?: return
        engine = null
        try { e.close() } catch (_: Exception) { }
    }
}
