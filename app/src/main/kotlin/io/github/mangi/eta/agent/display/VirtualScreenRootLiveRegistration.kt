// Adapted from lejw0925/android-virtual-screen @ 1b7e2a6 (PolyForm Noncommercial 1.0.0).
package io.github.mangi.eta.agent.display

import android.content.AttributionSource
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import io.github.mangi.eta.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Only the initiating application UID can use the Root display's capability. */
internal class VirtualScreenRootLiveRegistration(
    private val mirror: VirtualScreenDisplayMirror,
    setup: JSONObject,
) : AutoCloseable {
    private val uid = setup.getInt("ownerUid")
    private val sessionId = setup.getString("sessionId")
    private val closed = AtomicBoolean()
    private val authority = "${BuildConfig.APPLICATION_ID}.display-bridge"
    private val manager =
        Class.forName("android.app.ActivityManager").getDeclaredMethod("getService").invoke(null)
    private val type = Class.forName("android.app.IActivityManager")
    private val binder =
        object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) {
                    reply?.writeString(VirtualScreenLiveChannel.DESCRIPTOR)
                    return true
                }
                if (code !in VirtualScreenLiveChannel.ATTACH..VirtualScreenLiveChannel.CONTROL)
                    return super.onTransact(code, data, reply, flags)
                data.enforceInterface(VirtualScreenLiveChannel.DESCRIPTOR)
                check(getCallingUid() == uid && !closed.get()) { "DISPLAY_OWNER_MISMATCH" }
                val identity = clearCallingIdentity()
                try {
                    val raw = data.readString() ?: error("INVALID_BRIDGE_REQUEST")
                    require(raw.length <= 4096) { "INVALID_BRIDGE_REQUEST" }
                    val request = JSONObject(raw)
                    check(request.optString("sessionId") == sessionId) { "STALE_VIRTUAL_SCREEN" }
                    mirror.transact(code, request, data, reply)
                } catch (failure: Exception) {
                    reply?.writeException(
                        IllegalStateException(failure.message ?: "DISPLAY_BRIDGE_UNAVAILABLE")
                    )
                } finally {
                    restoreCallingIdentity(identity)
                }
                return true
            }
        }

    init {
        check(uid > 0 && android.os.Process.myUid() == 0) { "ROOT_REQUIRED" }
        val holder =
            requireNotNull(
                type
                    .getMethod(
                        "getContentProviderExternal",
                        String::class.java,
                        Int::class.javaPrimitiveType,
                        IBinder::class.java,
                        String::class.java,
                    )
                    .invoke(manager, authority, uid / 100000, binder, "Eta:virtual-mirror")
            )
        try {
            val provider = holder.javaClass.getField("provider").get(holder)
            Class.forName("android.content.IContentProvider")
                .getMethod(
                    "call",
                    AttributionSource::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    Bundle::class.java,
                )
                .invoke(
                    provider,
                    AttributionSource.Builder(0).build(),
                    authority,
                    "register",
                    null,
                    Bundle().apply {
                        putString("nonce", setup.getString("bridgeNonce"))
                        putString("sessionId", sessionId)
                        putBinder("display", binder)
                    },
                )
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching {
            type
                .getMethod("removeContentProviderExternal", String::class.java, IBinder::class.java)
                .invoke(manager, authority, binder)
        }
    }
}
