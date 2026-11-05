package io.github.bytejuno.theta.data.repository

import android.app.Application
import android.app.job.JobScheduler
import android.content.Context
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AutoBackupSchedulerTest {
    @Test fun onlyOwnedCompleteBackupsCanBeRotated() {
        assertTrue(AutoBackupScheduler.ownedBackup("eta-auto-123-12345678-1234-1234-1234-123456789abc.json"))
        assertFalse(AutoBackupScheduler.ownedBackup("my-backup.json"))
        assertFalse(AutoBackupScheduler.ownedBackup("eta-auto-not-a-backup.json"))
        assertFalse(AutoBackupScheduler.ownedBackup("eta-auto-123-12345678-1234-1234-1234-123456789abc.json.partial"))
        assertTrue(AutoBackupScheduler.ownedPartial("eta-auto-123-12345678-1234-1234-1234-123456789abc.json.partial"))
        assertTrue(AutoBackupScheduler.ownedPartial("eta-auto-123-12345678-1234-1234-1234-123456789abc.json.partial.json"))
        assertFalse(AutoBackupScheduler.ownedPartial("my-backup.json.partial"))
    }
    @Test fun repeatedScheduleKeepsExistingPeriodicJob() {
        val context = RuntimeEnvironment.getApplication() as Context
        AutoBackupScheduler.configure(context, Uri.parse("content://test/tree/backup"))
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val first = scheduler.getPendingJob(12031)
        AutoBackupScheduler.schedule(context)
        assertSame(first, scheduler.getPendingJob(12031))
        AutoBackupScheduler.configure(context, null)
    }
    @Test fun optInSchedulesConstrainedJobAndDisableCancelsIt() {
        val context = RuntimeEnvironment.getApplication() as Context
        AutoBackupScheduler.configure(context, null)
        assertFalse(AutoBackupScheduler.enabled(context))
        AutoBackupScheduler.configure(context, Uri.parse("content://test/tree/backup"))
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val job = scheduler.allPendingJobs.single { it.id == 12031 }
        assertTrue(job.isRequireCharging)
        assertTrue(job.isRequireBatteryNotLow)
        assertTrue(job.isPersisted)
        AutoBackupScheduler.configure(context, null)
        assertTrue(scheduler.allPendingJobs.none { it.id == 12031 })
    }
}
