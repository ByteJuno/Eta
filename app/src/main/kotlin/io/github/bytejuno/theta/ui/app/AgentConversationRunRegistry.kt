package io.github.bytejuno.theta.ui.app

/** UI-thread-owned execution slots: conversations run independently, each conversation stays serial. */
internal class AgentConversationRunRegistry {
    private val conversationsByRun = mutableMapOf<String, String>()

    val runIds: Set<String> get() = conversationsByRun.keys.toSet()
    val isNotEmpty: Boolean get() = conversationsByRun.isNotEmpty()

    fun contains(runId: String): Boolean = runId in conversationsByRun

    fun runIdForConversation(conversationId: String?): String? =
        conversationId?.let { id -> conversationsByRun.entries.firstOrNull { it.value == id }?.key }

    fun register(runId: String, conversationId: String): Boolean {
        if (runId in conversationsByRun || runIdForConversation(conversationId) != null) return false
        conversationsByRun[runId] = conversationId
        return true
    }

    fun finish(runId: String): Boolean = conversationsByRun.remove(runId) != null
}
