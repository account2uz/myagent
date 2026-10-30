package com.myagent.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The "brain loop":
 *  user text -> Gemini -> (Gemini asks to use a tool -> we run it -> tell Gemini the result) -> final reply
 */
class Agent(
    private val ctx: Context,
    confirmer: Confirmer,
    private val onStatus: (String) -> Unit = {}
) {

    private val history = JSONArray()
    private val tools = Tools(ctx, confirmer)

    private fun systemPrompt(): String {
        val owner = Prefs.get(ctx, "owner").ifBlank { "the user" }
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.US).format(Date())
        val tz = TimeZone.getDefault().id
        val appName = ctx.getString(R.string.app_name)
        return """
            You are $appName, the personal voice assistant of $owner, running on their Android phone.
            Current date/time: $now ($tz).
            - Reply in the same language the user used (Bangla in Bangla script, English in English). Keep replies short and natural, because they are spoken aloud.
            - Use the tools to actually do things (call, SMS, WhatsApp, alarm, timer, open apps). Never pretend an action was done if you did not call a tool.
            - Never invent phone numbers. If a contact is not found or ambiguous, say so briefly and ask.
            - When the user asks to tell someone something (e.g. "Ammu ke bolo ajke late hobe"), write a short, polite message in the language the recipient would expect (usually Bangla) and send it with the best tool: whatsapp_message if they mention WhatsApp, send_sms if they say SMS/text, call_contact if they say call.
            - The text comes from speech recognition and may contain small mistakes; use common sense.
            - For relative times like "5 minute por alarm", compute the exact clock time from the current time.
        """.trimIndent()
    }

    private fun isUserText(c: JSONObject): Boolean {
        if (c.optString("role") != "user") return false
        val p = c.optJSONArray("parts") ?: return false
        return p.length() > 0 && p.getJSONObject(0).has("text")
    }

    private fun trim() {
        while (history.length() > 30) history.remove(0)
        while (history.length() > 0 && !isUserText(history.getJSONObject(0))) history.remove(0)
    }

    fun clear() {
        while (history.length() > 0) history.remove(0)
    }

    /** Blocking. Call from a background thread. Returns the text to show/speak. */
    fun run(userText: String): String {
        val key = Prefs.get(ctx, "key").trim()
        if (key.isEmpty()) return "Age Settings (⚙) e giye Gemini API key boshao."
        val model = Prefs.get(ctx, "model", "gemini-2.5-flash").ifBlank { "gemini-2.5-flash" }

        val startLen = history.length()
        try {
            history.put(
                JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", userText)))
            )
            trim()

            repeat(6) {
                val resp = Gemini.generate(key, model, systemPrompt(), history, tools.declarations())
                val content = resp.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                    ?: throw Exception("Gemini kono uttor dilo na. " + (resp.optJSONObject("promptFeedback")?.toString() ?: ""))

                val parts = content.optJSONArray("parts") ?: JSONArray()
                history.put(content) // keep exactly what Gemini sent

                val calls = ArrayList<JSONObject>()
                val text = StringBuilder()
                for (i in 0 until parts.length()) {
                    val p = parts.getJSONObject(i)
                    if (p.has("functionCall")) calls.add(p.getJSONObject("functionCall"))
                    else if (p.has("text") && !p.optBoolean("thought", false)) text.append(p.getString("text"))
                }

                if (calls.isEmpty()) {
                    return text.toString().trim().ifEmpty { "Hoye geche." }
                }

                val results = JSONArray()
                for (c in calls) {
                    val name = c.getString("name")
                    val args = c.optJSONObject("args") ?: JSONObject()
                    onStatus("⚙ $name")
                    val result = try {
                        tools.execute(name, args)
                    } catch (e: Exception) {
                        "Error: ${e.message}"
                    }
                    results.put(
                        JSONObject().put(
                            "functionResponse",
                            JSONObject().put("name", name).put("response", JSONObject().put("result", result))
                        )
                    )
                }
                history.put(JSONObject().put("role", "user").put("parts", results))
            }
            return "Onek step hoye gelo, abar bolo."
        } catch (e: Exception) {
            // undo this turn so the next request is not broken
            while (history.length() > startLen) history.remove(history.length() - 1)
            return "Somossha: ${e.message}"
        }
    }
}
