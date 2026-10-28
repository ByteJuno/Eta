// Adapted from lejw0925/android-virtual-screen @ 1b7e2a6 (PolyForm Noncommercial 1.0.0).
package io.github.mangi.eta.agent.display

import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VirtualScreenMotionEventsTest {
    @Test
    fun multiPointerSequenceRejectsMissingDownAndStaleTimes() {
        val state = VirtualScreenMotionState()
        fun accepts(action: Int, time: Long, vararg ids: Int): Boolean {
            val event = event(action, time, *ids)
            return try {
                state.accept(event)
            } finally {
                event.recycle()
            }
        }
        assertFalse(accepts(MotionEvent.ACTION_MOVE, 100, 0))
        assertTrue(accepts(MotionEvent.ACTION_DOWN, 100, 0))
        assertTrue(accepts(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 110, 0, 1))
        assertFalse(accepts(MotionEvent.ACTION_MOVE, 109, 0, 1))
        assertFalse(accepts(MotionEvent.ACTION_MOVE, 120, 0))
        assertTrue(accepts(MotionEvent.ACTION_MOVE, 120, 0, 1))
        assertTrue(accepts(MotionEvent.ACTION_POINTER_UP or (1 shl 8), 130, 0, 1))
        assertTrue(accepts(MotionEvent.ACTION_UP, 140, 0))
        assertFalse(state.active)
        assertFalse(accepts(MotionEvent.ACTION_UP, 150, 0))
    }

    @Test
    fun clockwiseTransformKeepsEveryPointerAndClampsExcursions() {
        val source = event(MotionEvent.ACTION_MOVE, 120, 0, 1)
        val viewport = requireNotNull(VirtualScreenViewport.fit(400, 800, 1600, 800))
        val mapped = source.toVirtualDisplay(viewport)
        try {
            assertEquals(2, mapped.pointerCount)
            for (index in 0..1) {
                assertEquals(source.getPointerId(index), mapped.getPointerId(index))
                assertEquals(source.getY(index) / viewport.scale, mapped.getX(index), 0.01f)
                assertEquals(800f - source.getX(index) / viewport.scale, mapped.getY(index), 0.01f)
            }
            assertEquals(InputDevice.SOURCE_TOUCHSCREEN, mapped.source)
        } finally {
            mapped.recycle()
            source.recycle()
        }
    }

    private fun event(action: Int, time: Long, vararg ids: Int): MotionEvent =
        MotionEvent.obtain(
            100,
            time,
            action,
            ids.size,
            Array(ids.size) { index ->
                MotionEvent.PointerProperties().apply {
                    id = ids[index]
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            },
            Array(ids.size) { index ->
                MotionEvent.PointerCoords().apply {
                    x = 50f + index * 40
                    y = 100f + index * 40
                    pressure = 1f
                    size = 1f
                }
            },
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
}
