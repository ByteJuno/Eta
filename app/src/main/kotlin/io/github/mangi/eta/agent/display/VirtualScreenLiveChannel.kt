// Adapted from lejw0925/android-virtual-screen @ 1b7e2a6 (PolyForm Noncommercial 1.0.0).
package io.github.mangi.eta.agent.display

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.view.MotionEvent
import android.view.SurfaceControl
import org.json.JSONObject

internal data class VirtualScreenLiveGeometry(val width: Int, val height: Int, val rotation: Int)

/** Capability for one app-owned virtual display. Callers cannot select a display ID. */
internal class VirtualScreenLiveChannel(val sessionId: String, private val remote: IBinder) {
    val isAlive: Boolean
        get() = remote.isBinderAlive

    fun attach(viewer: String, parent: SurfaceControl, width: Int, height: Int): Bundle =
        invoke(
            ATTACH,
            JSONObject().put("viewer", viewer).put("width", width).put("height", height),
            { parent.writeToParcel(it, 0) },
        ) {
            requireNotNull(it.readBundle(javaClass.classLoader))
        }

    fun detach(viewer: String) = invoke(DETACH, JSONObject().put("viewer", viewer)) { Unit }

    fun motion(
        viewer: String,
        geometry: VirtualScreenLiveGeometry,
        gesture: Long,
        control: Long,
        event: MotionEvent,
    ) =
        invoke(
            MOTION,
            JSONObject()
                .put("viewer", viewer)
                .put("width", geometry.width)
                .put("height", geometry.height)
                .put("rotation", geometry.rotation)
                .put("gesture", gesture)
                .put("controlGeneration", control),
            { event.writeToParcel(it, 0) },
        ) {
            Unit
        }

    fun settle(viewer: String, gesture: Long, control: Long) =
        invoke(
            SETTLE,
            JSONObject()
                .put("viewer", viewer)
                .put("gesture", gesture)
                .put("controlGeneration", control),
        ) {
            Unit
        }

    fun cancel(viewer: String, gesture: Long, control: Long) =
        invoke(
            CANCEL,
            JSONObject()
                .put("viewer", viewer)
                .put("gesture", gesture)
                .put("controlGeneration", control),
        ) {
            Unit
        }

    fun setManualControl(generation: Long, enabled: Boolean) =
        invoke(CONTROL, JSONObject().put("controlGeneration", generation).put("enabled", enabled)) {
            Unit
        }

    private fun <T> invoke(
        code: Int,
        request: JSONObject,
        extra: (Parcel) -> Unit = {},
        read: (Parcel) -> T,
    ): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(request.put("sessionId", sessionId).toString())
            extra(data)
            check(remote.transact(code, data, reply, 0)) { "DISPLAY_BRIDGE_UNAVAILABLE" }
            reply.readException()
            return read(reply)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    companion object {
        const val DESCRIPTOR = "io.github.mangi.eta.virtual-display"
        const val ATTACH = 4
        const val DETACH = 5
        const val MOTION = 6
        const val CANCEL = 7
        const val SETTLE = 8
        const val CONTROL = 9
    }
}
