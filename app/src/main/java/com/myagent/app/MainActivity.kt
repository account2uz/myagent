package com.myagent.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private val BG = Color.parseColor("#0F1115")
    private val ACCENT = Color.parseColor("#6C5CE7")
    private val GREEN = Color.parseColor("#1F9D55")
    private val BOT_BUBBLE = Color.parseColor("#1D2130")

    private lateinit var chatBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var handsBtn: TextView
    private lateinit var agent: Agent
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var busy = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        agent = Agent(this, DialogConfirmer(this)) { showStatus(it) }
        tts = TextToSpeech(this, this)
        buildUi()
        askPermissions()

        if (Prefs.get(this, "key").isBlank()) {
            addBubble("Shuru korar age ⚙ chepe Gemini API key boshao.", false)
        } else {
            addBubble("Hello! Upore 🎧 chepe hands-free on koro, ba mic chepe bolo.", false)
        }
    }

    override fun onResume() {
        super.onResume()
        updateHandsBtn(AgentService.running)
        val err = Prefs.get(this, "svc_error")
        if (err.isNotBlank()) {
            Prefs.put(this, "svc_error", "")
            addBubble(err, false)
            updateHandsBtn(false)
        }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------- Permissions ----------

    private fun askPermissions() {
        val list = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        val wanted = list.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 100)
    }

    // ---------- Hands-free on/off ----------

    private fun updateHandsBtn(on: Boolean) {
        handsBtn.text = if (on) "🎧 Hands-free ON (tap to stop)" else "🎧 Hands-free OFF (tap to start)"
        handsBtn.background = rounded(if (on) GREEN else BOT_BUBBLE, 20)
    }

    private fun toggleHandsFree() {
        if (AgentService.running) {
            stopService(Intent(this, AgentService::class.java))
            updateHandsBtn(false)
            return
        }
        if (Prefs.get(this, "key").isBlank()) {
            toast("Age ⚙ e Gemini API key boshao.")
            showSettings()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            toast("Microphone permission allow koro, tarpor abar 🎧 chapo.")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("'Display over other apps' ON koro, tarpor ferot eshe abar 🎧 chapo.")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Battery 'Unrestricted/Allow' koro (na hole phone app ta mere dibe), tarpor abar 🎧 chapo.")
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            return
        }
        startForegroundService(Intent(this, AgentService::class.java))
        updateHandsBtn(true)
        toast("Prothom bar ~40MB wake-word model download hobe. Notification dekho.")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------- UI ----------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        // header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(4))
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(iconButton("🗑") { agent.clear(); chatBox.removeAllViews(); addBubble("New chat.", false) })
        header.addView(iconButton("⚙") { showSettings() })
        root.addView(header)

        // hands-free toggle
        handsBtn = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { toggleHandsFree() }
        }
        val hlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        hlp.setMargins(dp(12), dp(4), dp(12), dp(4))
        root.addView(handsBtn, hlp)
        updateHandsBtn(false)

        // chat
        chatBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        scroll = ScrollView(this).apply { addView(chatBox) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // bottom bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }
        input = EditText(this).apply {
            hint = "Type a command..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = rounded(BOT_BUBBLE, 24)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { sendFromInput(); true } else false
            }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(circleButton("➤", 44, BOT_BUBBLE) { sendFromInput() })
        bar.addView(circleButton("🎤", 56, ACCENT) { startVoice() })
        root.addView(bar)

        setContentView(root)
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun iconButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 22f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { onClick() }
    }

    private fun circleButton(label: String, sizeDp: Int, color: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 20f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { leftMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun addBubble(text: String, fromUser: Boolean): TextView {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(if (fromUser) ACCENT else BOT_BUBBLE, 18)
            setTextIsSelectable(true)
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(8)
        if (fromUser) { lp.gravity = Gravity.END; lp.leftMargin = dp(56) }
        else { lp.gravity = Gravity.START; lp.rightMargin = dp(56) }
        chatBox.addView(tv, lp)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        return tv
    }

    /** Small status line while a tool runs (called from the agent thread). */
    fun showStatus(text: String) {
        runOnUiThread {
            val tv = addBubble(text, false)
            tv.textSize = 12f
            tv.alpha = 0.6f
        }
    }

    // ---------- Sending ----------

    private fun sendFromInput() {
        val t = input.text.toString().trim()
        if (t.isNotEmpty()) { input.setText(""); send(t) }
    }

    private fun send(text: String) {
        if (busy) { Toast.makeText(this, "Ektu opekkha koro...", Toast.LENGTH_SHORT).show(); return }
        busy = true
        tts?.stop()
        addBubble(text, true)
        val thinking = addBubble("…", false)
        Thread {
            val reply = agent.run(text)
            runOnUiThread {
                thinking.text = reply
                busy = false
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                speak(reply)
            }
        }.start()
    }

    // ---------- Voice in / out (tap-to-talk) ----------

    private fun startVoice() {
        val lang = Prefs.get(this, "lang", "bn-BD").ifBlank { "bn-BD" }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Bolo...")
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, 1)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Phone e voice input nei. Google app install koro.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) send(text)
        }
    }

    private fun speak(text: String) {
        val t = tts ?: return
        if (!ttsReady || text.isBlank()) return
        val bangla = text.any { it in 'ঀ'..'৿' }
        val result = t.setLanguage(if (bangla) Locale("bn", "BD") else Locale.US)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            t.setLanguage(Locale.getDefault())
            if (bangla) Toast.makeText(this, "Bangla voice nei. Phone Settings e Google TTS e Bangla download koro.", Toast.LENGTH_LONG).show()
        }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    // ---------- Settings ----------

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(0))
        }
        fun field(label: String, value: String, password: Boolean = false): EditText {
            box.addView(TextView(this).apply { text = label; textSize = 12f; setPadding(0, dp(10), 0, 0) })
            val e = EditText(this).apply {
                setText(value)
                setSingleLine()
                if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            box.addView(e)
            return e
        }

        val key = field("Gemini API key", Prefs.get(this, "key"), true)
        val owner = field("Tomar naam", Prefs.get(this, "owner"))
        val wake = field("Wake phrase (English, jemon: hey agent)", Prefs.get(this, "wake", "hey agent"))
        val model = field("Gemini model", Prefs.get(this, "model", "gemini-3.8-flash"))
        val lang = field("Voice language (bn-BD / en-US)", Prefs.get(this, "lang", "bn-BD"))
        val country = field("Country code (Bangladesh = 880)", Prefs.get(this, "country", "880"))

        val sv = ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(sv)
            .setPositiveButton("Save") { _, _ ->
                Prefs.put(this, "key", key.text.toString().trim())
                Prefs.put(this, "owner", owner.text.toString().trim())
                Prefs.put(this, "wake", wake.text.toString().trim())
                Prefs.put(this, "model", model.text.toString().trim())
                Prefs.put(this, "lang", lang.text.toString().trim())
                Prefs.put(this, "country", country.text.toString().trim())
                Toast.makeText(this, "Saved. Hands-free cholle off/on koro.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
