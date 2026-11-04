package io.github.mangi.eta.hook.system

import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Message
import android.os.SystemClock
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.config.Prefs
import io.github.libxposed.api.XposedModule

internal object PowerHooks {
    private const val OEM_ASSISTANT_HAPTIC_EFFECT_ID = 0
    private const val OEM_ASSISTANT_HAPTIC_REASON = "Speech - Long Press"

    @Volatile
    private var lastInterceptUptime = 0L

    /** 消息入口已决定回退原逻辑时，同线程内的 startSpeech 不再重复尝试。 */
    private val nativeFallbackInProgress = ThreadLocal<Boolean>()

    private enum class LaunchResult {
        LAUNCHED,
        ACTIVITY_FALLBACK_REQUIRED,
        NOT_HANDLED
    }

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "Power")
        return hooks.install {
            // 当前机型实测证明 OplusSpeechHandler 是必要路径，目标在热路径即时读取。
            hookOplusSpeechHandler(hooks, classLoader)
            // 平板横屏、Home 长按与键盘快捷键等入口不经消息，直接调用 startSpeech。
            hookOplusStartSpeech(hooks, classLoader)
        }
    }

    private fun hookOplusStartSpeech(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val logger = hooks.logger
        val extClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.OP_LUS_PHONE_WINDOW_MANAGER_EXT_CLASS)
        val startSpeechMethod = extClass?.let {
            HookSupport.findMethod(
                it,
                "startSpeech",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Long::class.javaPrimitiveType!!
            )
        }
        if (startSpeechMethod == null) {
            hooks.missing(
                id = "system.power-assist-start-speech",
                description = "PhoneWindowManagerExtImpl.startSpeech",
                detail = "未找到 PhoneWindowManagerExtImpl.startSpeech(int, int, long)"
            )
            return
        }

        hooks.intercept(
            id = "system.power-assist-start-speech",
            executable = startSpeechMethod,
            description = "PhoneWindowManagerExtImpl.startSpeech"
        ) { chain ->
            if (nativeFallbackInProgress.get() == true) {
                return@intercept chain.proceed()
            }
            val ext = chain.getThisObject() ?: return@intercept chain.proceed()
            // 原逻辑用该标记去重同一次按键；已处理时交给原方法记录并忽略。
            if (HookSupport.getFieldValue(ext, "mSpeechLongPressHandled") == true) {
                return@intercept chain.proceed()
            }
            val pwm = HookSupport.getFieldValue(ext, "mBase")?.takeIf(::hasContext)
            if (pwm == null) {
                logger.warnThrottled("start_speech_missing_pwm") {
                    "startSpeech 未能解析 PhoneWindowManager，回退原逻辑"
                }
                return@intercept chain.proceed()
            }
            val source = "startSpeech(${chain.getArg(1)})"
            if (launchOrFallback(
                    logger = logger,
                    phoneWindowManager = pwm,
                    handler = HookSupport.getFieldValue(ext, "mAsynHandler") as? Handler,
                    source = source
                )) {
                runCatching { HookSupport.findField(ext.javaClass, "mSpeechLongPressHandled")?.setBoolean(ext, true) }
                null
            } else {
                chain.proceed()
            }
        }
    }

    private fun hasContext(target: Any): Boolean =
        HookSupport.getFieldValue(target, "mContext") is Context

    /** 返回 true 表示已由目标助理接管；false 时调用方必须执行原逻辑。 */
    private fun launchOrFallback(
        logger: ModuleLogger,
        phoneWindowManager: Any,
        handler: Handler?,
        source: String
    ): Boolean {
        val target = Prefs.powerAssistantTarget()
        val binding = assistantBindingFor(target) ?: return false
        return when (tryLaunchAssistant(
            target = target,
            binding = binding,
            logger = logger,
            phoneWindowManager = phoneWindowManager,
            source = source
        )) {
            LaunchResult.LAUNCHED -> true
            LaunchResult.ACTIVITY_FALLBACK_REQUIRED -> {
                val activityStarted = tryStartAssistantActivityFallback(
                    target = target,
                    binding = binding,
                    logger = logger,
                    phoneWindowManager = phoneWindowManager,
                    source = source
                )
                // Activity 兜底能处理本次触发，但仍需后台修复首选 voiceinteraction 路径。
                scheduleBackgroundRecovery(
                    handler = handler,
                    logger = logger,
                    phoneWindowManager = phoneWindowManager,
                    source = source
                )
                if (!activityStarted) {
                    logger.warnThrottled("${source}_native_fallback") {
                        "$source: ${binding.displayName} 启动失败，回退原逻辑"
                    }
                }
                // 当前触发不等待后台修复；所有快速路径失败后立即回退小布。
                activityStarted
            }
            LaunchResult.NOT_HANDLED -> false
        }
    }

    private fun hookOplusSpeechHandler(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val logger = hooks.logger
        val handlerClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.OP_LUS_SPEECH_HANDLER_CLASS)
        val handleMessageMethod = handlerClass?.let {
            HookSupport.findMethod(it, "handleMessage", Message::class.java)
        }
        if (handleMessageMethod == null) {
            hooks.missing(
                id = "system.power-assist-message",
                description = "OplusSpeechHandler.handleMessage",
                detail = "未找到 OplusSpeechHandler.handleMessage(Message)"
            )
            return
        }

        hooks.intercept(
            id = "system.power-assist-message",
            executable = handleMessageMethod,
            description = "PhoneWindowManagerExtImpl\$OplusSpeechHandler.handleMessage"
        ) { chain ->
            val message = chain.getArg(0) as? Message
            if (message?.what != ModuleConfig.OP_LUS_ASSIST_MESSAGE_WHAT) {
                return@intercept chain.proceed()
            }

            if (assistantBindingFor(Prefs.powerAssistantTarget()) == null) {
                return@intercept chain.proceed()
            }

            val handler = chain.getThisObject() as? Handler
            val pwm = resolvePhoneWindowManager(chain.getThisObject())
            if (pwm == null) {
                logger.warnThrottled("oplus_speech_missing_pwm") {
                    "OplusSpeechHandler 未能解析 PhoneWindowManager，回退原逻辑"
                }
                return@intercept chain.proceed()
            }

            if (launchOrFallback(logger, pwm, handler, "OplusSpeechHandler")) {
                null
            } else {
                nativeFallbackInProgress.set(true)
                try {
                    chain.proceed()
                } finally {
                    nativeFallbackInProgress.remove()
                }
            }
        }
    }

    private fun tryLaunchAssistant(
        target: PowerAssistantTarget,
        binding: AssistantBinding,
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String
    ): LaunchResult {
        val context = HookSupport.getFieldValue(phoneWindowManager, "mContext") as? Context
        if (context == null) {
            logger.warnThrottled("${source}_missing_context") {
                "$source 缺少 mContext，回退原逻辑"
            }
            return LaunchResult.NOT_HANDLED
        }

        if (!HookSupport.isPackageInstalled(context, binding.packageName)) {
            logger.warnThrottled("${source}_${target.persistedValue}_missing") {
                "$source: ${binding.displayName} 未安装，回退原逻辑"
            }
            return LaunchResult.NOT_HANDLED
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastInterceptUptime <= ModuleConfig.INTERCEPT_DEDUP_WINDOW_MS) {
            logger.debug { "$source: 命中去重窗口，直接吞掉重复触发" }
            return LaunchResult.LAUNCHED
        }

        // 目标为 Eta 且开关开启时直接进入主页面，不经过 voiceinteraction；
        // 失败只回退 OEM 原逻辑，不触发浮窗兜底或后台助理配置修复。
        if (EtaPowerMainActivity.shouldOpenMain(
                target = target,
                openMainEnabled = Prefs.isEnabled(Prefs.Keys.POWER_KEY_ETA_OPEN_MAIN)
            )
        ) {
            if (!EtaPowerMainActivity.launch(context)) {
                logger.warnThrottled("${source}_eta_main_failed") {
                    "$source: 打开 Eta 主页面失败，回退原逻辑"
                }
                return LaunchResult.NOT_HANDLED
            }
            finalizeSuccessfulLaunch(logger, phoneWindowManager, source, now)
            logger.debug { "$source: 已直接打开 Eta 主页面" }
            return LaunchResult.LAUNCHED
        }

        if (AssistantManager.showAssistantSession(
                context = context,
                target = target,
                logger = logger,
                source = source,
                logFailures = false
            )) {
            finalizeSuccessfulLaunch(logger, phoneWindowManager, source, now)
            logger.debug { "$source: 已通过 voiceinteraction 启动 ${binding.displayName}" }
            return LaunchResult.LAUNCHED
        }

        return LaunchResult.ACTIVITY_FALLBACK_REQUIRED
    }

    private fun tryStartAssistantActivityFallback(
        target: PowerAssistantTarget,
        binding: AssistantBinding,
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String
    ): Boolean {
        val context = HookSupport.getFieldValue(phoneWindowManager, "mContext") as? Context
            ?: return false
        val now = SystemClock.uptimeMillis()
        return when (target) {
            PowerAssistantTarget.OEM -> false
            PowerAssistantTarget.GEMINI -> startAssistantActivity(
                context = context,
                binding = binding,
                logger = logger,
                phoneWindowManager = phoneWindowManager,
                source = source,
                now = now,
                action = Intent.ACTION_ASSIST,
            ) || startAssistantActivity(
                context = context,
                binding = binding,
                logger = logger,
                phoneWindowManager = phoneWindowManager,
                source = source,
                now = now,
                action = Intent.ACTION_VOICE_COMMAND,
            )
            PowerAssistantTarget.ETA -> {
                if (!AssistantManager.isAssistantConfigured(context, target)) {
                    false
                } else {
                    startAssistantActivity(
                        context = context,
                        binding = binding,
                        logger = logger,
                        phoneWindowManager = phoneWindowManager,
                        source = source,
                        now = now,
                        action = Intent.ACTION_ASSIST,
                    )
                }
            }
        }
    }

    private fun startAssistantActivity(
        context: Context,
        binding: AssistantBinding,
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String,
        now: Long,
        action: String
    ): Boolean {
        val intent = Intent(action).apply {
            setPackage(binding.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolves = runCatching { HookSupport.resolvesActivity(context, intent) }
            .getOrElse { throwable ->
                logger.warnThrottled("${source}_${action}_resolve_failed") {
                    "$source: 查询 ${binding.displayName} $action 入口失败，" +
                        "type=${throwable.safeLogType()}"
                }
                false
            }
        if (!resolves) {
            logger.warnThrottled("${source}_${action}_missing") {
                "$source: ${binding.displayName} 未暴露 $action，回退原逻辑"
            }
            return false
        }

        return runCatching {
            context.startActivity(intent)
            finalizeSuccessfulLaunch(logger, phoneWindowManager, source, now)
            logger.debug { "$source: 已通过 $action 启动 ${binding.displayName}" }
            true
        }.getOrElse { throwable ->
            logger.warnThrottled("${source}_${action}_failed") {
                "$source: $action 启动失败，回退原逻辑，type=${throwable.safeLogType()}"
            }
            false
        }
    }

    private fun finalizeSuccessfulLaunch(
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String,
        now: Long
    ) {
        markLaunchSuccess(now)
        maybePerformAssistantHapticFeedback(logger, phoneWindowManager, source)
    }

    private fun maybePerformAssistantHapticFeedback(
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String
    ) {
        if (invokeOplusAssistantHapticFeedback(phoneWindowManager)) {
            logger.debug { "$source: 已补发 Oplus 原生助理震感" }
            return
        }

        logger.warnThrottled("${source}_assistant_haptic_missing") {
            "$source: 未找到 Oplus 原生长按助理震感入口"
        }
    }

    private fun invokeOplusAssistantHapticFeedback(phoneWindowManager: Any): Boolean {
        val wrapper = HookSupport.invokeNoArgs(phoneWindowManager, "getWrapper") ?: return false
        val wrapperMethod = HookSupport.findMethod(
            wrapper.javaClass,
            "performHapticFeedback",
            Int::class.javaPrimitiveType!!,
            String::class.java
        ) ?: return false
        return runCatching {
            wrapperMethod.invoke(wrapper, OEM_ASSISTANT_HAPTIC_EFFECT_ID, OEM_ASSISTANT_HAPTIC_REASON)
            true
        }.getOrDefault(false)
    }

    private fun scheduleBackgroundRecovery(
        handler: Handler?,
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String
    ) {
        if (!Prefs.isEnabled(Prefs.Keys.ASSISTANT_AUTO_CONFIG) ||
            Prefs.powerAssistantTarget() == PowerAssistantTarget.OEM
        ) {
            return
        }
        if (handler == null) {
            logger.warnThrottled("${source}_recovery_missing_handler") {
                "$source: 无法取得 OplusSpeechHandler 实例，跳过后台配置修复"
            }
            return
        }

        val context = HookSupport.getFieldValue(phoneWindowManager, "mContext") as? Context
        if (context == null) {
            logger.warnThrottled("${source}_recovery_missing_context") {
                "$source: 无法取得 mContext，跳过后台配置修复"
            }
            return
        }

        val scheduled = AssistantManager.scheduleAssistantRecovery(
            context = context,
            logger = logger,
            handler = handler,
            forceRefresh = true,
        )
        if (!scheduled) {
            logger.warnThrottled("${source}_configuration_schedule_failed") {
                "$source: 默认助理后台修复无法入队"
            }
        } else {
            logger.warnThrottled("${source}_assistant_recovery_pending") {
                "$source: voiceinteraction 失败，已在后台修复默认助理配置"
            }
        }
    }

    private fun markLaunchSuccess(now: Long) {
        lastInterceptUptime = now
    }

    private fun resolvePhoneWindowManager(handlerInstance: Any): Any? {
        val owner = HookSupport.getFieldValue(handlerInstance, "this$0") ?: return null
        HookSupport.findField(owner.javaClass, "mPhoneWindowManager")?.let { field ->
            return runCatching { field.get(owner) }.getOrNull()
        }

        var current: Class<*>? = owner.javaClass
        while (current != null) {
            current.declaredFields.forEach { field ->
                if (field.type.name == ModuleConfig.PHONE_WINDOW_MANAGER_CLASS) {
                    field.isAccessible = true
                    return runCatching { field.get(owner) }.getOrNull()
                }
            }
            current = current.superclass
        }
        return null
    }
}
