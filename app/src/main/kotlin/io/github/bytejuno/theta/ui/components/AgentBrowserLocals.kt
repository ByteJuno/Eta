package io.github.bytejuno.theta.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.bytejuno.theta.agent.browser.AgentBrowserSession

internal val LocalAgentBrowserSession = staticCompositionLocalOf<AgentBrowserSession?> { null }
