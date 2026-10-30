package com.myagent.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * The agent's "hands". Each tool = one small function the AI can call.
 * To add a new ability: add it in declarations() and in execute().
 */
class Tools(private val ctx: Context, private val confirmer: Confirmer) {

    // ---------- Tool list shown to the AI ----------

    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun int(desc: String) = JSONObject().put("type", "integer").put("description", desc)

    private fun fn(name: String, desc: String, props: JSONObject, required: List<String>): JSONObject {
        val params = JSONObject().put("type", "object").put("properties", props)
        if (required.isNotEmpty()) params.put("required", JSONArray(required))
        return JSONObject().put("name", name).put("description", desc).put("parameters", params)
    }

    fun declarations(): JSONArray = JSONArray()
        .put(
            fn(
                "call_contact",
                "Make a normal phone call to a saved contact (by name) or to a phone number.",
                JSONObject().put("who", str("Contact name exactly as the user said it, or a phone number")),
                listOf("who")
            )
        )
        .put(
            fn(
                "send_sms",
                "Send an SMS text message to a saved contact or phone number.",
                JSONObject()
                    .put("who", str("Contact name or phone number"))
                    .put("message", str("The SMS text to send")),
                listOf("who", "message")
            )
        )
        .put(
            fn(
                "whatsapp_message",
                "Open WhatsApp chat with a contact and prefill the message. The user taps Send themselves.",
                JSONObject()
                    .put("who", str("Contact name or phone number"))
                    .put("message", str("The message text to prefill")),
                listOf("who", "message")
            )
        )
        .put(
            fn(
                "set_alarm",
                "Set an alarm clock at a given time (24-hour).",
                JSONObject()
                    .put("hour", int("Hour 0-23"))
                    .put("minute", int("Minute 0-59"))
                    .put("label", str("Optional alarm label")),
                listOf("hour", "minute")
            )
        )
        .put(
            fn(
                "set_timer",
                "Start a countdown timer.",
                JSONObject()
                    .put("seconds", int("Timer length in seconds"))
                    .put("label", str("Optional timer label")),
                listOf("seconds")
            )
        )
        .put(
            fn(
                "open_app",
                "Open an installed app on the phone by its name, e.g. YouTube, Camera, Settings.",
                JSONObject().put("app_name", str("App name")),
                listOf("app_name")
            )
        )

    // ---------- Running a tool ----------

    fun execute(name: String, args: JSONObject): String = when (name) {
        "call_contact" -> callContact(args.optString("who"))
        "send_sms" -> sendSms(args.optString("who"), args.optString("message"))
        "whatsapp_message" -> whatsapp(args.optString("who"), args.optString("message"))
        "set_alarm" -> setAlarm(args.optInt("hour", -1), args.optInt("minute", -1), args.optString("label"))
        "set_timer" -> setTimer(args.optInt("seconds", -1), args.optString("label"))
        "open_app" -> openApp(args.optString("app_name"))
        else -> "Unknown tool: $name"
    }

    // ---------- Helpers ----------

    private fun has(perm: String) = ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun confirm(message: String): Boolean = confirmer.confirm(message)

    /** Starts an activity (works from the screen or from the background service). */
    private fun launch(intent: Intent) {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private class Resolved(val name: String, val number: String, val error: String? = null)

    /** Turns "Ammu" into a phone number using the phone's contacts. */
    private fun resolve(who: String): Resolved {
        val q = who.trim()
        if (q.isEmpty()) return Resolved("", "", "No contact name given.")
        if (Regex("^[+0-9()\\-\\s]{5,}$").matches(q)) return Resolved(q, q.replace(Regex("[\\s()\\-]"), ""))
        if (!has(Manifest.permission.READ_CONTACTS)) {
            return Resolved("", "", "Contacts permission not granted. Ask the user to allow it in phone Settings > Apps.")
        }

        val found = LinkedHashMap<String, String>() // name -> number
        val p = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        ctx.contentResolver.query(
            p, cols, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$q%"), null
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                if (!found.containsKey(n)) found[n] = num
            }
        }

        if (found.isEmpty()) {
            return Resolved("", "", "No contact found matching '$q'. Try another spelling (Bangla/English) or ask the user.")
        }
        val exact = found.keys.firstOrNull { it.equals(q, ignoreCase = true) }
        if (exact != null) return Resolved(exact, found[exact]!!)
        if (found.size == 1) {
            val e = found.entries.first()
            return Resolved(e.key, e.value)
        }
        return Resolved("", "", "Several contacts match: ${found.keys.take(6).joinToString(", ")}. Ask the user which one.")
    }

    private fun waNumber(raw: String): String {
        val countryCode = Prefs.get(ctx, "country", "880").ifBlank { "880" }
        var d = raw.filter { it.isDigit() }
        if (raw.trim().startsWith("+")) return d
        if (d.startsWith("00")) return d.drop(2)
        if (d.startsWith("0")) d = countryCode + d.drop(1)
        return d
    }

    // ---------- The tools ----------

    private fun callContact(who: String): String {
        if (!has(Manifest.permission.CALL_PHONE)) return "Call permission not granted. Ask the user to allow Phone permission in Settings > Apps."
        val r = resolve(who)
        if (r.error != null) return r.error
        if (!confirm("Call ${r.name} (${r.number})?")) return "User cancelled the call."
        launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(r.number))))
        return "Calling ${r.name}."
    }

    private fun sendSms(who: String, message: String): String {
        if (!has(Manifest.permission.SEND_SMS)) return "SMS permission not granted. Ask the user to allow SMS permission in Settings > Apps."
        if (message.isBlank()) return "Message is empty."
        val r = resolve(who)
        if (r.error != null) return r.error
        if (!confirm("Send SMS to ${r.name} (${r.number})?\n\n\"$message\"")) return "User cancelled the SMS."
        val sm = ctx.getSystemService(SmsManager::class.java)
        val parts = sm.divideMessage(message)
        sm.sendMultipartTextMessage(r.number, null, parts, null, null)
        return "SMS sent to ${r.name}."
    }

    private fun whatsapp(who: String, message: String): String {
        val r = resolve(who)
        if (r.error != null) return r.error
        val num = waNumber(r.number)
        val uri = Uri.parse("https://wa.me/$num?text=" + Uri.encode(message))
        val i = Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp")
        return try {
            launch(i)
            "WhatsApp chat with ${r.name} opened with the message ready. The user must tap Send."
        } catch (e: Exception) {
            try {
                launch(Intent(Intent.ACTION_VIEW, uri))
                "WhatsApp link opened for ${r.name}. The user must tap Send."
            } catch (e2: Exception) {
                "Could not open WhatsApp: ${e2.message}"
            }
        }
    }

    private fun setAlarm(hour: Int, minute: Int, label: String): String {
        if (hour !in 0..23 || minute !in 0..59) return "Invalid time."
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return try {
            launch(i)
            "Alarm set for %02d:%02d.".format(hour, minute)
        } catch (e: Exception) {
            "Could not set alarm: ${e.message}"
        }
    }

    private fun setTimer(seconds: Int, label: String): String {
        if (seconds <= 0) return "Invalid timer length."
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return try {
            launch(i)
            "Timer started for $seconds seconds."
        } catch (e: Exception) {
            "Could not start timer: ${e.message}"
        }
    }

    private fun openApp(appName: String): String {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        val q = appName.trim().lowercase()
        if (q.isEmpty()) return "No app name given."
        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: return "No installed app found named '$appName'."
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "Cannot open ${match.loadLabel(pm)}."
        launch(intent)
        return "Opened ${match.loadLabel(pm)}."
    }
}
