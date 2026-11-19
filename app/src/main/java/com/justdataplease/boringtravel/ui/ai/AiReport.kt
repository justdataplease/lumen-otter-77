package com.justdataplease.boringtravel.ui.ai

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.BuildConfig
import com.justdataplease.boringtravel.ai.GeminiPlanner
import com.justdataplease.boringtravel.ui.me.AboutApp
import com.justdataplease.boringtravel.ui.theme.Bt

/**
 * Reporting an AI planner answer (Google Play's AI-generated content policy: users can flag offensive output in the
 * app). The report is an email to us with the answer in it, written by the traveller's own mail app; nothing is sent
 * without them. Pure parts are tested in `AiReportTest`.
 */
object AiReport {
    const val SUBJECT = "BoringTravel: AI planner answer report"

    /** Longest answer copied into the email (mail apps cut very long intents). */
    const val MAX_ANSWER = 6000

    fun body(answer: String, model: String, version: String): String = buildString {
        append("What's wrong with this answer? (offensive, harmful, wrong…)\n\n\n")
        append("---\n")
        append("BoringTravel ").append(version).append(" · model ").append(model).append('\n')
        val text = answer.trim()
        append(if (text.length > MAX_ANSWER) text.take(MAX_ANSWER) + "\n[…cut]" else text)
    }

    /** The built-in planner's model: for an answer that didn't keep who wrote it. */
    fun model(): String = BuildConfig.AI_MODEL.ifBlank { GeminiPlanner.DEFAULT_MODEL }

    /**
     * Opens the mail app with the report; without one, copies it and says where to send it. [model] is who wrote the
     * answer, kept with it when it came ([com.justdataplease.boringtravel.ai.PlannerEngine.reportName]), not what
     * Me › AI says now.
     */
    fun send(context: Context, answer: String, model: String?) {
        val body = body(answer, model ?: AiReport.model(), BuildConfig.VERSION_NAME)
        val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(AboutApp.CONTACT))
            .putExtra(Intent.EXTRA_SUBJECT, SUBJECT)
            .putExtra(Intent.EXTRA_TEXT, body)
        try {
            context.startActivity(mail)
        } catch (_: ActivityNotFoundException) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText(SUBJECT, body))
            Toast.makeText(context, "No mail app. The report is copied: send it to ${AboutApp.CONTACT}", Toast.LENGTH_LONG).show()
        }
    }
}

/** "Report this answer" under an AI planner answer; [model] wrote it. */
@Composable
internal fun ReportAnswer(answer: String, model: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Text(
        "Report this answer",
        modifier.heightIn(min = 40.dp).clickable(role = Role.Button) { AiReport.send(context, answer, model) }.padding(vertical = 10.dp),
        style = Bt.text.fine,
        color = Bt.colors.muted,
    )
}
