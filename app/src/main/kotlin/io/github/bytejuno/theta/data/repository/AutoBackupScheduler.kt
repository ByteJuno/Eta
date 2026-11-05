package io.github.bytejuno.theta.data.repository

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object AutoBackupScheduler {
    private const val JOB_ID = 12031
    private const val PREFS = "eta_auto_backup"
    const val PREFIX = "eta-auto-"
    private val lock = Mutex()
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun status(context: Context): String {
        val raw = prefs(context).getString("status", "").orEmpty()
        val time = raw.removePrefix("OK ").toLongOrNull() ?: return raw
        return "OK " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
            .format(java.util.Date(time))
    }
    fun configure(context: Context, tree: Uri?) {
        val previous = prefs(context).getString("tree", null)
        check(prefs(context).edit().putBoolean("enabled", tree != null)
            .putString("tree", tree?.toString()).commit())
        // 关闭或换目录时归还旧目录的持久授权，避免占满系统授权配额。
        if (previous != null && previous != tree?.toString()) runCatching {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(previous),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        schedule(context)
    }
    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!enabled(context)) { scheduler.cancel(JOB_ID); return }
        // 应用每次启动都会调用；重复 schedule 会重置周期计时，频繁打开时备份可能永远不执行。
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val result = scheduler.schedule(JobInfo.Builder(JOB_ID,
            ComponentName(context, AutoBackupJobService::class.java))
            .setPersisted(true).setRequiresCharging(true).setRequiresBatteryNotLow(true)
            .setPeriodic(24L * 60 * 60 * 1000).build())
        check(result == JobScheduler.RESULT_SUCCESS) { "BACKUP_SCHEDULING_FAILED" }
    }
    fun ownedBackup(name: String): Boolean =
        Regex("eta-auto-[0-9]+-[a-f0-9-]{36}\\.json").matches(name)
    /** 进程被杀留下的半成品；SAF 可能按 MIME 再补一个 .json 后缀。 */
    fun ownedPartial(name: String): Boolean =
        Regex("eta-auto-[0-9]+-[a-f0-9-]{36}\\.json\\.partial(\\.json)?").matches(name)

    /** Export before pruning. Failed exports are deleted; unrelated documents are never touched. */
    suspend fun run(context: Context) = lock.withLock {
        check(enabled(context)) { "BACKUP_DISABLED" }
        val raw = prefs(context).getString("tree", null) ?: error("BACKUP_FOLDER_REQUIRED")
        val tree = Uri.parse(raw)
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = "$PREFIX${System.currentTimeMillis()}-${UUID.randomUUID()}.json"
        val document = DocumentsContract.createDocument(resolver, parent, "application/json", "$name.partial")
            ?: error("BACKUP_CREATE_FAILED")
        try {
            resolver.openOutputStream(document, "wt")?.use { EtaBackupRepository.export(context, it) }
                ?: error("BACKUP_WRITE_FAILED")
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, document) }
            throw error
        }
        val completed = DocumentsContract.renameDocument(resolver, document, name)
            ?: error("BACKUP_FINALIZE_FAILED")
        // Keep the successful new backup plus six previous owned files.
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val previous = mutableListOf<Pair<String, String>>()
        val stale = mutableListOf<String>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0); val childName = cursor.getString(1).orEmpty()
                if (ownedBackup(childName) && id != DocumentsContract.getDocumentId(completed)) previous += childName to id
                // run 在进程内串行，此刻存在的半成品都来自之前中断的执行。
                if (ownedPartial(childName)) stale += id
            }
        }
        (previous.sortedByDescending { it.first }.drop(6).map { it.second } + stale).forEach { id ->
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
        }
        prefs(context).edit().putString("status", "OK ${System.currentTimeMillis()}").apply()
    }
    fun failed(context: Context) { prefs(context).edit().putString("status", "FAILED").apply() }
}

internal class AutoBackupJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            try { AutoBackupScheduler.run(applicationContext) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { AutoBackupScheduler.failed(applicationContext) }
            jobFinished(params, false)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); return false }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

internal class AutoBackupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED))
            runCatching { AutoBackupScheduler.schedule(context) }
    }
}
