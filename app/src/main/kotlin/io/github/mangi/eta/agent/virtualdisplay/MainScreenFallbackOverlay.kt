package io.github.mangi.eta.agent.virtualdisplay

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.runtime.EtaMainVisibility
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 主屏操作授权的系统级确认弹窗：用户不在 Eta 内（在其他应用里）时，
 * 直接在当前界面上弹出，不必下拉通知。Eta 在前台时由应用内对话框负责，锁屏时不显示
 * （与通知按钮的解锁要求一致）。点弹窗外只收起，请求仍可在通知里处理，超时规则不变。
 */
internal object MainScreenFallbackOverlay {
    private val installed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: Context
    private var prompts: List<MainScreenFallbackApproval.Prompt> = emptyList()
    private var mainVisible = false
    /** 用户点弹窗外收起的请求；只影响弹窗，不改变决定。 */
    private val hidden = mutableSetOf<String>()
    private var shown: Shown? = null

    private class Shown(val token: String, val manager: WindowManager, val root: View)

    fun titleRes(): Int = R.string.main_screen_fallback_notification_title

    fun choices(): List<Pair<Int, MainScreenFallbackDecision>> = listOf(
        R.string.main_screen_fallback_allow to MainScreenFallbackDecision.ALLOWED,
        R.string.main_screen_fallback_deny to MainScreenFallbackDecision.DENIED,
    )

    fun dismissDecision(): MainScreenFallbackDecision = MainScreenFallbackDecision.DENIED

    fun available(context: Context): Boolean =
        AgentAccessibilityService.current() != null || Settings.canDrawOverlays(context)

    fun ensure(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        app = context.applicationContext
        scope.launch {
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) = render()
            }, IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_OFF)
            }, Context.RECEIVER_NOT_EXPORTED)
            combine(MainScreenFallbackApproval.state, EtaMainVisibility.flow) { list, visible -> list to visible }
                .collect { (list, visible) ->
                    prompts = list
                    mainVisible = visible
                    hidden.retainAll(list.map { it.token }.toSet())
                    render()
                }
        }
    }

    private fun render() {
        val prompt = prompts.firstOrNull { it.token !in hidden }
        val locked = app.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (prompt == null || mainVisible || locked) { remove(); return }
        if (shown?.token == prompt.token) return
        remove()
        runCatching { show(prompt) }
            .onFailure { AndroidAgentLogger.warn("Main screen fallback overlay failed: type=${it.safeLogType()}") }
    }

    private fun remove() {
        val current = shown ?: return
        shown = null
        runCatching { current.manager.removeViewImmediate(current.root) }
    }

    private fun show(prompt: MainScreenFallbackApproval.Prompt) {
        val service = AgentAccessibilityService.current()
        val type = if (service != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        if (service == null && !Settings.canDrawOverlays(app)) return
        // 无障碍窗口必须用服务自身的 context 创建，否则 BadTokenException。
        val context = service ?: app.createDisplayContext(
            app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        ).createWindowContext(type, null)
        val manager = context.getSystemService(WindowManager::class.java)
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val surface = if (dark) Color.rgb(0x26, 0x26, 0x29) else Color.WHITE
        val onSurface = if (dark) Color.rgb(0xEE, 0xEE, 0xF0) else Color.rgb(0x1C, 0x1C, 0x1E)
        val secondary = if (dark) Color.rgb(0xA8, 0xA8, 0xAE) else Color.rgb(0x6C, 0x6C, 0x70)
        val buttonFill = if (dark) Color.rgb(0x36, 0x36, 0x3A) else Color.rgb(0xF0, 0xF0, 0xF3)
        val accent = Color.rgb(0x31, 0x82, 0xF6)
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 24)
            setPadding(dp(24), dp(22), dp(24), dp(18))
            isClickable = true // 卡片内空白不当作「点弹窗外」
        }
        card.addView(TextView(context).apply {
            text = context.getString(titleRes())
            textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(onSurface)
        })
        card.addView(TextView(context).apply {
            text = prompt.reason; textSize = 14f; setTextColor(onSurface)
            setLineSpacing(0f, 1.15f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val choices = choices()
        choices.forEachIndexed { index, (label, decision) ->
            val primary = index == 0 // 首选项与应用内对话框顺序一致
            card.addView(TextView(context).apply {
                text = context.getString(label); textSize = 15f; gravity = Gravity.CENTER
                typeface = if (primary) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (primary) accent else onSurface)
                background = rounded(buttonFill, 14)
                // 被其他悬浮窗遮挡时不接受点击，防止诱导授权。
                filterTouchesWhenObscured = true
                setOnClickListener { decide(prompt.token, decision) }
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(if (index == 0) 18 else 8) })
        }
        card.addView(TextView(context).apply {
            text = context.getString(R.string.main_screen_fallback_overlay_hint)
            textSize = 12f; gravity = Gravity.CENTER; setTextColor(secondary)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(0x73, 0, 0, 0))
            setOnClickListener { hidden += prompt.token; render() }
        }
        val width = minOf(context.resources.displayMetrics.widthPixels - dp(40), dp(380))
        root.addView(card, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "Eta main screen fallback"
        }
        manager.addView(root, params)
        shown = Shown(prompt.token, manager, root)
    }

    private fun decide(token: String, decision: MainScreenFallbackDecision) {
        // 允许与通知按钮一样要求已解锁；锁屏时弹窗本就不显示，这里再兜一次。
        if (app.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        MainScreenFallbackApproval.resolve(token, decision)
        render()
    }
}
