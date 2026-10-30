package com.myagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream
import android.speech.RecognitionListener as SpeechListener
import org.vosk.android.RecognitionListener as VoskListener

/**
 * Hands-free mode.
 *
 *   [Vosk listens offline for "hey agent"]
 *        -> beep -> [Google speech recognizer hears your command]
 *        -> Agent (Gemini + tools) -> spoken reply -> back to waiting.
 */
class AgentService : Service() {

    companion object {
        @Volatile var running = false
        const val ACTION_STOP = "com.myagent.app.STOP"
        private const val CHANNEL = "agent_channel"
        private const val NOTIF_ID = 1
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    }

    private enum class S { IDLE, WAKE, BUSY }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var state = S.IDLE
    @Volatile private var loading = false
    private var destroyed = false

    private var model: Model? = null
    private var speech: SpeechService? = null
    private var recognizer: SpeechRecognizer? = null
    private lateinit var speaker: Speaker
    private lateinit var agent: Agent

    // ---------- Service lifecycle ----------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Agent", NotificationManager.IMPORTANCE_LOW))
        speaker = Speaker(this)
        agent = Agent(this, object : Confirmer {
            override fun confirm(message: String): Boolean = voiceConfirm(message)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val n = buildNotification("Starting...")
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            Prefs.put(this, "svc_error", "Service start hoyni: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        if (model == null) {
            if (!loading) {
                loading = true
                Thread { prepareModel() }.start()
            }
        } else if (state == S.IDLE) {
            main.post { startWake() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        stopWake()
        recognizer?.destroy()
        recognizer = null
        try { model?.close() } catch (e: Exception) { }
        model = null
        speaker.shutdown()
        super.onDestroy()
    }

    // ---------- Notification ----------

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AgentService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    private fun update(text: String) {
        if (destroyed) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    // ---------- Wake-word model (downloaded once, ~40 MB) ----------

    private fun prepareModel() {
        try {
            val dir = File(filesDir, "vosk-model")
            if (!File(dir, "am").exists()) {
                update("Downloading wake-word model (~40 MB, one time)...")
                downloadModel(dir)
            }
            update("Loading wake-word model...")
            model = Model(dir.absolutePath)
            loading = false
            main.post { startWake() }
        } catch (e: Exception) {
            loading = false
            Prefs.put(this, "svc_error", "Wake-word model load hoyni: ${e.message}")
            main.post { stopSelf() }
        }
    }

    private fun downloadModel(dir: File) {
        val zip = File(cacheDir, "vosk-model.zip")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        if (conn.responseCode != 200) throw Exception("Download HTTP ${conn.responseCode}")
        conn.inputStream.use { input -> zip.outputStream().use { out -> input.copyTo(out) } }

        val tmp = File(filesDir, "vosk-tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val base = tmp.canonicalPath
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                val rel = e.name.substringAfter('/', "")
                if (rel.isNotEmpty()) {
                    val out = File(tmp, rel)
                    if (!out.canonicalPath.startsWith(base)) throw Exception("Bad zip entry")
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zin.copyTo(it) }
                    }
                }
                e = zin.nextEntry
            }
        }
        zip.delete()
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) throw Exception("Could not move model folder")
    }

    // ---------- Waiting for "hey agent" ----------

    private fun startWake() {
        if (destroyed) return
        val m = model ?: return
        stopWake()
        try {
            val phrase = Prefs.get(this, "wake", "hey agent").ifBlank { "hey agent" }.trim().lowercase()
            // Only listen for this phrase (everything else = "[unk]"), so it is accurate and light.
            val grammar = JSONArray().put(phrase).put("[unk]").toString()
            val rec = Recognizer(m, 16000.0f, grammar)
            val s = SpeechService(rec, 16000.0f)
            speech = s
            state = S.WAKE
            s.startListening(object : VoskListener {
                override fun onPartialResult(hypothesis: String?) = heard(hypothesis, "partial", phrase)
                override fun onResult(hypothesis: String?) = heard(hypothesis, "text", phrase)
                override fun onFinalResult(hypothesis: String?) {}
                override fun onError(exception: Exception?) {
                    update("Mic error: ${exception?.message}")
                }
                override fun onTimeout() {}
            })
            update("Say \"$phrase\"")
        } catch (e: Exception) {
            update("Wake error: ${e.message}")
        }
    }

    private fun stopWake() {
        val s = speech ?: return
        speech = null
        try {
            s.stop()
            s.shutdown()
        } catch (e: Exception) {
        }
    }

    private fun heard(hypothesis: String?, key: String, phrase: String) {
        if (state != S.WAKE || hypothesis == null) return
        val text = try { JSONObject(hypothesis).optString(key) } catch (e: Exception) { "" }
        if (text.contains(phrase)) {
            state = S.BUSY
            main.post { onWake() }
        }
    }

    // ---------- After the wake word ----------

    private fun beep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            main.postDelayed({ tg.release() }, 400)
        } catch (e: Exception) {
        }
    }

    private fun onWake() {
        stopWake() // free the microphone for the command recognizer
        beep()
        update("Listening...")
        main.postDelayed({
            listenOnce { text ->
                if (text.isNullOrBlank()) {
                    startWake()
                } else {
                    update("Working: $text")
                    Thread {
                        val reply = agent.run(text)
                        speaker.speakAndWait(reply)
                        main.post { startWake() }
                    }.start()
                }
            }
        }, 350)
    }

    /** Listens once with Google speech recognition. Must run on the main thread. */
    private fun listenOnce(callback: (String?) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            callback(null)
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        var done = false

        fun finish(text: String?) {
            if (done) return
            done = true
            main.post { r.destroy() }
            if (recognizer === r) recognizer = null
            callback(text)
        }

        r.setRecognitionListener(object : SpeechListener {
            override fun onResults(results: Bundle?) {
                finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            }
            override fun onError(error: Int) = finish(null)
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val lang = Prefs.get(this, "lang", "bn-BD").ifBlank { "bn-BD" }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        try {
            r.startListening(i)
        } catch (e: Exception) {
            finish(null)
            return
        }
        main.postDelayed({ finish(null) }, 15000) // safety: never wait forever
    }

    /** Hands-free Yes/No: speaks the question, then listens for the answer. */
    private fun voiceConfirm(message: String): Boolean {
        speaker.speakAndWait("$message Say yes or no.")
        val latch = CountDownLatch(1)
        val answer = AtomicReference<String?>(null)
        main.post {
            beep()
            main.postDelayed({
                listenOnce { t ->
                    answer.set(t)
                    latch.countDown()
                }
            }, 350)
        }
        latch.await(25, TimeUnit.SECONDS)
        val a = (answer.get() ?: "").lowercase()
        val no = listOf("no", "না", "নো", "cancel", "বাদ", "থাক", "stop", "nah")
        val yes = listOf("yes", "yeah", "yep", "ok", "sure", "han", "ha", "হ্যাঁ", "হ্যা", "হাঁ", "হা", "ঠিক", "ইয়েস", "ওকে", "জি", "করো", "কর")
        if (no.any { a.contains(it) }) return false
        return yes.any { a.contains(it) }
    }
}
