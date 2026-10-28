// Adapted from lejw0925/android-virtual-screen @ 1b7e2a6 (PolyForm Noncommercial 1.0.0).
package io.github.mangi.eta.agent.display

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import io.github.mangi.eta.agent.overlay.GestureIndicatorHost
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.hypot
import org.json.JSONObject

/** SurfaceFlinger renders the app itself. JPEG is only a compatibility renderer. */
internal class VirtualScreenSurfaceView(context: Context) :
    FrameLayout(context), SurfaceHolder.Callback {
    private val viewer = UUID.randomUUID().toString()
    private val live =
        SurfaceView(context).apply {
            holder.addCallback(this@VirtualScreenSurfaceView)
            if (Build.VERSION.SDK_INT >= 35) setRequestedFrameRate(120f)
        }
    private var bitmap: Bitmap? = null
    private val fallback =
        object : View(context) {
            private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(Color.BLACK)
                val frame = bitmap ?: return
                val viewport =
                    VirtualScreenViewport.fit(width, height, frame.width, frame.height) ?: return
                canvas.save()
                canvas.translate(viewport.left, viewport.top)
                canvas.scale(viewport.scale, viewport.scale)
                if (viewport.rotatesClockwise) {
                    canvas.translate(frame.height.toFloat(), 0f)
                    canvas.rotate(90f)
                }
                canvas.drawBitmap(frame, 0f, 0f, paint)
                canvas.restore()
            }
        }
    private val worker =
        ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) { r ->
            Thread(r, "display-viewer").apply { isDaemon = true }
        }
    private val pending = Semaphore(64)
    private val epoch = AtomicLong()
    private var target: VirtualScreenLiveChannel? = null
    private var attached: VirtualScreenLiveChannel? = null
    private var sessionId: String? = null
    private var geometry = VirtualScreenLiveGeometry(720, 1600, 0)
    private var ready = false
    private var resumed = false
    private var disposed = false
    private var inputReadyAt = Long.MAX_VALUE
    var isLive = false
        private set

    var onLiveChanged: (Boolean) -> Unit = {}
    var onManualDown: (String) -> String? = { null }
    var onManualComplete: (String, String) -> Unit = { _, _ -> }
    private var phoneToken: String? = null
    var onInput: (String, JSONObject) -> Unit = { _, _ -> }
    var touchEnabled = true
        set(value) {
            if (!value) cancelGesture()
            field = value
        }

    private var displayInfo: VirtualDisplayInfo? = null
    private val indicators = GestureIndicatorHost(context)
    private var lastGesture = 0L
    var onError: (String) -> Unit = {}
    private var gesture = 0L
    private var active = false
    private var gestureGeometry = geometry
    private var gestureChannel: VirtualScreenLiveChannel? = null
    private val points = mutableListOf<Pair<Int, Int>>()
    private var downAt = 0L
    private var attachAttempt = 0
    private val retryAttach = Runnable { attach() }

    init {
        setBackgroundColor(Color.BLACK)
        addView(live, LayoutParams(-1, -1))
        addView(fallback, LayoutParams(-1, -1))
        addView(indicators, LayoutParams(-1, -1))
    }

    private fun setTarget(
        id: String?,
        channel: VirtualScreenLiveChannel?,
        nextGeometry: VirtualScreenLiveGeometry,
    ) {
        if (sessionId != id || target !== channel || geometry != nextGeometry) {
            cancelGesture()
            sessionId = id
            target = channel
            geometry = nextGeometry
            inputReadyAt = Long.MAX_VALUE
            attach()
        }
        fallback.invalidate()
        if (bitmap != null && !isLive && resumed) inputReadyAt = SystemClock.uptimeMillis()
    }

    fun setLiveTarget(display: VirtualDisplayInfo?, channel: VirtualScreenLiveChannel?) {
        if (sessionId != display?.sessionId) {
            bitmap?.recycle()
            bitmap = null
        }
        displayInfo = display
        setTarget(
            display?.sessionId,
            channel,
            display?.let { VirtualScreenLiveGeometry(it.width, it.height, it.rotation) } ?: geometry,
        )
    }

    fun setFrame(next: Bitmap, display: VirtualDisplayInfo) {
        next.density = Bitmap.DENSITY_NONE
        bitmap?.takeIf { it !== next }?.recycle()
        bitmap = next
        displayInfo = display
        setTarget(
            display.sessionId,
            target?.takeIf { it.sessionId == display.sessionId },
            VirtualScreenLiveGeometry(display.width, display.height, display.rotation),
        )
        fallback.invalidate()
    }

    fun clearFrame() {
        cancelGesture()
        target = null
        sessionId = null
        displayInfo = null
        detach()
        bitmap?.recycle()
        bitmap = null
        fallback.invalidate()
        indicators.clear()
    }

    fun showGesture(gesture: VirtualScreenGesture?) {
        val display = displayInfo ?: return
        if (gesture == null || gesture.id == lastGesture || gesture.sessionId != display.sessionId)
            return
        lastGesture = gesture.id
        if (SystemClock.elapsedRealtime() - gesture.startedAt > gesture.durationMs + 700) return
        val viewport =
            VirtualScreenViewport.fit(width, height, display.width, display.height) ?: return
        val start = viewport.toView(gesture.x, gesture.y)
        if (gesture.action == "swipe") {
            val end = viewport.toView(gesture.endX, gesture.endY)
            indicators.showSwipe(start.x, start.y, end.x, end.y, gesture.durationMs)
        } else
            indicators.showPress(
                start.x,
                start.y,
                gesture.action == "long_press",
                gesture.durationMs,
            )
    }

    fun setResumed(value: Boolean) {
        if (value != resumed) {
            resumed = value
            if (value) attach()
            else {
                cancelGesture()
                detach()
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        ready = true
        attach()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        attach()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        ready = false
        cancelGesture()
        detach()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cancelGesture()
        indicators.clear()
        attach()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!disposed) attach()
    }

    private fun attach() {
        val channel = target
        val parentSurface = if (ready) live.surfaceControl else null
        if (
            disposed ||
                !resumed ||
                channel == null ||
                parentSurface?.isValid != true ||
                width <= 0 ||
                height <= 0
        ) {
            detach()
            return
        }
        val generation = epoch.incrementAndGet()
        val w = width
        val h = height
        submit {
            if (epoch.get() != generation) return@submit
            try {
                attached?.takeIf { it !== channel }?.let { runCatching { it.detach(viewer) } }
                val info = channel.attach(viewer, parentSurface, w, h)
                attached = channel
                post {
                    if (!disposed && resumed && epoch.get() == generation && target === channel) {
                        attachAttempt = 0
                        geometry =
                            VirtualScreenLiveGeometry(
                                info.getInt("width"),
                                info.getInt("height"),
                                info.getInt("rotation"),
                            )
                        setLive(info.getBoolean("attached"))
                    }
                }
            } catch (e: Exception) {
                post {
                    if (
                        epoch.get() == generation &&
                            !disposed &&
                            resumed &&
                            ready &&
                            target === channel
                    ) {
                        setLive(false)
                        removeCallbacks(retryAttach)
                        if (attachAttempt < 6) {
                            val delay = (50L shl attachAttempt.coerceAtMost(4)).coerceAtMost(800L)
                            attachAttempt++
                            postDelayed(retryAttach, delay)
                        }
                    }
                }
            }
        }
    }

    private fun detach() {
        epoch.incrementAndGet()
        removeCallbacks(retryAttach)
        attachAttempt = 0
        setLive(false)
        if (!disposed)
            submitControl {
                attached?.let { runCatching { it.detach(viewer) } }
                attached = null
            }
    }

    private fun setLive(value: Boolean) {
        if (isLive == value) return
        cancelGesture()
        isLive = value
        fallback.visibility = if (value) GONE else VISIBLE
        inputReadyAt =
            if (resumed && (value || bitmap != null)) SystemClock.uptimeMillis() else Long.MAX_VALUE
        onLiveChanged(value)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(event: MotionEvent) = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!touchEnabled || !resumed || sessionId == null) return false
        if (!isLive && event.pointerCount != 1) {
            cancelGesture()
            return true
        }
        val viewport =
            VirtualScreenViewport.fit(width, height, geometry.width, geometry.height)
                ?: return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelGesture()
            if (event.downTime < inputReadyAt || viewport.toDisplay(event.x, event.y) == null)
                return true
            phoneToken = onManualDown(requireNotNull(sessionId)) ?: return true
            active = true
            gesture = gestures.incrementAndGet()
            gestureGeometry = geometry
            gestureChannel = target
            downAt = event.downTime
            points.clear()
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (!active) return true
        if (isLive) {
            val channel = gestureChannel ?: return true
            val mapped = event.toVirtualDisplay(viewport)
            val id = gesture
            val g = gestureGeometry
            val generation = epoch.get()
            val finished =
                mapped.actionMasked in setOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)
            val token = phoneToken
            val inputSession = requireNotNull(sessionId)
            if (
                !submit {
                    try {
                        if (epoch.get() == generation) {
                            channel.motion(viewer, g, id, requireNotNull(token).toLong(), mapped)
                            if (finished) channel.settle(viewer, id, requireNotNull(token).toLong())
                        }
                    } catch (e: Exception) {
                        runCatching { channel.cancel(viewer, id, requireNotNull(token).toLong()) }
                        post {
                            // A delayed failure from the preceding gesture must not cancel the next
                            // tap.
                            if (gesture == id && active) cancelGesture()
                            if (!channel.isAlive) {
                                target = null
                                setLive(false)
                            }
                            onError(
                                e.message?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,79}")) }
                                    ?: "INPUT_DISPATCH_UNCONFIRMED"
                            )
                        }
                    } finally {
                        mapped.recycle()
                        if (finished) token?.let { post { onManualComplete(inputSession, it) } }
                    }
                }
            ) {
                mapped.recycle()
                epoch.incrementAndGet()
                cancelGesture()
            }
        } else {
            val p = viewport.toDisplay(event.x, event.y, true) ?: return true
            if (points.size < 64 && (points.lastOrNull() != p.x to p.y)) points += p.x to p.y
            else if (event.actionMasked == MotionEvent.ACTION_UP && points.isNotEmpty())
                points[points.lastIndex] = p.x to p.y
            if (event.actionMasked == MotionEvent.ACTION_CANCEL)
                phoneToken?.let { onManualComplete(requireNotNull(sessionId), it) }
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val duration = (event.eventTime - downAt).toInt()
                val first = points.firstOrNull()
                val last = points.lastOrNull()
                if (first != null && last != null) {
                    val moved =
                        points.any {
                            hypot(
                                (it.first - first.first).toDouble(),
                                (it.second - first.second).toDouble(),
                            ) > 12
                        }
                    val args =
                        if (moved)
                            JSONObject()
                                .put("action", "swipe")
                                .put("x1", first.first)
                                .put("y1", first.second)
                                .put("x2", last.first)
                                .put("y2", last.second)
                                .put("durationMs", duration.coerceIn(100, 2000))
                        else
                            JSONObject()
                                .put("action", if (duration >= 500) "long_press" else "tap")
                                .put("x", first.first)
                                .put("y", first.second)
                                .put("durationMs", duration.coerceIn(300, 2000))
                    args
                        .put("expectedWidth", gestureGeometry.width)
                        .put("expectedHeight", gestureGeometry.height)
                        .put("expectedRotation", gestureGeometry.rotation)
                    args.put("controlGeneration", phoneToken?.toLongOrNull())
                    sessionId?.let { onInput(it, args) }
                }
            }
        }
        if (event.actionMasked in setOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
            active = false
            gestureChannel = null
            phoneToken = null
            parent?.requestDisallowInterceptTouchEvent(false)
            if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        }
        return true
    }

    fun cancelGesture() {
        val channel = gestureChannel
        val token = phoneToken
        val id = gesture
        val inputSession = sessionId
        gestureChannel = null
        phoneToken = null
        active = false
        points.clear()
        if (channel != null && !disposed) {
            submitControl {
                try {
                    runCatching {
                        channel.cancel(viewer, id, requireNotNull(token).toLong())
                        channel.settle(viewer, id, requireNotNull(token).toLong())
                    }
                } finally {
                    if (inputSession != null)
                        token?.let { post { onManualComplete(inputSession, it) } }
                }
            }
        } else if (inputSession != null) token?.let { onManualComplete(inputSession, it) }
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    // Reserve room for cancellation even when motion producers exceed the bounded budget.
    private fun submit(block: () -> Unit): Boolean {
        if (!pending.tryAcquire()) {
            post { onError("INPUT_STREAM_BACKPRESSURE") }
            return false
        }
        return try {
            worker.execute {
                try {
                    block()
                } finally {
                    pending.release()
                }
            }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            pending.release()
            false
        }
    }

    private fun submitControl(block: () -> Unit) {
        runCatching { worker.execute(block) }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun dispose() {
        if (disposed) return
        cancelGesture()
        detach()
        disposed = true
        bitmap?.recycle()
        bitmap = null
        worker.shutdown()
    }

    override fun onDetachedFromWindow() {
        // Android detaches AndroidView instances during task/PiP transitions. Keep the
        // worker and target alive so the same view can attach again on the next frame.
        cancelGesture()
        detach()
        super.onDetachedFromWindow()
    }

    companion object {
        private val gestures = AtomicLong()
    }
}
