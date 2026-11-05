package io.github.bytejuno.theta.agent.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartedActivityTrackerTest {
    @Test fun visibleOnlyWhileMainActivityIsStarted() {
        val tracker = StartedActivityTracker()
        val activity = Any()
        assertFalse(tracker.visible)
        tracker.started(activity)
        assertTrue(tracker.visible)
        tracker.stopped(activity)
        assertFalse(tracker.visible)
    }
    @Test fun oldInstanceCannotHideNewInstance() {
        val tracker = StartedActivityTracker()
        val old = Any()
        val next = Any()
        tracker.started(old)
        tracker.started(next)
        tracker.stopped(old)
        assertTrue(tracker.visible)
        tracker.stopped(next)
        assertFalse(tracker.visible)
    }
    @Test fun duplicateCallbacksAreIdempotent() {
        val tracker = StartedActivityTracker()
        val activity = Any()
        tracker.started(activity)
        tracker.started(activity)
        tracker.stopped(activity)
        tracker.stopped(activity)
        assertFalse(tracker.visible)
    }
}
