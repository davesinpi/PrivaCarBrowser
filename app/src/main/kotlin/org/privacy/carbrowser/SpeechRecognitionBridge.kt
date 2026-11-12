package org.privacy.carbrowser

import android.content.Context
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wraps the native [SpeechRecognizer] and pushes results back into the page's
 * polyfill (window.__sr_event). One instance per WebView.
 */
class SpeechRecognitionBridge(private val context: Context, private val web: WebView) {

    private var recognizer: SpeechRecognizer? = null

    /** Called on the page's message thread; hop to the UI thread for recognizer + JS. */
    fun onMessage(payload: String) {
        val type = runCatching { JSONObject(payload).optString("type") }.getOrDefault("")
        val lang = runCatching { JSONObject(payload).optString("lang", "en-US") }.getOrDefault("en-US")
        web.post {
            when (type) {
                "start" -> start(lang)
                "stop" -> recognizer?.stopListening()
            }
        }
    }

    private fun start(lang: String) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            emit("error", JSONObject().put("error", "service-not-allowed")); return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
            startListening(android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            })
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partial: Bundle?) = pushResults(partial, false)
        override fun onResults(results: Bundle?) { pushResults(results, true); emit("end", JSONObject()) }
        override fun onEvent(t: Int, p: Bundle?) {}
        override fun onError(code: Int) {
            emit("error", JSONObject().put("error", if (code == SpeechRecognizer.ERROR_NO_MATCH) "no-speech" else "aborted"))
            emit("end", JSONObject())
        }
    }

    private fun pushResults(b: Bundle?, final: Boolean) {
        val words = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        if (words.isEmpty()) return
        emit("result", JSONObject().put("a", JSONArray(words)).put("f", final))
    }

    private fun emit(kind: String, data: JSONObject) {
        val js = "window.__sr_event && window.__sr_event('$kind', ${JSONObject.quote(data.toString())})"
        web.post { web.evaluateJavascript(js, null) }
    }

    fun destroy() { recognizer?.destroy(); recognizer = null }
}
