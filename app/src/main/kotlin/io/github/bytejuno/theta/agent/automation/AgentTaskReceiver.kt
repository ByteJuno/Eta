package io.github.bytejuno.theta.agent.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

internal class AgentTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        worker.execute {
            try {
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    runBlocking {
                        io.github.bytejuno.theta.data.db.EtaDatabase.get(context).agentTaskDao()
                            .recoverInterrupted(System.currentTimeMillis())
                    }
                    AgentTaskScheduler.publish(
                        context,
                        "device_boot",
                        System.currentTimeMillis().toString()
                    )
                }
                AgentTaskScheduler.enqueueDue(context)
                AgentTaskScheduler.refresh(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_TICK = "io.github.bytejuno.theta.action.AUTOMATION_TICK"
        private val worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(
                runnable,
                "eta-task-alarm"
            ).apply { isDaemon = true }
        }
    }
}
