package io.github.bytejuno.theta.ui.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.bytejuno.theta.agent.runtime.AgentRuntimeWire
import io.github.bytejuno.theta.data.db.EtaDatabase
import io.github.bytejuno.theta.ui.model.AgentChatHomeUiState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentImageAttachmentOwnershipTest {
    private lateinit var context: Context
    private lateinit var source: File
    private lateinit var provider: DelayedImageProvider
    private lateinit var uri: Uri
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(RuntimeEnvironment.getApplication()).declareActionUnbindable(AgentRuntimeWire.ACTION_BIND)
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        source = File(context.cacheDir, "attachment-owner-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        val authority = "eta.test.attachment.${System.nanoTime()}"
        provider = DelayedImageProvider(source)
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        uri = Uri.parse("content://$authority/image")
        val empty = AgentChatHomeUiState(
            messages = emptyList(), history = emptyList(), journal = emptyList(),
            input = "", isStreaming = false, thinkingEnabled = false,
        )
        runBlocking {
            AgentConversationStore.save(
                context, "first", linkedMapOf("first" to empty, "second" to empty),
                mapOf("first" to "first", "second" to "second"),
                mapOf("first" to 1L, "second" to 1L),
            )
        }
    }

    @After
    fun tearDown() {
        provider.release.countDown()
        scope.cancel()
        awaitCondition { job.isCompleted }
        source.delete()
        EtaDatabase.closeForTests()
    }

    @Test
    fun switchingConversationRejectsTheInFlightImage() = checkImport(expectedCount = 0) { state ->
        state.selectConversation("second")
        awaitCondition { state.homeState.isConversationLoaded && !state.isLoadingConversation }
        assertEquals("second", state.conversationPaneState.selectedConversationId)
    }

    @Test
    fun creatingConversationRejectsTheInFlightImage() = checkImport(expectedCount = 0) { state ->
        state.createConversation()
    }

    @Test
    fun switchingAwayAndBackStillRejectsTheInFlightImage() = checkImport(expectedCount = 0) { state ->
        state.selectConversation("second")
        state.selectConversation("first")
        awaitCondition { state.homeState.isConversationLoaded && !state.isLoadingConversation }
        assertEquals("first", state.conversationPaneState.selectedConversationId)
    }

    @Test
    fun unchangedConversationAcceptsTheImage() = checkImport(expectedCount = 1) { }

    private fun checkImport(expectedCount: Int, whileReading: (AgentAppState) -> Unit) {
        val state = AgentAppState(context, scope)
        val previousJobs = job.children.toSet()
        state.attachImage(uri.toString())
        val importJob = job.children.single { it !in previousJobs }
        try {
            assertTrue("图片读取未开始", provider.started.await(10, TimeUnit.SECONDS))
            whileReading(state)
        } finally {
            provider.release.countDown()
        }
        awaitCondition { importJob.isCompleted }
        assertTrue("图片导入异常取消", !importJob.isCancelled)
        assertEquals(expectedCount, state.homeState.pendingImages.size)
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS)
            Thread.sleep(1)
        }
        assertTrue("异步操作未在限定时间内完成", condition())
    }

    private class DelayedImageProvider(private val source: File) : ContentProvider() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun onCreate(): Boolean = true
        override fun getType(uri: Uri): String = "image/png"
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            started.countDown()
            check(release.await(20, TimeUnit.SECONDS)) { "图片读取等待超时" }
            return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(
            uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
        ): Int = 0
    }
}
