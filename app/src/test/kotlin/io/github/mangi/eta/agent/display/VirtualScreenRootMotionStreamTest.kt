package io.github.mangi.eta.agent.display

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VirtualScreenRootMotionStreamTest {
    @Test
    fun returningControlCancelsTheActiveFingerBeforeUnblockingAgentInput() {
        val events = CopyOnWriteArrayList<Int>()
        val down = CountDownLatch(1)
        val injector =
            VirtualScreenInputInjector(
                3,
                dispatch = { event, wait ->
                    assertFalse(wait)
                    events += (event as MotionEvent).actionMasked
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) down.countDown()
                    true
                },
            )
        val stream =
            VirtualScreenRootMotionStream(injector, { true }, {}, { _, error -> fail(error) })
        try {
            stream.takeover()
            send(stream, 1, MotionEvent.ACTION_DOWN)
            assertTrue(down.await(2, TimeUnit.SECONDS))
            stream.resume()
            assertFalse(stream.isManualControl)
            assertEquals(
                listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL),
                events.toList(),
            )
        } finally {
            stream.close()
            injector.close()
        }
    }

    @Test
    fun cancellationOfOldGestureCannotCancelNextGesture() {
        val events = CopyOnWriteArrayList<Int>()
        val injector =
            VirtualScreenInputInjector(
                3,
                dispatch = { event, _ ->
                    events += (event as MotionEvent).actionMasked
                    true
                },
            )
        val stream =
            VirtualScreenRootMotionStream(injector, { true }, {}, { _, error -> fail(error) })
        try {
            stream.takeover()
            send(stream, 1, MotionEvent.ACTION_DOWN)
            send(stream, 1, MotionEvent.ACTION_UP)
            stream.awaitSettled(1)
            send(stream, 2, MotionEvent.ACTION_DOWN)
            stream.cancel(1)
            send(stream, 2, MotionEvent.ACTION_UP)
            stream.awaitSettled(2)
            assertEquals(
                listOf(
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_UP,
                ),
                events.toList(),
            )
        } finally {
            stream.close()
            injector.close()
        }
    }

    private fun send(stream: VirtualScreenRootMotionStream, id: Long, action: Int) {
        val event = MotionEvent.obtain(0, SystemClock.uptimeMillis(), action, 10f, 20f, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            stream.enqueue(VirtualScreenLiveGeometry(100, 200, 0), id, event)
        } finally {
            event.recycle()
        }
    }
}
