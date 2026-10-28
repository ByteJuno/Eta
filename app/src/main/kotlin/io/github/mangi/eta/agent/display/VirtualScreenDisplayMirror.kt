// Adapted from lejw0925/android-virtual-screen @ 1b7e2a6 (PolyForm Noncommercial 1.0.0).
package io.github.mangi.eta.agent.display

import android.graphics.Point
import android.graphics.Rect
import android.os.Bundle
import android.os.Parcel
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

internal class VirtualScreenDisplayMirror(
    private val display: Display,
    injector: VirtualScreenInputInjector,
    allowed: () -> Boolean,
    onActivity: () -> Unit,
) : AutoCloseable {
    private var layer: SurfaceControl? = null
    private var parent: SurfaceControl? = null
    private var viewer: String? = null
    private val inputErrors = ConcurrentHashMap<Long, String>()
    private val inputDispatches = ConcurrentHashMap<Long, String>()
    private val motion =
        VirtualScreenRootMotionStream(
            injector,
            allowed,
            onActivity,
            { id, error -> inputErrors[id] = error },
            { id, action -> inputDispatches[id] = action },
        )
    private var controlGeneration = 0L
    val isManualControl: Boolean
        get() = motion.isManualControl

    @Suppress("DEPRECATION")
    private fun geometry() =
        Point()
            .also { display.getRealSize(it) }
            .let { VirtualScreenLiveGeometry(it.x, it.y, display.rotation) }

    @Synchronized
    fun setManualControl(generation: Long, enabled: Boolean) {
        require(generation > 0) { "INVALID_MANUAL_CONTROL" }
        check(generation >= controlGeneration) { "STALE_MANUAL_CONTROL" }
        if (enabled) motion.takeover() else motion.resume()
        controlGeneration = generation
    }

    private fun requireControl(request: JSONObject) {
        check(request.optLong("controlGeneration") == controlGeneration && isManualControl) {
            "UI_MANUAL_CONTROL_REQUIRED"
        }
        check(request.optString("viewer") == viewer) { "VIEWER_HIDDEN" }
    }

    @Synchronized
    fun transact(code: Int, request: JSONObject, data: Parcel, reply: Parcel?) {
        when (code) {
            VirtualScreenLiveChannel.ATTACH -> {
                val nextParent = SurfaceControl.CREATOR.createFromParcel(data)
                try {
                    data.enforceNoDataAvail()
                    val width = request.getInt("width")
                    val height = request.getInt("height")
                    require(width in 1..16384 && height in 1..16384 && nextParent.isValid) {
                        "INVALID_SURFACE"
                    }
                    closeMirror()
                    val service =
                        Class.forName("android.view.WindowManagerGlobal")
                            .getMethod("getWindowManagerService")
                            .invoke(null)
                    val mirror = SurfaceControl::class.java.getDeclaredConstructor().newInstance()
                    layer = mirror
                    parent = nextParent
                    viewer = request.getString("viewer")
                    check(
                        display.displayId > 0 &&
                            Class.forName("android.view.IWindowManager")
                                .getMethod(
                                    "mirrorDisplay",
                                    Int::class.javaPrimitiveType,
                                    SurfaceControl::class.java,
                                )
                                .invoke(service, display.displayId, mirror) == true &&
                            mirror.isValid
                    ) {
                        "DISPLAY_MIRROR_UNAVAILABLE"
                    }
                    val geometry = geometry()
                    val viewport =
                        requireNotNull(
                            VirtualScreenViewport.fit(
                                width,
                                height,
                                geometry.width,
                                geometry.height,
                            )
                        )
                    SurfaceControl.Transaction().use { transaction ->
                        transaction
                            .reparent(mirror, nextParent)
                            .setLayer(mirror, 1)
                            .setCrop(mirror, Rect(0, 0, geometry.width, geometry.height))
                            .setFrameRate(mirror, 120f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                            .setVisibility(mirror, true)
                        val scale = viewport.scale
                        val matrix =
                            if (viewport.rotatesClockwise) floatArrayOf(0f, scale, -scale, 0f)
                            else floatArrayOf(scale, 0f, 0f, scale)
                        SurfaceControl.Transaction::class
                            .java
                            .getMethod(
                                "setMatrix",
                                SurfaceControl::class.java,
                                Float::class.javaPrimitiveType,
                                Float::class.javaPrimitiveType,
                                Float::class.javaPrimitiveType,
                                Float::class.javaPrimitiveType,
                            )
                            .invoke(transaction, mirror, matrix[0], matrix[1], matrix[2], matrix[3])
                        transaction
                            .setPosition(
                                mirror,
                                viewport.left +
                                    if (viewport.rotatesClockwise) geometry.height * scale else 0f,
                                viewport.top,
                            )
                            .apply()
                    }
                    reply?.writeNoException()
                    reply?.writeBundle(
                        Bundle().apply {
                            putBoolean("attached", true)
                            putInt("width", geometry.width)
                            putInt("height", geometry.height)
                            putInt("rotation", geometry.rotation)
                        }
                    )
                } catch (failure: Exception) {
                    if (parent !== nextParent) nextParent.release()
                    closeMirror()
                    throw failure
                }
            }
            VirtualScreenLiveChannel.DETACH -> {
                data.enforceNoDataAvail()
                if (request.getString("viewer") == viewer) closeMirror()
                reply?.writeNoException()
            }
            VirtualScreenLiveChannel.MOTION -> {
                val event = MotionEvent.CREATOR.createFromParcel(data)
                try {
                    data.enforceNoDataAvail()
                    requireControl(request)
                    check(layer?.isValid == true) { "VIEWER_HIDDEN" }
                    val geometry = geometry()
                    check(
                        request.getInt("width") == geometry.width &&
                            request.getInt("height") == geometry.height &&
                            request.getInt("rotation") == geometry.rotation
                    ) {
                        "STALE_OBSERVATION"
                    }
                    val gesture = request.getLong("gesture")
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        inputErrors.keys.removeIf { it < gesture - 32 }
                        inputDispatches.keys.removeIf { it < gesture - 32 }
                    }
                    inputErrors[gesture]?.let { error(it) }
                    motion.enqueue(geometry, gesture, event)
                    reply?.writeNoException()
                } finally {
                    event.recycle()
                }
            }
            VirtualScreenLiveChannel.CANCEL -> {
                data.enforceNoDataAvail()
                if (
                    request.optString("viewer") == viewer &&
                        request.optLong("controlGeneration") == controlGeneration
                )
                    motion.cancel(request.getLong("gesture"))
                reply?.writeNoException()
            }
            VirtualScreenLiveChannel.SETTLE -> {
                data.enforceNoDataAvail()
                requireControl(request)
                val gesture = request.getLong("gesture")
                motion.awaitSettled(gesture)
                inputErrors.remove(gesture)?.let { error(it) }
                reply?.writeNoException()
                reply?.writeString(inputDispatches.remove(gesture))
            }
            VirtualScreenLiveChannel.CONTROL -> {
                data.enforceNoDataAvail()
                setManualControl(
                    request.getLong("controlGeneration"),
                    request.getBoolean("enabled"),
                )
                reply?.writeNoException()
            }
        }
    }

    private fun closeMirror() {
        motion.cancel()
        inputErrors.clear()
        inputDispatches.clear()
        layer?.let {
            runCatching {
                SurfaceControl.Transaction().use { transaction ->
                    transaction.setVisibility(it, false).reparent(it, null).apply()
                }
            }
            it.release()
        }
        parent?.release()
        layer = null
        parent = null
        viewer = null
    }

    @Synchronized
    override fun close() {
        closeMirror()
        motion.close()
    }
}
