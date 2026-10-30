package com.myagent.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Settings saved on the phone only (never inside the code / GitHub). */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("agent", Context.MODE_PRIVATE)
    fun get(c: Context, key: String, def: String = ""): String = sp(c).getString(key, def) ?: def
    fun put(c: Context, key: String, value: String) = sp(c).edit().putString(key, value).apply()
}

/** Minimal Gemini REST client, no libraries needed. */
object Gemini {

    // Ask the model to think very little (much faster). If the model rejects it, we turn it off.
    @Volatile private var fastThinking = true

    fun generate(
        apiKey: String,
        model: String,
        system: String,
        contents: JSONArray,
        functions: JSONArray
    ): JSONObject {
        var retries = 0
        while (true) {
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", contents)
                .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", functions)))
            val usedFast = fastThinking
            if (usedFast) {
                body.put(
                    "generationConfig",
                    JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
                )
            }

            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code in 200..299) return JSONObject(text)

            // Model did not accept the "think less" setting -> stop sending it, try again at once.
            if (code == 400 && usedFast && text.contains("think", ignoreCase = true)) {
                fastThinking = false
                continue
            }

            // Server busy / rate limit: wait a little and try again (up to 3 retries).
            if ((code == 503 || code == 429 || code == 500) && retries < 3) {
                retries++
                Thread.sleep(1000L * retries)
                continue
            }

            val msg = try {
                JSONObject(text).getJSONObject("error").getString("message")
            } catch (e: Exception) {
                text.take(300)
            }
            throw Exception("Gemini error $code: $msg")
        }
    }
}
