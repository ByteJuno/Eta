package io.github.mangi.eta.hook.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.core.ModuleConfig
import java.lang.reflect.Method

/**
 * 电源键长按直接打开 Eta 主页面的策略与启动入口。
 *
 * 该路径只由设置页的 [io.github.mangi.eta.config.Prefs.Keys.POWER_KEY_ETA_OPEN_MAIN] 开关
 * 控制，并且只在电源键目标为 Eta 时生效。它绕开 `voiceinteraction` 会话，因此不要求 Eta
 * 已成为系统默认数字助理；启动失败由调用方回退系统原逻辑，不触发浮窗或后台配置修复。
 *
 * 锁屏不绕过：Activity 按系统 `keyguard` 约束启动，与桌面图标的行为一致。
 */
internal object EtaPowerMainActivity {

    private const val START_ACTIVITY_AS_USER = "startActivityAsUser"
    private const val USER_HANDLE_CLASS = "android.os.UserHandle"

    /** 仅「目标为 Eta」且开关开启时改走主页面，其余组合保持原有唤起逻辑。 */
    fun shouldOpenMain(target: PowerAssistantTarget, openMainEnabled: Boolean): Boolean =
        openMainEnabled && target == PowerAssistantTarget.ETA

    /**
     * 与桌面图标一致的显式启动 Intent。
     *
     * 组件名由 [ModuleConfig.ETA_PACKAGE] 派生，对应 Manifest 中唯一声明
     * `MAIN`/`LAUNCHER` 的 `MainActivity`；三个 flag 让已有任务被复用并回到栈顶。
     */
    fun mainActivityIntent(): Intent = Intent(Intent.ACTION_MAIN).apply {
        component = ComponentName(ModuleConfig.ETA_PACKAGE, ModuleConfig.ETA_MAIN_ACTIVITY_CLASS)
        addCategory(Intent.CATEGORY_LAUNCHER)
        addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
    }

    /** 在 system_server 中显式核实组件存在且启用，避免 ROM 版本差异下启动不存在的 Activity。 */
    fun isResolvable(context: Context, intent: Intent = mainActivityIntent()): Boolean {
        val component = intent.component ?: return false
        return runCatching {
            val info = context.packageManager.getActivityInfo(component, 0)
            info.enabled && info.applicationInfo?.enabled != false
        }.getOrDefault(false)
    }

    /**
     * 打开 Eta 主页面，成功返回 true。
     *
     * 任何失败（组件缺失、跨用户启动被拒绝、系统抛出异常）都只返回 false，
     * 让调用方执行 OEM 原逻辑，不产生半成功的接管状态。
     */
    fun launch(
        context: Context,
        intent: Intent = mainActivityIntent(),
        start: (Context, Intent) -> Unit = { target, activityIntent ->
            startActivityAsCurrentUser(target, activityIntent)
        },
    ): Boolean {
        if (!isResolvable(context, intent)) return false
        return runCatching {
            start(context, intent)
            true
        }.getOrDefault(false)
    }

    /**
     * system_server 的 `PhoneWindowManager.mContext` 属于用户 0，直接 `startActivity`
     * 会在用户 0 启动；这里显式带上当前前台用户。
     */
    private fun startActivityAsCurrentUser(context: Context, intent: Intent) {
        val userHandle = currentUserId()?.let(::userHandleOf)
        val method = userHandle?.let { resolveStartActivityAsUser(context) }
        if (userHandle != null && method != null) {
            val arguments = if (method.parameterCount >= 3) {
                arrayOf<Any?>(intent, null, userHandle)
            } else {
                arrayOf<Any?>(intent, userHandle)
            }
            method.invoke(context, *arguments)
            return
        }
        // 隐藏方法不可用时退回普通启动；单用户设备行为与 OEM 现有 Activity 兜底一致。
        check(currentUserId() == 0) { "Cannot resolve current-user activity launch" }
        context.startActivity(intent)
    }

    private fun resolveStartActivityAsUser(context: Context): Method? {
        var type: Class<*>? = context.javaClass
        while (type != null) {
            val current = type
            val declared = runCatching { current.declaredMethods.toList() }
                .getOrNull()
                .orEmpty()
            val match = declared.firstOrNull { method ->
                method.name == START_ACTIVITY_AS_USER &&
                    method.parameterCount in 2..3 &&
                    method.parameterTypes.firstOrNull() == Intent::class.java &&
                    method.parameterTypes.lastOrNull()?.name == USER_HANDLE_CLASS &&
                    (method.parameterCount == 2 || method.parameterTypes[1] == android.os.Bundle::class.java)
            }
            if (match != null) {
                return runCatching { match.apply { isAccessible = true } }.getOrNull()
            }
            type = current.superclass
        }
        return null
    }

    private fun currentUserId(): Int? = runCatching {
        Class.forName("android.app.ActivityManager")
            .getDeclaredMethod("getCurrentUser")
            .invoke(null) as Int
    }.getOrNull()

    private fun userHandleOf(userId: Int): Any? = runCatching {
        val userHandleClass = Class.forName(USER_HANDLE_CLASS)
        val ofMethod = userHandleClass.methods.firstOrNull { method ->
            method.name == "of" &&
                method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }
        if (ofMethod != null) {
            return@runCatching ofMethod.invoke(null, userId)
        }
        val constructor = userHandleClass.getDeclaredConstructor(Int::class.javaPrimitiveType)
        constructor.isAccessible = true
        constructor.newInstance(userId)
    }.getOrNull()
}
