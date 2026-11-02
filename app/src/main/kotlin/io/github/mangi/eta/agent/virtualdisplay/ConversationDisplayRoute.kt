package io.github.mangi.eta.agent.virtualdisplay

import android.content.Context

/**
 * 会话级屏幕选择：选了主屏的会话，其 UI 操作直接在主屏执行，不进虚拟屏也不再申请回退。
 * 默认（未记录）跟随虚拟屏设置。单独存 SharedPreferences，避免为一个开关升级 Room 版本；
 * 界面与 Runtime 同进程，apply 后立即可读。
 */
internal object ConversationDisplayRoute {
    private const val PREFS = "eta_conversation_display_route"
    private const val PRIMARY = "primary"

    fun usesPrimary(context: Context, conversationId: String?): Boolean =
        !conversationId.isNullOrBlank() && ConversationDisplayChoice.fromStored(prefs(context).getString(conversationId, null)).usesPrimary

    fun set(context: Context, conversationId: String, primary: Boolean) {
        if (conversationId.isBlank() || usesPrimary(context, conversationId) == primary) return
        prefs(context).edit().apply {
            if (primary) putString(conversationId, PRIMARY) else remove(conversationId)
        }.apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
