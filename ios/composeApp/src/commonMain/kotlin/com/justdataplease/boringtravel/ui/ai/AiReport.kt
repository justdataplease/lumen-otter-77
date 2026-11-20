package com.justdataplease.boringtravel.ui.ai

import androidx.compose.runtime.Composable
import com.justdataplease.boringtravel.ai.GeminiPlanner
import com.justdataplease.boringtravel.firebase.FirebaseSetup
import com.justdataplease.boringtravel.ios.LocalAppVersion
import com.justdataplease.boringtravel.ios.LocalPhone
import com.justdataplease.boringtravel.ios.rememberPlainCopy

/**
 * Reporting an AI planner answer (the Android app's ui/ai/AiReport.kt; the stores' AI-generated content rules: users
 * can flag offensive output in the app). The report is an email to us with the answer in it, written by the
 * traveller's own mail app; nothing is sent without them. Without a mail app it is copied.
 */
object AiReport {
    const val SUBJECT = "BoringTravel: AI planner answer report"

    /** Longest answer copied into the email (mail apps cut very long links). */
    const val MAX_ANSWER = 6000

    /** Where reports go (the Android app's AboutApp.CONTACT). */
    const val CONTACT = "contact@example.com"

    fun body(answer: String, model: String, version: String): String = buildString {
        append("What's wrong with this answer? (offensive, harmful, wrong…)\n\n\n")
        append("---\n")
        append("BoringTravel ").append(version).append(" · model ").append(model).append('\n')
        val text = answer.trim()
        append(if (text.length > MAX_ANSWER) text.take(MAX_ANSWER) + "\n[…cut]" else text)
    }

    /** The built-in planner's model: for an answer that didn't keep who wrote it. */
    fun model(): String = FirebaseSetup.aiModel.ifBlank { GeminiPlanner.DEFAULT_MODEL }

    /** The mail as a mailto: link (to [CONTACT], with [SUBJECT] and [body]), for the phone's mail app. */
    fun mailto(body: String): String = "mailto:$CONTACT?subject=" + escape(SUBJECT) + "&body=" + escape(body)

    /** What shows when no mail app took the report. */
    const val NO_MAIL = "No mail app. The report is copied: send it to $CONTACT"

    /** RFC 6068: everything but the unreserved characters as %XX of its UTF-8 bytes. */
    private fun escape(text: String): String = buildString {
        for (b in text.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c.toChar() in "-._~") {
                append(c.toChar())
            } else {
                append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
            }
        }
    }
}

/**
 * Sends a report of an answer as Android's AiReport.send does: the mail app with the report; without one, the report
 * copied and [say] tells where to send it. [model] is who wrote the answer, kept with it when it came.
 */
@Composable
fun rememberAiReport(say: (String) -> Unit): (answer: String, model: String?) -> Unit {
    val phone = LocalPhone.current
    val copy = rememberPlainCopy()
    val version = LocalAppVersion.current
    return { answer, model ->
        val body = AiReport.body(answer, model ?: AiReport.model(), version)
        phone.openLink(AiReport.mailto(body)) { opened ->
            if (!opened) {
                copy(body)
                say(AiReport.NO_MAIL)
            }
        }
    }
}
