package me.erguotou.homehub.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "-"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> String.format(Locale.getDefault(), "%.2f GB", gb)
        mb >= 1 -> String.format(Locale.getDefault(), "%.1f MB", mb)
        kb >= 1 -> String.format(Locale.getDefault(), "%.0f KB", kb)
        else -> "$bytes B"
    }
}

private val dateTimeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
private val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
private val monthFormatter = SimpleDateFormat("yyyy-MM", Locale.getDefault())

fun formatDateTime(seconds: Long): String =
    if (seconds <= 0) "-" else dateTimeFormatter.format(Date(seconds * 1000))

fun formatDate(seconds: Long): String =
    if (seconds <= 0) "-" else dateFormatter.format(Date(seconds * 1000))

fun formatMonth(seconds: Long): String =
    if (seconds <= 0) "-" else monthFormatter.format(Date(seconds * 1000))

/** Strips the highlight markers the server puts into FTS snippets. */
fun plainSnippet(snippet: String?): String =
    snippet?.replace("<b>", "")?.replace("</b>", "").orEmpty()
