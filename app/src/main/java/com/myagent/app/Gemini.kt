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
    fun generate(
        apiKey: String,
        model: String,
        system: String,
        contents: JSONArray,
        functions: JSONArray
    ): JSONObject {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", functions)))
        val payload = body.toString().toByteArray(Charsets.UTF_8)

        var attempt = 0
        while (true) {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(payload) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code in 200..299) return JSONObject(text)

            // Server busy / rate limit: wait a little and try again (up to 3 retries).
            if ((code == 503 || code == 429 || code == 500) && attempt < 3) {
                attempt++
                Thread.sleep(2000L * attempt)
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
