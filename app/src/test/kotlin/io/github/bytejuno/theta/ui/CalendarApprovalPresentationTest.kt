package io.github.bytejuno.theta.ui

import io.github.bytejuno.theta.ui.components.formatCalendarApprovalJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CalendarApprovalPresentationTest {
    @Test fun batchPreviewKeepsEveryTargetAndField() {
        val raw = """{"events":[{"calendar_id":1,"title":"First","start_time":"2026-11-06T09:00:00+08:00","end_time":"2026-11-06T10:00:00+08:00","timezone":"Asia/Shanghai","reminder_minutes":[10]},{"calendar_id":2,"title":"Second","description":"Keep every field"}]}"""
        val rendered = formatCalendarApprovalJson(raw)
        val events = org.json.JSONObject(rendered).getJSONArray("events")
        assertEquals(2, events.length())
        assertEquals("Asia/Shanghai", events.getJSONObject(0).getString("timezone"))
        listOf("First", "Second", "reminder_minutes", "Keep every field", "end_time")
            .forEach { assertTrue("Missing field: $it", rendered.contains(it)) }
    }

    @Test fun malformedPreviewRemainsVisibleRatherThanBecomingEmptyApproval() {
        assertEquals("unparsed target", formatCalendarApprovalJson("unparsed target"))
    }
}
