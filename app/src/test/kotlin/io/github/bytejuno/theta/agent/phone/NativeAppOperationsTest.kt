package io.github.bytejuno.theta.agent.phone

import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NativeAppOperationsTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun calendarCreationUsesOneBatchAndVerifiesReminderBackReferences() {
        val provider = FakeProvider()
        val result =
            PhoneOperations.execute(
                context,
                "create_calendar_event",
                event().put("reminder_minutes", org.json.JSONArray(listOf(60, 1440))),
                access = provider,
            )
        assertTrue(result.toString(), result.getBoolean("verified"))
        assertEquals(1, provider.batches)
        assertEquals(3, provider.lastBatchSize)
        assertEquals("1", result.getString("event_id"))
        assertEquals(setOf("1"), provider.reminders.map { it.getAsString("event_id") }.toSet())
        assertEquals(setOf(60, 1440), provider.reminders.map { it.getAsInteger("minutes") }.toSet())
    }

    @Test
    fun calendarBatchBindsEachReminderToItsOwnEvent() {
        val provider = FakeProvider()
        val events =
            org.json
                .JSONArray()
                .put(event().put("reminder_minutes", org.json.JSONArray(listOf(10))))
                .put(
                    event()
                        .put("title", "另一会议")
                        .put("reminder_minutes", org.json.JSONArray(listOf(30)))
                )
        val result =
            PhoneOperations.execute(
                context,
                "create_calendar_events",
                JSONObject().put("events", events),
                access = provider,
            )
        assertTrue(result.toString(), result.getBoolean("verified"))
        assertEquals(1, provider.batches)
        assertEquals(2, provider.events.size)
        assertEquals(
            mapOf("1" to 10, "2" to 30),
            provider.reminders.associate {
                it.getAsString("event_id") to it.getAsInteger("minutes")
            },
        )
    }

    @Test
    fun invalidLaterEventCannotPartiallyCreateCalendarBatch() {
        val provider = FakeProvider()
        val events =
            org.json
                .JSONArray()
                .put(event())
                .put(event().put("end_time", "2026-10-04T10:00:00+08:00"))
        val result =
            PhoneOperations.execute(
                context,
                "create_calendar_events",
                JSONObject().put("events", events),
                access = provider,
            )
        assertEquals("INVALID_ARGUMENT", result.getString("code"))
        assertEquals(0, provider.batches)
        assertTrue(provider.events.isEmpty())
    }

    @Test
    fun calendarDoesNotGuessAccountOrAcceptReversedAndNonUtcAllDayRanges() {
        val multiple = FakeProvider().apply { calendarCount = 2 }
        val selection =
            PhoneOperations.execute(context, "create_calendar_event", event(), access = multiple)
        assertEquals("CALENDAR_SELECTION_REQUIRED", selection.getString("code"))
        assertEquals(0, multiple.batches)
        val provider = FakeProvider()
        for (args in
            listOf(
                event().put("end_time", "2026-10-04T10:00:00+08:00"),
                event().put("all_day", true),
            )) {
            assertEquals(
                "INVALID_ARGUMENT",
                PhoneOperations.execute(context, "create_calendar_event", args, access = provider)
                    .getString("code"),
            )
        }
        assertEquals(0, provider.batches)
    }

    @Test
    fun calendarWriteValidationFailureIsRejectedBeforeProviderMutation() {
        val provider = FakeProvider()
        val result = PhoneOperations.execute(context, "create_calendar_event",
            event().put("end_time", "invalid"), access = provider)
        assertEquals("INVALID_ARGUMENT", result.getString("code"))
        assertEquals("rejected", result.getString("status"))
        assertFalse(result.getBoolean("verified"))
        assertEquals(0, provider.batches)
    }

    @Test
    fun nativeFailureAfterDispatchIsNotReportedAsSafeToRetry() {
        val provider = FakeProvider().apply { failBatch = true }
        val result =
            PhoneOperations.execute(context, "create_calendar_event", event(), access = provider)
        assertFalse(result.getBoolean("ok"))
        assertEquals("unconfirmed", result.getString("status"))
        assertFalse(result.getBoolean("verified"))
        assertFalse(result.toString().contains("private-server-message"))
    }

    @Test
    fun aNonNullErrorUriIsNeverAConfirmedNoteCreation() {
        val provider = FakeProvider().apply { noteError = true }
        val result =
            PhoneOperations.execute(
                context,
                "create_note",
                JSONObject().put("content", "新便签正文"),
                access = provider,
            )
        assertFalse(result.getBoolean("ok"))
        assertEquals("NOTE_CREATE_REJECTED", result.getString("code"))
        assertFalse(result.has("note_id"))
        assertEquals(provider.callerPackage, provider.lastInsert!!.getAsString("package_name"))
    }

    @Test
    fun noteRequiresReadableProviderBeforeAnyCreation() {
        val provider = FakeProvider().apply { notesReady = false }
        val result =
            PhoneOperations.execute(
                context,
                "create_note",
                JSONObject().put("content", "正文"),
                access = provider,
            )
        assertEquals("NOTES_NOT_READY", result.getString("code"))
        assertNull(provider.lastInsert)
        assertEquals("rejected", result.getString("status"))
    }

    @Test
    fun noteDeletionUsesUuidAndRecyclesWithoutAcceptingIndexIds() {
        val provider = FakeProvider()
        val bad =
            PhoneOperations.execute(
                context,
                "delete_note",
                JSONObject().put("note_id", "123"),
                access = provider,
            )
        assertEquals("INVALID_ARGUMENT", bad.getString("code"))
        assertNull(provider.deletedUri)
    }

    @Test
    fun clockUsesTypedRepeatMaskAndRestoresDisabledStateOnTimeEdit() {
        val provider = FakeProvider()
        val result =
            PhoneOperations.execute(
                context,
                "set_alarm",
                JSONObject()
                    .put("hour", 8)
                    .put("minute", 30)
                    .put("repeat_days", org.json.JSONArray(listOf("mon", "wed", "fri"))),
                userId = 0,
                access = provider,
            )
        assertTrue(result.toString(), result.getBoolean("verified"))
        assertEquals(
            21.toByte(),
            provider.createdAlarm!!.getByte("android.intent.extra.alarm.DAYS_OF_WEEK"),
        )
        assertFalse(provider.createdAlarm!!.containsKey("android.intent.extra.alarm.DAYS"))
        provider.alarmEnabled = false
        val changed =
            PhoneOperations.execute(
                context,
                "update_alarm_time",
                JSONObject().put("alarm_id", "7").put("hour", 9).put("minute", 0),
                userId = 0,
                access = provider,
            )
        assertTrue(changed.toString(), changed.getBoolean("verified"))
        assertFalse(provider.alarmEnabled)
        assertTrue(provider.calls.indexOf("update_alarm") < provider.calls.indexOf("close_alarm"))
    }

    @Test
    fun clockInvalidTimeCannotReachMutation() {
        val provider = FakeProvider()
        val result = PhoneOperations.execute(context, "set_alarm",
            JSONObject().put("hour", -1).put("minute", 0), userId = 0, access = provider)
        assertEquals("INVALID_ARGUMENT", result.getString("code"))
        assertNull(provider.createdAlarm)
    }

    @Test
    fun clockIgnoresExtraFieldsWithoutChangingProviderTarget() {
        val provider = FakeProvider()
        val result = PhoneOperations.execute(context, "set_alarm",
            JSONObject().put("hour", 8).put("minute", 30).put("provider_uri", "content://anything"),
            userId = 0, access = provider)
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals(8, provider.alarmHour)
        assertEquals(30, provider.alarmMinute)
        assertEquals(1, provider.calls.count { it == "add_alarm" })
    }

    @Test
    fun calendarPreparePinsDefaultTargetAndIsNotAModelTool() {
        val provider = FakeProvider()
        val prepared = prepare(provider, "create_calendar_event", event())
        assertTrue(prepared.toString(), prepared.getBoolean("ok"))
        val args = prepared.getJSONObject("normalized_arguments")
        assertEquals("1", args.getString("calendar_id"))
        assertEquals("Asia/Shanghai", args.getString("timezone"))
        assertEquals("2026-10-05T02:00:00Z", args.getString("start_time"))
        assertEquals(0, provider.batches)
        assertTrue(provider.events.isEmpty())
        assertFalse("prepare_calendar_write" in PhoneOperation.tools)
        provider.calendarCount = 2 // New default candidates cannot redirect an approved create.
        val result = PhoneOperations.execute(context, "create_calendar_event",
            args.put("expected_target_digest", prepared.getString("target_digest")), access = provider)
        assertTrue(result.toString(), result.getBoolean("verified"))
        assertEquals("1", provider.events.values.single().getAsString("calendar_id"))
    }

    @Test
    fun calendarPrepareValidatesWholeBatchAndPinsEveryItem() {
        val provider = FakeProvider()
        val prepared = prepare(provider, "create_calendar_events", JSONObject().put("events",
            org.json.JSONArray().put(event()).put(event().put("title", "Second"))))
        val items = prepared.getJSONObject("normalized_arguments").getJSONArray("events")
        assertEquals(2, items.length())
        repeat(2) { assertEquals("1", items.getJSONObject(it).getString("calendar_id")) }
        assertEquals(0, provider.batches)
        val invalid = prepare(provider, "create_calendar_events", JSONObject().put("events",
            org.json.JSONArray().put(event()).put(event().put("end_time", "invalid"))))
        assertFalse(invalid.getBoolean("ok"))
        assertEquals(0, provider.batches)
    }

    @Test
    fun calendarApprovalRejectsChangedAccountAndChangedEventWithoutWriting() {
        val provider = FakeProvider()
        val prepared = prepare(provider, "create_calendar_event", event())
        provider.accountName = "other-account"
        val rejected = PhoneOperations.execute(context, "create_calendar_event",
            prepared.getJSONObject("normalized_arguments").put("expected_target_digest", prepared.getString("target_digest")), access = provider)
        assertEquals("APPROVAL_TARGET_CHANGED", rejected.getString("code"))
        assertEquals("rejected", rejected.getString("status"))
        assertEquals(0, provider.batches)
        PhoneOperations.execute(context, "create_calendar_event", event(), access = provider)
        val update = prepare(provider, "update_calendar_event", JSONObject().put("event_id", "1").put("title", "Approved"))
        val delete = prepare(provider, "delete_calendar_event", JSONObject().put("event_id", "1"))
        assertTrue(update.toString(), update.getBoolean("ok"))
        assertTrue(delete.toString(), delete.getBoolean("ok"))
        assertEquals("1", update.getJSONObject("normalized_arguments").getString("calendar_id"))
        provider.events[1L]!!.put("title", "Changed elsewhere")
        for ((tool, result) in listOf("update_calendar_event" to update, "delete_calendar_event" to delete)) {
            val response = PhoneOperations.execute(context, tool, result.getJSONObject("normalized_arguments")
                .put("expected_target_digest", result.getString("target_digest")), access = provider)
            assertEquals("APPROVAL_TARGET_CHANGED", response.getString("code"))
        }
        assertEquals(1, provider.batches)
        assertNull(provider.deletedUri)
    }

    @Test
    fun preparedUpdateRechecksAndExecutesNormalizedArguments() {
        val provider = FakeProvider()
        PhoneOperations.execute(context, "create_calendar_event", event(), access = provider)
        val prepared = prepare(provider, "update_calendar_event", JSONObject().put("event_id", "1").put("title", "Approved"))
        val response = PhoneOperations.execute(context, "update_calendar_event", prepared.getJSONObject("normalized_arguments")
            .put("expected_target_digest", prepared.getString("target_digest")), access = provider)
        assertTrue(response.toString(), response.getBoolean("verified"))
        assertEquals("Approved", provider.events[1L]!!.getAsString("title"))
    }

    @Test
    fun createReadbackFailureRetainsEveryCommittedId() {
        val provider = FakeProvider().apply { failReadAfterMutation = true }
        val result = PhoneOperations.execute(context, "create_calendar_events", JSONObject().put("events",
            org.json.JSONArray().put(event()).put(event())), access = provider)
        assertEquals("OPERATION_UNCONFIRMED", result.getString("code"))
        assertEquals("unconfirmed", result.getString("status"))
        assertEquals(listOf("1", "2"), (0 until result.getJSONArray("event_ids").length()).map { result.getJSONArray("event_ids").getString(it) })
        assertEquals(2, result.getJSONArray("events").length())
    }

    @Test
    fun updateAndDeleteReadbackFailuresRetainEventId() {
        for (tool in listOf("update_calendar_event", "delete_calendar_event")) {
            val provider = FakeProvider()
            PhoneOperations.execute(context, "create_calendar_event", event(), access = provider)
            provider.failReadAfterMutation = true
            provider.hasMutated = false
            val result = PhoneOperations.execute(context, tool, JSONObject().put("event_id", "1").put("title", "Changed"), access = provider)
            assertEquals(result.toString(), "OPERATION_UNCONFIRMED", result.getString("code"))
            assertEquals("1", result.getString("event_id"))
            assertEquals("unconfirmed", result.getString("status"))
        }
    }

    private fun prepare(provider: FakeProvider, tool: String, args: JSONObject) = PhoneOperations.execute(
        context, "prepare_calendar_write", JSONObject().put("write_tool", tool).put("arguments", args), access = provider,
    )

    private fun event() =
        JSONObject()
            .put("title", "会议")
            .put("start_time", "2026-10-05T10:00:00+08:00")
            .put("end_time", "2026-10-05T11:00:00+08:00")
            .put("timezone", "Asia/Shanghai")

    private class FakeProvider : PhoneProviderAccess {
        override val callerPackage = "example.caller"
        var calendarCount = 1
        var batches = 0
        var lastBatchSize = 0
        var failBatch = false
        var failReadAfterMutation = false
        var hasMutated = false
        var accountName = "original-account"
        val events = mutableMapOf<Long, ContentValues>()
        val reminders = mutableListOf<ContentValues>()
        var lastInsert: ContentValues? = null
        var noteError = false
        var notesReady = true
        var deletedUri: Uri? = null
        val calls = mutableListOf<String>()
        var createdAlarm: Bundle? = null
        var alarmEnabled = true
        var alarmHour = 8
        var alarmMinute = 30
        var repeatMask = 0

        override fun query(
            uri: Uri,
            columns: Array<String>,
            selection: String?,
            args: Array<String>?,
            sort: String?,
        ): Cursor? {
            if (failReadAfterMutation && hasMutated && uri.pathSegments.firstOrNull() in setOf("events", "reminders")) {
                throw IllegalStateException("readback unavailable")
            }
            if (uri.authority.orEmpty().endsWith("com.nearme.note") && !notesReady) return null
            val rows: List<Map<String, Any?>> =
                when (uri.pathSegments.firstOrNull()) {
                    "calendars" ->
                        (1..calendarCount).map {
                            mapOf(
                                "_id" to it,
                                "calendar_displayName" to "日历$it",
                                "calendar_access_level" to 700,
                                "visible" to 1,
                                "account_name" to accountName,
                            )
                        }
                    "events" ->
                        events.entries
                            .filter { args == null || it.key.toString() == args[0] }
                            .map { (id, values) ->
                                values.keySet().associateWith { values.get(it) } +
                                    mapOf("_id" to id, "deleted" to 0)
                            }
                    "reminders" ->
                        reminders
                            .filter { args == null || it.getAsString("event_id") == args[0] }
                            .map { v -> v.keySet().associateWith { v.get(it) } }
                    else -> emptyList()
                }
            return MatrixCursor(columns).apply {
                if (selection != "0")
                    rows.forEach { row -> addRow(columns.map { row[it] }.toTypedArray()) }
            }
        }

        override fun insert(uri: Uri, values: ContentValues): Uri? {
            lastInsert = ContentValues(values)
            return when (uri.pathSegments.first()) {
                "events" -> {
                    val id = events.size.toLong() + 1
                    events[id] = ContentValues(values)
                    uri.buildUpon().appendPath(id.toString()).build()
                }
                "reminders" -> {
                    reminders += ContentValues(values)
                    uri.buildUpon().appendPath(reminders.size.toString()).build()
                }
                "text_note" ->
                    uri.buildUpon()
                        .appendQueryParameter("result", if (noteError) "error" else "success")
                        .appendQueryParameter("message", "private-server-message")
                        .build()
                else -> null
            }
        }

        override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int {
            deletedUri = uri
            if (uri.pathSegments.firstOrNull() == "events") {
                hasMutated = true
                return if (events.remove(uri.lastPathSegment!!.toLong()) != null) 1 else 0
            }
            return 0
        }

        override fun call(uri: Uri, method: String, arg: String?, extras: Bundle): Bundle {
            calls += method
            when (method) {
                "add_alarm" -> {
                    createdAlarm = Bundle(extras)
                    alarmHour = extras.getInt("android.intent.extra.alarm.HOUR")
                    alarmMinute = extras.getInt("android.intent.extra.alarm.MINUTES")
                    repeatMask = extras.getByte("android.intent.extra.alarm.DAYS_OF_WEEK").toInt()
                }
                "update_alarm" -> {
                    alarmHour = extras.getInt("alarm_hour")
                    alarmMinute = extras.getInt("alarm_minute")
                    alarmEnabled = true
                }
                "close_alarm" -> alarmEnabled = false
                "enable_alarm" -> alarmEnabled = true
            }
            return Bundle().apply {
                putInt("result", 1)
                putLongArray("alarm_id_list", longArrayOf(7))
                putIntArray("alarm_hour_list", intArrayOf(alarmHour))
                putIntArray("alarm_min_list", intArrayOf(alarmMinute))
                putBooleanArray("alarm_state_list", booleanArrayOf(alarmEnabled))
                putIntArray("alarm_repeat_set_list", intArrayOf(repeatMask))
                putStringArray("alarm_label_list", arrayOf(""))
            }
        }

        override fun applyBatch(
            authority: String,
            operations: ArrayList<ContentProviderOperation>,
        ): Array<ContentProviderResult> {
            batches++
            lastBatchSize = operations.size
            if (failBatch) throw IllegalStateException("private-server-message")
            val provider =
                object : ContentProvider() {
                    override fun onCreate() = true

                    override fun query(
                        uri: Uri,
                        projection: Array<out String>?,
                        selection: String?,
                        selectionArgs: Array<out String>?,
                        sortOrder: String?,
                    ): Cursor? = null

                    override fun getType(uri: Uri): String? = null

                    override fun insert(uri: Uri, values: ContentValues?): Uri? =
                        this@FakeProvider.insert(uri, values!!)

                    override fun delete(
                        uri: Uri,
                        selection: String?,
                        selectionArgs: Array<out String>?,
                    ): Int = 0

                    override fun update(
                        uri: Uri,
                        values: ContentValues?,
                        selection: String?,
                        selectionArgs: Array<out String>?,
                    ): Int {
                        val existing = events[uri.lastPathSegment!!.toLong()] ?: return 0
                        existing.putAll(values!!)
                        return 1
                    }
                }
            val results = arrayOfNulls<ContentProviderResult>(operations.size)
            operations.forEachIndexed { index, operation ->
                results[index] = operation.apply(provider, results, index)
            }
            hasMutated = true
            @Suppress("UNCHECKED_CAST")
            return results as Array<ContentProviderResult>
        }
    }
}
