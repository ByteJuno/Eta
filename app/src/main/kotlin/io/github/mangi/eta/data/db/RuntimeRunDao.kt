package io.github.mangi.eta.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
internal interface RuntimeRunDao : ChunkedTextDao {
    suspend fun restoreRuntimeResult(row: RuntimeResultEntity): RuntimeResultEntity = row.copy(
        content = restoreText("runtime_results", row.runId, "content", row.content),
        reasoningContent = restoreText("runtime_results", row.runId, "reasoningContent", row.reasoningContent),
        transcriptJson = restoreText("runtime_results", row.runId, "transcriptJson", row.transcriptJson),
        contextSnapshotJson = restoreText("runtime_results", row.runId, "contextSnapshotJson", row.contextSnapshotJson),
    )

    suspend fun restoreArchivedRun(row: RuntimeArchiveRunEntity): RuntimeArchiveRunEntity = row.copy(
        content = restoreText("runtime_archive_runs", row.archiveRunId, "content", row.content),
        reasoningContent = restoreText("runtime_archive_runs", row.archiveRunId, "reasoningContent", row.reasoningContent),
        transcriptJson = restoreText("runtime_archive_runs", row.archiveRunId, "transcriptJson", row.transcriptJson),
        contextSnapshotJson = restoreText("runtime_archive_runs", row.archiveRunId, "contextSnapshotJson", row.contextSnapshotJson),
        userImagePreviewsJson = restoreText("runtime_archive_runs", row.archiveRunId, "userImagePreviewsJson", row.userImagePreviewsJson),
    )

    suspend fun restoreInFlightRun(row: RuntimeInFlightRunEntity): RuntimeInFlightRunEntity = row.copy(
        transcriptJson = restoreText("runtime_inflight_runs", row.runId, "transcriptJson", row.transcriptJson),
        contextSnapshotJson = restoreText("runtime_inflight_runs", row.runId, "contextSnapshotJson", row.contextSnapshotJson),
    )

    @Query("SELECT run_id, handoff_id, handoff_source, handoff_payload, dismiss_entry_surface, ok, operation, rewrite_target_message_id, created_at FROM runtime_results ORDER BY created_at ASC LIMIT :limit")
    suspend fun pendingResultHeaders(limit: Int): List<RuntimeResultHeader>

    @Query("SELECT * FROM runtime_results WHERE run_id = :runId AND handoff_payload = :owner")
    suspend fun ownedResultRow(runId: String, owner: String): RuntimeResultEntity?

    @Transaction
    suspend fun ownedResult(runId: String, owner: String): RuntimeResultEntity? =
        ownedResultRow(runId, owner)?.let { restoreRuntimeResult(it) }

    @Query("SELECT EXISTS(SELECT 1 FROM runtime_inflight_runs WHERE run_id = :runId)")
    suspend fun hasInFlightRun(runId: String): Boolean

    @Query("UPDATE runtime_inflight_runs SET transcript_json = :transcript WHERE run_id = :runId")
    suspend fun updateTranscriptRow(runId: String, transcript: String)

    @Transaction
    suspend fun updateTranscript(runId: String, transcript: String) {
        if (!hasInFlightRun(runId)) return
        updateTranscriptRow(runId, storeText("runtime_inflight_runs", runId, "transcriptJson", transcript))
    }

    @Query("UPDATE runtime_inflight_runs SET context_snapshot_json = :snapshot WHERE run_id = :runId")
    suspend fun updateContextSnapshotRow(runId: String, snapshot: String)

    @Transaction
    suspend fun updateContextSnapshot(runId: String, snapshot: String) {
        if (!hasInFlightRun(runId)) return
        updateContextSnapshotRow(runId, storeText("runtime_inflight_runs", runId, "contextSnapshotJson", snapshot))
    }

    @Query("SELECT * FROM runtime_results ORDER BY created_at ASC")
    suspend fun runtimeResultRows(): List<RuntimeResultEntity>

    @Transaction
    suspend fun runtimeResults(): List<RuntimeResultEntity> = runtimeResultRows().map { restoreRuntimeResult(it) }

    @Upsert
    suspend fun upsertRuntimeResultRow(result: RuntimeResultEntity)

    @Transaction
    suspend fun upsertRuntimeResult(result: RuntimeResultEntity) {
        upsertRuntimeResultRow(result.copy(
            content = storeText("runtime_results", result.runId, "content", result.content),
            reasoningContent = storeText("runtime_results", result.runId, "reasoningContent", result.reasoningContent),
            transcriptJson = storeText("runtime_results", result.runId, "transcriptJson", result.transcriptJson),
            contextSnapshotJson = storeText("runtime_results", result.runId, "contextSnapshotJson", result.contextSnapshotJson),
        ))
    }

    @Query("DELETE FROM runtime_results WHERE run_id = :runId")
    suspend fun deleteRuntimeResult(runId: String)

    @Query("DELETE FROM runtime_results")
    suspend fun deleteRuntimeResults()

    @Transaction
    suspend fun insertRuntimeResults(results: List<RuntimeResultEntity>) {
        results.forEach { upsertRuntimeResult(it) }
    }

    @Transaction
    suspend fun replaceRuntimeResults(results: List<RuntimeResultEntity>) {
        val retainedRunIds = results.mapTo(mutableSetOf()) { it.runId }
        val removedRunIds = runtimeResults()
            .map { it.runId }
            .filterNot { it in retainedRunIds }
        deleteRuntimeResults()
        if (results.isNotEmpty()) {
            insertRuntimeResults(results)
        }
        removedRunIds.forEach { runId -> deleteInFlightRun(runId) }
    }

    @Transaction
    @Query("SELECT * FROM runtime_archive_runs ORDER BY created_at ASC")
    suspend fun archivedRunRows(): List<RuntimeArchiveRunWithEvents>

    @Transaction
    suspend fun archivedRuns(): List<RuntimeArchiveRunWithEvents> = archivedRunRows().map { stored ->
        stored.copy(run = restoreArchivedRun(stored.run), events = stored.events.map { event ->
            event.copy(eventJson = restoreText("runtime_archive_runs", event.archiveRunId, "event:${event.sortIndex}", event.eventJson))
        })
    }

    @Upsert
    suspend fun upsertArchivedRunRow(run: RuntimeArchiveRunEntity)

    @Transaction
    suspend fun upsertArchivedRun(run: RuntimeArchiveRunEntity) {
        upsertArchivedRunRow(run.copy(
            content = storeText("runtime_archive_runs", run.archiveRunId, "content", run.content),
            reasoningContent = storeText("runtime_archive_runs", run.archiveRunId, "reasoningContent", run.reasoningContent),
            transcriptJson = storeText("runtime_archive_runs", run.archiveRunId, "transcriptJson", run.transcriptJson),
            contextSnapshotJson = storeText("runtime_archive_runs", run.archiveRunId, "contextSnapshotJson", run.contextSnapshotJson),
            userImagePreviewsJson = storeText("runtime_archive_runs", run.archiveRunId, "userImagePreviewsJson", run.userImagePreviewsJson),
        ))
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArchivedEventRow(event: RuntimeArchiveEventEntity)

    @Transaction
    suspend fun insertArchivedEvents(events: List<RuntimeArchiveEventEntity>) {
        events.forEach { event ->
            insertArchivedEventRow(event.copy(eventJson = storeText(
                "runtime_archive_runs", event.archiveRunId, "event:${event.sortIndex}", event.eventJson,
            )))
        }
    }

    @Query("DELETE FROM runtime_archive_events WHERE archive_run_id = :archiveRunId")
    suspend fun deleteArchivedEvents(archiveRunId: String)

    @Query("DELETE FROM runtime_archive_runs WHERE archive_run_id = :archiveRunId")
    suspend fun deleteArchivedRunByArchiveId(archiveRunId: String)

    @Query("DELETE FROM runtime_archive_runs WHERE run_id = :runId OR handoff_id = :runId")
    suspend fun deleteArchivedRun(runId: String)

    @Query("DELETE FROM runtime_archive_events")
    suspend fun deleteAllArchivedEvents()

    @Query("DELETE FROM runtime_archive_runs")
    suspend fun deleteAllArchivedRuns()

    @Transaction
    suspend fun replaceArchivedRun(
        run: RuntimeArchiveRunEntity,
        events: List<RuntimeArchiveEventEntity>,
    ) {
        deleteArchivedEvents(run.archiveRunId)
        upsertArchivedRun(run)
        if (events.isNotEmpty()) {
            insertArchivedEvents(events)
        }
    }

    @Transaction
    suspend fun replaceArchivedRuns(runs: List<RuntimeArchiveRunWithEventsSeed>) {
        deleteAllArchivedEvents()
        deleteAllArchivedRuns()
        runs.forEach { seed ->
            upsertArchivedRun(seed.run)
            if (seed.events.isNotEmpty()) {
                insertArchivedEvents(seed.events)
            }
        }
    }

    @Transaction
    @Query("SELECT * FROM runtime_inflight_runs ORDER BY created_at ASC")
    suspend fun inFlightRunRows(): List<RuntimeInFlightRunWithEvents>

    /** 分块存储之前写入的超大事件行会让整个查询抛 SQLiteBlobTooBigException（上游 #230），读前先丢弃。 */
    @Query("DELETE FROM runtime_inflight_events WHERE length(event_json) > :maxChars")
    suspend fun deleteOversizedInFlightEvents(maxChars: Int)

    @Query("DELETE FROM agent_text_chunks WHERE owner_table = 'runtime_inflight_runs' AND owner_id = :runId AND field LIKE 'event:%'")
    suspend fun deleteInFlightEventChunks(runId: String)

    @Transaction
    suspend fun inFlightRuns(): List<RuntimeInFlightRunWithEvents> {
        deleteOversizedInFlightEvents(MAX_LEGACY_EVENT_CHARS)
        return inFlightRunRows().map { stored ->
            stored.copy(run = restoreInFlightRun(stored.run), events = stored.events.map { event ->
                event.copy(eventJson = restoreText("runtime_inflight_runs", event.runId, "event:${event.sortIndex}", event.eventJson))
            })
        }
    }

    @Upsert
    suspend fun upsertInFlightRunRow(run: RuntimeInFlightRunEntity)

    @Transaction
    suspend fun upsertInFlightRun(run: RuntimeInFlightRunEntity) {
        upsertInFlightRunRow(run.copy(
            transcriptJson = storeText("runtime_inflight_runs", run.runId, "transcriptJson", run.transcriptJson),
            contextSnapshotJson = storeText("runtime_inflight_runs", run.runId, "contextSnapshotJson", run.contextSnapshotJson),
        ))
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInFlightEvent(event: RuntimeInFlightEventEntity)

    @Query("UPDATE runtime_inflight_runs SET updated_at = :updatedAt WHERE run_id = :runId")
    suspend fun touchInFlightRun(runId: String, updatedAt: Long)

    @Query("DELETE FROM runtime_inflight_events WHERE run_id = :runId")
    suspend fun deleteInFlightEvents(runId: String)

    @Query("DELETE FROM runtime_inflight_runs WHERE run_id = :runId")
    suspend fun deleteInFlightRun(runId: String)

    @Transaction
    suspend fun acknowledgeRuntimeResult(runId: String) {
        deleteRuntimeResult(runId)
        deleteInFlightRun(runId)
    }

    @Transaction
    suspend fun replaceInFlightRun(run: RuntimeInFlightRunEntity) {
        deleteInFlightEvents(run.runId)
        deleteInFlightEventChunks(run.runId)
        upsertInFlightRun(run)
    }

    @Transaction
    suspend fun appendInFlightEvent(event: RuntimeInFlightEventEntity, updatedAt: Long) {
        insertInFlightEvent(event.copy(
            eventJson = storeText("runtime_inflight_runs", event.runId, "event:${event.sortIndex}", event.eventJson),
        ))
        touchInFlightRun(event.runId, updatedAt)
    }

    companion object {
        /** 远低于 CursorWindow 2 MB；分块后的引用行只有几十字符。 */
        const val MAX_LEGACY_EVENT_CHARS = 512 * 1024
    }
}

internal data class RuntimeArchiveRunWithEventsSeed(
    val run: RuntimeArchiveRunEntity,
    val events: List<RuntimeArchiveEventEntity>,
)
