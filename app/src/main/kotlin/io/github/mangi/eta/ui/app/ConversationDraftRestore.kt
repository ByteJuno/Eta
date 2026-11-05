package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.AgentChatHomeUiState

// 历史来自数据库，未发送输入及其同步标记来自运行期状态，必须成组恢复。
internal fun AgentChatHomeUiState.withConversationDraftFrom(
    current: AgentChatHomeUiState,
): AgentChatHomeUiState = copy(
    input = current.input,
    inputSyncVersion = current.inputSyncVersion,
    focusInputOnSync = current.focusInputOnSync,
    pendingImages = current.pendingImages,
    pendingFileReferences = current.pendingFileReferences,
)

// 输入框已在发送回调前清空；校验拒绝时通过同步版本恢复原文，保留空白与附件。
internal fun AgentChatHomeUiState.withRejectedSubmission(submittedText: String?): AgentChatHomeUiState = copy(
    input = submittedText ?: input,
    inputSyncVersion = inputSyncVersion + 1,
    focusInputOnSync = true,
)
