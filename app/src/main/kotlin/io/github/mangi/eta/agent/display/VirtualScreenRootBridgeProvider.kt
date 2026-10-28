// Adapted from lejw0925/android-virtual-screen @ 1b7e2a6 (PolyForm Noncommercial 1.0.0).
package io.github.mangi.eta.agent.display

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** Root-only bootstrap; a pending nonce and session must match before a Binder is accepted. */
class VirtualScreenRootBridgeProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == 0) { "ROOT_REQUIRED" }
        require(method == "register" && extras != null) { "INVALID_BRIDGE_REQUEST" }
        val nonce = extras.getString("nonce") ?: error("INVALID_BRIDGE_REQUEST")
        val pending = waiting[nonce] ?: error("STALE_VIRTUAL_SCREEN")
        check(extras.getString("sessionId") == pending.sessionId) { "DISPLAY_OWNER_MISMATCH" }
        val binder = requireNotNull(extras.getBinder("display"))
        check(waiting.remove(nonce, pending)) { "STALE_VIRTUAL_SCREEN" }
        pending.binder.complete(binder)
        return Bundle()
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()

    internal class Pending(
        val sessionId: String,
        val binder: CompletableFuture<IBinder> = CompletableFuture(),
    )

    companion object {
        private val waiting = ConcurrentHashMap<String, Pending>()

        internal fun expect(nonce: String, sessionId: String): Pending =
            Pending(sessionId).also { waiting[nonce] = it }

        internal fun forget(nonce: String) {
            waiting.remove(nonce)?.binder?.cancel(false)
        }
    }
}
