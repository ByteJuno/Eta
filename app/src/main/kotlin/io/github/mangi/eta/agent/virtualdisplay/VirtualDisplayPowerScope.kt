package io.github.mangi.eta.agent.virtualdisplay

import android.content.Context
import android.os.PowerManager
import android.view.Display

/** 只唤醒虚拟屏独立显示组；ROM 不支持时降级，绝不退回物理屏唤醒锁。 */
internal object VirtualDisplayPowerScope {
    fun isDedicatedGroup(displayId: Int, groupId: Int): Boolean = displayId > 0 && groupId > 0

    @Suppress("DEPRECATION")
    fun acquire(context: Context, display: Display): AutoCloseable? = runCatching {
        val infoClass = Class.forName("android.view.DisplayInfo")
        val info = infoClass.getDeclaredConstructor().newInstance()
        require(Display::class.java.getMethod("getDisplayInfo", infoClass).invoke(display, info) == true)
        require(isDedicatedGroup(display.displayId, infoClass.getField("displayGroupId").getInt(info)))
        val power = context.getSystemService(PowerManager::class.java)
        val screen = PowerManager::class.java.getMethod(
            "newWakeLock", Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType,
        ).invoke(power, PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Eta:virtual-display", display.displayId) as PowerManager.WakeLock
        // Android 15 某些 ROM 会拒绝显示组锁；取得失败不能阻止创建虚拟屏。
        screen.acquire()
        val cpu = runCatching {
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Eta:virtual-display-cpu").apply { acquire() }
        }.getOrNull()
        AutoCloseable {
            runCatching { if (screen.isHeld) screen.release() }
            runCatching { if (cpu?.isHeld == true) cpu.release() }
        }
    }.getOrNull()
}
