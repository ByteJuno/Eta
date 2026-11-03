package io.github.mangi.eta.agent.device

import android.app.Notification
import android.os.Bundle
import android.os.Parcelable

/**
 * Reads the human-visible text of a notification.
 *
 * Messaging apps (WeChat, Telegram, Instagram, ...) often put only a counter such as
 * "[2条]" or "10+ new messages" into EXTRA_TEXT and carry the real lines in
 * EXTRA_MESSAGES (MessagingStyle), EXTRA_TEXT_LINES (InboxStyle) or EXTRA_BIG_TEXT.
 * Prefer the richest available source so history and triggers keep the actual content.
 */
internal object NotificationContentExtractor {
    data class Content(val title: String?, val text: String?, val subText: String?)

    fun extract(notification: Notification): Content = extract(notification.extras)

    fun extract(extras: Bundle?): Content {
        if (extras == null) return Content(null, null, null)
        val title = extras.text(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.text(Notification.EXTRA_TITLE_BIG)
            ?: extras.text(Notification.EXTRA_TITLE)
        val text = messages(extras)
            ?: lines(extras)
            ?: extras.text(Notification.EXTRA_BIG_TEXT)
            ?: extras.text(Notification.EXTRA_TEXT)
        return Content(title, text, extras.text(Notification.EXTRA_SUB_TEXT))
    }

    private fun messages(extras: Bundle): String? {
        val raw = runCatching { extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java) }.getOrNull()
        if (raw.isNullOrEmpty()) return null
        // Most recent lines carry the content the user just received; keep a bounded tail.
        val entries = raw.takeLast(MAX_MESSAGES).mapNotNull { item -> (item as? Bundle)?.let(::messageLine) }
        return entries.joinToString("\n").takeIf { it.isNotBlank() }
    }

    private fun messageLine(bundle: Bundle): String? {
        val body = bundle.getCharSequence(KEY_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val sender = bundle.getCharSequence(KEY_SENDER)?.toString()?.trim()
            ?: runCatching { bundle.getParcelable(KEY_SENDER_PERSON, android.app.Person::class.java) }.getOrNull()
                ?.name?.toString()?.trim()
        return if (sender.isNullOrEmpty()) body else "$sender: $body"
    }

    private fun lines(extras: Bundle): String? =
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            ?.takeLast(MAX_MESSAGES)
            ?.joinToString("\n")
            ?.takeIf { it.isNotBlank() }

    private fun Bundle.text(key: String): String? =
        getCharSequence(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    // Keys used by Notification.MessagingStyle.Message#toBundle (stable since API 24).
    private const val KEY_TEXT = "text"
    private const val KEY_SENDER = "sender"
    private const val KEY_SENDER_PERSON = "sender_person"
    private const val MAX_MESSAGES = 8
}
