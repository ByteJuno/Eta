package io.github.mangi.eta.agent.runtime

import android.os.Messenger
import io.github.mangi.eta.agent.model.AgentModelClient

internal fun runtimeTestRequest(runId: String, conversationId: String = runId) = AgentRuntimeWire.RunRequest(
    runId = runId,
    modelSessionId = conversationId,
    prompt = "test",
    config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1", apiKey = "test-key", model = "test-model", systemPrompt = "",
    ),
    images = emptyList(),
)

@Suppress("UNCHECKED_CAST")
internal fun AgentRuntimeService.testRegistry() =
    readRuntimeField("runs") as AgentRuntimeRunRegistry<Any, Any>

internal fun AgentRuntimeService.registerTestRun(
    session: AgentRuntimeSession,
    conversationId: String = session.runId,
    selected: Boolean = true,
) {
    val request = runtimeTestRequest(session.runId, conversationId)
    val pending = registerTestPending(request)
    val run = RuntimeRun(request, session)
    check(testRegistry().promote(session.runId, pending, run))
    if (selected) setRuntimeField("activeSession", session)
}

internal fun AgentRuntimeService.registerTestPending(request: AgentRuntimeWire.RunRequest, replyTo: Messenger? = null): Any {
    val pending = PendingStartRequest(AgentRuntimeWire.IncomingRunRequest(request, emptyList()), replyTo)
    check(testRegistry().reserve(request.runId, request.effectiveModelSessionId, pending) ==
        AgentRuntimeRunRegistry.Admission.ACCEPTED)
    return pending
}

internal fun AgentRuntimeService.setRuntimeField(name: String, value: Any?) {
    AgentRuntimeService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
}

internal fun AgentRuntimeService.readRuntimeField(name: String): Any? =
    AgentRuntimeService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this)

internal fun AgentRuntimeService.callRuntime(name: String, vararg arguments: Any?): Any? =
    AgentRuntimeService::class.java.declaredMethods.single { it.name == name && it.parameterCount == arguments.size }
        .apply { isAccessible = true }.invoke(this, *arguments)
