package com.myagent.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Something that can ask the user "Yes or No?" before a risky action (call / SMS). */
interface Confirmer {
    /** Blocking. Call from a background thread. */
    fun confirm(message: String): Boolean
}

/** Screen version: shows a Yes/No dialog (used by the chat screen). */
class DialogConfirmer(private val act: Activity) : Confirmer {
    override fun confirm(message: String): Boolean {
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        act.runOnUiThread {
            AlertDialog.Builder(act)
                .setTitle("Confirm")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Yes") { _, _ -> ok.set(true); latch.countDown() }
                .setNegativeButton("No") { _, _ -> latch.countDown() }
                .show()
        }
        latch.await()
        return ok.get()
    }
}

/** Text-to-speech that can wait until the sentence is finished. */
class Speaker(ctx: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(ctx, this)
    @Volatile private var ready = false
    @Volatile private var latch: CountDownLatch? = null

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { latch?.countDown() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { latch?.countDown() }
        })
    }

    /** Blocking. Call from a background thread. */
    fun speakAndWait(text: String) {
        val t = tts ?: return
        if (!ready || text.isBlank()) return
        val bangla = text.any { it in 'ঀ'..'৿' }
        val r = t.setLanguage(if (bangla) Locale("bn", "BD") else Locale.US)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            t.setLanguage(Locale.getDefault())
        }
        val l = CountDownLatch(1)
        latch = l
        if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime()) != TextToSpeech.SUCCESS) return
        l.await(60, TimeUnit.SECONDS)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
