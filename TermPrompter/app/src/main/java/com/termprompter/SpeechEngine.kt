package com.termprompter

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File

/** Thin wrapper around Vosk. 100% on-device: no network permission is even declared. */
class SpeechEngine {
    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var loadedPath: String? = null
    private var service: SpeechService? = null
    private var recognizer: Recognizer? = null

    /** Loads (or reuses) the model on a background thread; [done] runs on the main thread. */
    fun loadAsync(dir: File, done: (Boolean, String?) -> Unit) {
        if (model != null && loadedPath == dir.absolutePath) { done(true, null); return }
        Thread {
            try {
                stop()
                model?.close(); model = null
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                model = Model(dir.absolutePath)
                loadedPath = dir.absolutePath
                main.post { done(true, null) }
            } catch (t: Throwable) {
                loadedPath = null
                main.post { done(false, t.message ?: t.javaClass.simpleName) }
            }
        }.start()
    }

    /** Starts the microphone. [onText] gets (text, isFinal) on a background thread. */
    fun start(onText: (String, Boolean) -> Unit, onError: (String) -> Unit): Boolean {
        val m = model ?: return false
        stop()
        return try {
            val rec = Recognizer(m, SAMPLE_RATE)
            val s = SpeechService(rec, SAMPLE_RATE)
            s.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    val t = JSONObject(hypothesis ?: return).optString("partial")
                    if (t.isNotEmpty()) onText(t, false)
                }
                override fun onResult(hypothesis: String?) {
                    val t = JSONObject(hypothesis ?: return).optString("text")
                    onText(t, true)
                }
                override fun onFinalResult(hypothesis: String?) {
                    val t = JSONObject(hypothesis ?: return).optString("text")
                    onText(t, true)
                }
                override fun onError(exception: Exception?) { onError(exception?.message ?: "Microphone error") }
                override fun onTimeout() {}
            })
            recognizer = rec
            service = s
            true
        } catch (t: Throwable) {
            onError(t.message ?: "Could not start microphone")
            false
        }
    }

    /** Stops listening (releases the mic so the battery is not drained while paused). */
    fun stop() {
        try { service?.stop(); service?.shutdown() } catch (_: Throwable) {}
        service = null
        try { recognizer?.close() } catch (_: Throwable) {}
        recognizer = null
    }

    fun release() {
        stop()
        try { model?.close() } catch (_: Throwable) {}
        model = null; loadedPath = null
    }

    private companion object { const val SAMPLE_RATE = 16000.0f }
}
