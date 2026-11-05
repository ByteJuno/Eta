package io.github.mangi.eta.agent.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.IBinder
import org.json.JSONObject
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.MainActivity

/** 仅在用户启用了事件任务时监控系统事件；退出后不补发历史事件。 */
internal class AgentTriggerMonitorService : Service() {
    private var networkRegistered = false
    private var receiverRegistered = false
    private var lastBatteryLevel: Int? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> return onBatteryChanged(context, intent)
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                    return onBluetooth(context, intent)
            }
            val type = when (intent.action) {
                Intent.ACTION_USER_PRESENT -> "device_unlocked"
                Intent.ACTION_SCREEN_ON -> "screen_on"
                Intent.ACTION_SCREEN_OFF -> "screen_off"
                Intent.ACTION_POWER_CONNECTED -> "charging_connected"
                Intent.ACTION_POWER_DISCONNECTED -> "charging_disconnected"
                Intent.ACTION_BATTERY_LOW -> "battery_low"
                Intent.ACTION_BATTERY_OKAY -> "battery_okay"
                Intent.ACTION_HEADSET_PLUG -> if (intent.getIntExtra(
                        "state",
                        0
                    ) == 1
                ) "headset_connected" else "headset_disconnected"

                else -> return
            }
            AgentTaskScheduler.publish(context, type, System.currentTimeMillis().toString())
        }
    }

    private fun onBluetooth(context: Context, intent: Intent) {
        val type = if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) "bluetooth_connected" else "bluetooth_disconnected"
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        // 读取名称需要 BLUETOOTH_CONNECT；未授权时仍发布事件，只是不能按设备名过滤。
        val name = runCatching { device?.name }.getOrNull()?.trim().orEmpty()
        val data = JSONObject().apply { if (name.isNotEmpty()) put("deviceName", name.take(100)) }
        AgentTaskScheduler.publish(context, type, System.currentTimeMillis().toString(), data)
    }

    /** BATTERY_CHANGED 是粘性广播：注册时立即收到一次，只用来记录起点。 */
    private fun onBatteryChanged(context: Context, intent: Intent) {
        val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (raw < 0 || scale <= 0) return
        val level = raw * 100 / scale
        val previous = lastBatteryLevel
        lastBatteryLevel = level
        if (previous == null || previous == level) return
        AgentTaskScheduler.publish(
            context,
            "battery_level",
            "$previous->$level:${System.currentTimeMillis()}",
            JSONObject().put("level", level).put("previous", previous),
        )
    }

    // FLAG_INCLUDE_LOCATION_INFO 让已获精确位置授权时 WifiInfo 带真实 SSID；未授权时为 <unknown ssid>。
    private val networkCallback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
        private val types = mutableMapOf<Network, NetworkIdentity>()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val transport = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
            val ssid = (capabilities.transportInfo as? WifiInfo)?.ssid?.let(::normalizeSsid)
            val identity = NetworkIdentity(transport, ssid)
            if (types.put(network, identity) != identity) AgentTaskScheduler.publish(
                this@AgentTriggerMonitorService,
                "network_connected",
                "$network:$transport",
                identity.toJson(),
            )
        }

        override fun onLost(network: Network) {
            val identity = types.remove(network) ?: NetworkIdentity("other", null)
            AgentTaskScheduler.publish(
                this@AgentTriggerMonitorService,
                "network_disconnected",
                network.toString(),
                identity.toJson(),
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.automation_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val open = PendingIntent.getActivity(
            this,
            1111,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification =
            Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.automation_monitor_title))
                .setContentText(getString(R.string.automation_monitor_summary))
                .setContentIntent(open).setOngoing(true).build()
        if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(
            1111,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        else startForeground(1111, notification)
        val filter = IntentFilter().apply {
            listOf(
                Intent.ACTION_USER_PRESENT,
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_POWER_CONNECTED,
                Intent.ACTION_POWER_DISCONNECTED,
                Intent.ACTION_BATTERY_LOW,
                Intent.ACTION_BATTERY_OKAY,
                Intent.ACTION_HEADSET_PLUG,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                Intent.ACTION_BATTERY_CHANGED,
            ).forEach(::addAction)
        }
        registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        receiverRegistered = true
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            networkCallback
        )
        networkRegistered = true
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        isRunning = false
        if (receiverRegistered) unregisterReceiver(receiver)
        if (networkRegistered) getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(
            networkCallback
        )
        super.onDestroy()
    }

    private data class NetworkIdentity(val transport: String, val ssid: String?) {
        fun toJson(): JSONObject = JSONObject().put("transport", transport).apply {
            if (ssid != null) put("ssid", ssid)
        }
    }

    companion object {
        private const val CHANNEL = "eta_automation_triggers"

        /** WifiInfo.ssid 带引号；无授权或未知时返回 null。 */
        internal fun normalizeSsid(raw: String): String? =
            raw.removeSurrounding("\"").trim()
                .takeUnless { it.isEmpty() || it == WifiManager.UNKNOWN_SSID }
                ?.take(64)
        @Volatile
        var isRunning = false
            private set
    }
}
