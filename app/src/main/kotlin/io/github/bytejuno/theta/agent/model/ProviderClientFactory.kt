package io.github.bytejuno.theta.agent.model

import io.github.bytejuno.theta.data.model.ProviderTypes
import io.github.bytejuno.theta.data.model.OpenAiEndpointMode
import io.github.bytejuno.theta.data.provider.ProviderConnectionProfile

internal object ProviderClientFactory {

    fun getClient(config: AgentModelClient.ModelConfig): AgentProviderClient {
        ProviderConnectionProfile.forAuthMode(config.authMode)?.let { profile ->
            return when (profile.endpointMode) {
                OpenAiEndpointMode.RESPONSES -> OpenAiResponsesProvider
                else -> OpenAiChatCompletionsProvider
            }
        }
        return when (config.providerType) {
            ProviderTypes.OPENAI_COMPATIBLE -> when (config.openAiEndpointMode) {
                OpenAiEndpointMode.RESPONSES -> OpenAiResponsesProvider
                else -> OpenAiChatCompletionsProvider
            }
            ProviderTypes.ANTHROPIC -> AnthropicMessagesProvider
            ProviderTypes.GEMINI -> GeminiInteractionsProvider
            else -> error("不支持的 Provider 协议类型：${config.providerType}")
        }
    }
}
