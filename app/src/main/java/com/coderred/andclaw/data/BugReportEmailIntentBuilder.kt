@file:Suppress("PackageDirectoryMismatch")

package com.coderred.andclaw.data

import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class BugReportEmailSummary(
    val sessionErrorCount: Int,
    val hasGatewayError: Boolean,
    val hasProcessError: Boolean,
)

data class BugReportEmailMetadata(
    val appVersionName: String,
    val packageName: String,
    val androidSdkInt: Int,
    val deviceManufacturer: String,
    val deviceModel: String,
    val locale: String,
)

object BugReportEmailIntentBuilder {
    private const val RECIPIENT = "admin@coderred.com"

    fun build(
        artifact: BugReportZipArtifact,
        summary: BugReportEmailSummary,
        metadata: BugReportEmailMetadata,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Intent {
        val timestamp = formatTimestamp(nowEpochMs)
        val subject = "andClaw bug report v${metadata.appVersionName} [$timestamp]"
        val body = buildBody(summary = summary, metadata = metadata, timestamp = timestamp)

        return Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(RECIPIENT))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_STREAM, artifact.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun buildBody(
        summary: BugReportEmailSummary,
        metadata: BugReportEmailMetadata,
        timestamp: String,
    ): String {
        return buildString {
            appendLine("Hello AndClaw team,")
            appendLine()
            appendLine("Please find the attached bug report ZIP.")
            appendLine()
            appendLine("Summary")
            appendLine("- Created at: $timestamp")
            appendLine("- Session error count: ${summary.sessionErrorCount}")
            appendLine("- Gateway error present: ${summary.hasGatewayError}")
            appendLine("- Process error present: ${summary.hasProcessError}")
            appendLine()
            appendLine("App / Device metadata")
            appendLine("- Package: ${metadata.packageName}")
            appendLine("- App version: ${metadata.appVersionName}")
            appendLine("- Android SDK: ${metadata.androidSdkInt}")
            appendLine("- Device: ${metadata.deviceManufacturer} ${metadata.deviceModel}")
            appendLine("- Locale: ${metadata.locale}")
            appendLine()
            append("Privacy note: conversation text/content is not included.")
        }
    }

    private fun formatTimestamp(epochMs: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(epochMs))
    }
}
