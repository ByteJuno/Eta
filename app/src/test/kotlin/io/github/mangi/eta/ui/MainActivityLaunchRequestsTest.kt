package io.github.mangi.eta.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.ui.app.ArchivedConversationRequest
import io.github.mangi.eta.ui.voice.ACTION_SPEECH_SETTINGS
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MainActivityLaunchRequestsTest {
    @Test
    fun executionEntryKeepsSourceAcrossRecreationAndDoesNotReplayConsumedKey() {
        val intent = Intent(MainActivity.ACTION_VIEW_EXECUTION)
            .putExtra(EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY, "virtual-run")
            .putExtra(MainActivity.EXTRA_EXECUTION_SOURCE, "automation")
        val pending = MainActivityLaunchRequests.restore(null, intent)
        assertEquals("virtual-run", pending.assistantConversationKey)
        assertEquals("automation", pending.assistantConversationSource)
        assertEquals(pending, MainActivityLaunchRequests.restore(savedState(pending), intent))
        val consumed = pending.copy(assistantConversationKey = null)
        assertEquals(consumed, MainActivityLaunchRequests.restore(savedState(consumed), intent))
        val voice = consumed.accept(Intent(EtaAssistantOverlayService.ACTION_OPEN_CONVERSATION)
            .putExtra(EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY, "voice-run"))
        assertEquals(AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, voice.assistantConversationSource)
    }

    @Test
    fun `重建不重新执行已消费的启动会话`() {
        val originalIntent = Intent(EtaAssistantOverlayService.ACTION_OPEN_CONVERSATION)
            .putExtra(EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY, "old-conversation")
        val pending = MainActivityLaunchRequests.restore(null, originalIntent)
        assertEquals("old-conversation", pending.assistantConversationKey)
        val consumed = pending.copy(assistantConversationKey = null)

        val restored = MainActivityLaunchRequests.restore(savedState(consumed), originalIntent)

        assertEquals(MainActivityLaunchRequests(), restored)
    }

    @Test
    fun `重建保留尚未完成的跳转且不被原始 Intent 覆盖`() {
        val pending = MainActivityLaunchRequests(
            assistantConversationKey = "assistant",
            conversationId = "conversation",
            archivedConversation = ArchivedConversationRequest("source", "archive"),
            speechSettings = true,
            terminal = true,
        )

        val restored = MainActivityLaunchRequests.restore(
            savedState(pending),
            Intent(MainActivity.ACTION_OPEN_CONVERSATION_ID)
                .putExtra(MainActivity.EXTRA_CONVERSATION_ID, "original"),
        )

        assertEquals(pending, restored)
    }

    @Test
    fun `桌面再次进入不产生跳转也不清除尚未完成的请求`() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pending = MainActivityLaunchRequests(conversationId = "conversation")

        assertEquals(MainActivityLaunchRequests(), MainActivityLaunchRequests().accept(launcher))
        assertEquals(pending, pending.accept(launcher))
    }

    @Test
    fun `真正的新 Intent 在恢复后仍能打开目标入口`() {
        val restored = MainActivityLaunchRequests.restore(savedState(MainActivityLaunchRequests()), null)

        assertEquals(
            MainActivityLaunchRequests(terminal = true),
            restored.accept(Intent(MainActivity.ACTION_OPEN_TERMINAL)),
        )
        assertEquals(
            MainActivityLaunchRequests(speechSettings = true),
            restored.accept(Intent(ACTION_SPEECH_SETTINGS)),
        )
        assertEquals(
            MainActivityLaunchRequests(archivedConversation = ArchivedConversationRequest("source", "archive")),
            restored.accept(
                Intent(MainActivity.ACTION_OPEN_ARCHIVED_CONVERSATION)
                    .putExtra(MainActivity.EXTRA_HANDOFF_SOURCE, "source")
                    .putExtra(MainActivity.EXTRA_CONVERSATION_KEY, "archive"),
            ),
        )
    }

    @Test
    fun `无效会话参数不会产生跳转`() {
        assertEquals(
            MainActivityLaunchRequests(),
            MainActivityLaunchRequests().accept(
                Intent(MainActivity.ACTION_OPEN_CONVERSATION_ID)
                    .putExtra(MainActivity.EXTRA_CONVERSATION_ID, " "),
            ),
        )
        assertEquals(
            MainActivityLaunchRequests(),
            MainActivityLaunchRequests().accept(
                Intent(MainActivity.ACTION_OPEN_ARCHIVED_CONVERSATION)
                    .putExtra(MainActivity.EXTRA_HANDOFF_SOURCE, "source"),
            ),
        )
    }

    @Test
    fun `单文件与多文件分享提取去重URI与附带文本且消费后重建不重放`() {
        val fileUri = Uri.parse("content://provider/files/report.pdf")
        val imageUri = Uri.parse("content://provider/images/photo.png")
        val singleShare = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, fileUri)
            putExtra(Intent.EXTRA_TEXT, "帮我总结这份文档")
        }
        val acceptedSingle = MainActivityLaunchRequests.restore(null, singleShare)
        assertEquals(
            IncomingShareRequest(
                uris = listOf(fileUri.toString()),
                mimeType = "application/pdf",
                text = "帮我总结这份文档",
            ),
            acceptedSingle.sharedContent,
        )

        val consumed = acceptedSingle.copy(sharedContent = null)
        assertEquals(
            MainActivityLaunchRequests(),
            MainActivityLaunchRequests.restore(savedState(consumed), singleShare),
        )

        val multiShare = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(fileUri, imageUri))
            clipData = ClipData.newRawUri("shared", fileUri).apply {
                addItem(ClipData.Item(imageUri))
            }
            putExtra(Intent.EXTRA_TEXT, fileUri.toString())
        }
        val acceptedMulti = MainActivityLaunchRequests().accept(multiShare)
        assertEquals(
            IncomingShareRequest(
                uris = listOf(fileUri.toString(), imageUri.toString()),
                mimeType = "*/*",
                text = null,
            ),
            acceptedMulti.sharedContent,
        )
    }

    @Test
    fun attachmentSharePreservesClipTextAndRestoresItWithoutReplay() {
        val uri = Uri.parse("content://provider/images/123")
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("photo", uri).apply {
                addItem(ClipData.Item("  帮我识别这张图片  "))
                addItem(ClipData.Item(uri.toString()))
                addItem(ClipData.Item("帮我识别这张图片"))
                addItem(ClipData.Item("补充说明"))
            }
        }
        val pending = MainActivityLaunchRequests().accept(share)
        assertEquals(listOf(uri.toString()), pending.sharedContent?.uris)
        assertEquals("帮我识别这张图片\n补充说明", pending.sharedContent?.text)
        assertEquals(pending, MainActivityLaunchRequests.restore(savedState(pending), share))
        val consumed = pending.copy(sharedContent = null)
        assertEquals(consumed, MainActivityLaunchRequests.restore(savedState(consumed), share))
    }

    @Test
    fun redundantStreamTextDoesNotHideClipCaption() {
        val uri = Uri.parse("content://provider/images/123")
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, uri.toString())
            clipData = ClipData.newRawUri("photo", uri).apply {
                addItem(ClipData.Item("图片说明"))
            }
        }
        assertEquals("图片说明", MainActivityLaunchRequests().accept(share).sharedContent?.text)
    }

    @Test
    fun explicitShareTextTakesPrecedenceOverClipText() {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "用户提供的正文")
            clipData = ClipData.newPlainText("text", "剪贴板副本")
        }
        assertEquals("用户提供的正文", MainActivityLaunchRequests().accept(share).sharedContent?.text)
    }

    @Test
    fun multipleAttachmentShareFallsBackToClipTextWhenExtraIsBlank() {
        val first = Uri.parse("content://provider/images/123")
        val second = Uri.parse("content://provider/images/456")
        val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))
            putExtra(Intent.EXTRA_TEXT, " ")
            clipData = ClipData.newRawUri("photos", first).apply {
                addItem(ClipData.Item(second))
                addItem(ClipData.Item("比较这两张图片"))
            }
        }
        assertEquals(
            IncomingShareRequest(listOf(first.toString(), second.toString()), "image/*", "比较这两张图片"),
            MainActivityLaunchRequests().accept(share).sharedContent,
        )
    }

    private fun savedState(requests: MainActivityLaunchRequests): Bundle {
        val state = Bundle().also(requests::saveTo)
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(state)
            parcel.setDataPosition(0)
            requireNotNull(parcel.readBundle(javaClass.classLoader))
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun shareRejectsFileAndOwnProviderUris() {
        val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                arrayListOf(
                    Uri.parse("file:///data/data/io.github.mangi.eta/databases/eta.db"),
                    Uri.parse("content://io.github.mangi.eta.accessibility.health/state"),
                    Uri.parse("https://example.com/a.png"),
                    Uri.parse("content://provider/files/report.pdf"),
                ),
            )
        }
        assertEquals(
            listOf("content://provider/files/report.pdf"),
            MainActivityLaunchRequests().accept(share).sharedContent?.uris,
        )
        val onlyUnsafe = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///data/data/io.github.mangi.eta/shared_prefs/x.xml"))
        }
        assertEquals(null, MainActivityLaunchRequests().accept(onlyUnsafe).sharedContent)
        assertEquals(false, isAcceptableSharedUri("content://io.github.mangi.eta/x"))
        assertEquals(true, isAcceptableSharedUri("content://com.android.providers.media.documents/document/image%3A1"))
    }
}
