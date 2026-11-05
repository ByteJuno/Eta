package io.github.mangi.eta.agent.automation

import android.content.Context
import io.github.mangi.eta.ui.IncomingShareRequest
import org.json.JSONObject

/**
 * 系统分享进入 Eta 时发布 shared_content 事件。分享本身仍只生成草稿、不自动发送；
 * 只有用户另外保存了 shared_content 自动任务，才会在后台按任务目标处理。
 */
internal object SharedContentTrigger {
    fun publish(context: Context, request: IncomingShareRequest) {
        val data = describe(request) ?: return
        AgentTaskScheduler.publish(context, "shared_content", System.currentTimeMillis().toString(), data)
    }

    /** 图片和文件只给类型与数量：内容 URI 的读取授权属于当前界面，后台任务拿不到。 */
    internal fun describe(request: IncomingShareRequest): JSONObject? {
        val text = request.text?.trim().orEmpty()
        val kind = when {
            request.uris.isNotEmpty() && isImage(request.mimeType) -> "image"
            request.uris.isNotEmpty() -> "file"
            text.isEmpty() -> return null
            LINK.matches(text) -> "link"
            else -> "text"
        }
        return JSONObject().put("kind", kind).apply {
            if (text.isNotEmpty()) put("text", text)
            if (request.uris.isNotEmpty()) put("count", request.uris.size)
            request.mimeType?.takeIf(String::isNotBlank)?.let { put("mimeType", it.take(100)) }
        }
    }

    private fun isImage(mimeType: String?): Boolean = mimeType?.startsWith("image/", ignoreCase = true) == true

    /** 整段只有一个链接（可带标题前缀，常见于浏览器分享）时视为 link。 */
    private val LINK = Regex("""(?s)^(?:[^\n]{0,200}\s)?https?://\S+$""")
}
