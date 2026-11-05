package io.github.bytejuno.theta.agent.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTerminalResultPolicyTest {
    @Test fun completedVirtualRunShowsResultWithoutForegroundTool() {
        assertTrue(AgentTerminalResultPolicy.showVirtualResult(true, false, false, false))
    }
    @Test fun automationMustNotOpenMainScreenOverlay() {
        assertFalse(AgentTerminalResultPolicy.showVirtualResult(true, true, false, false))
    }
    @Test fun stoppedAndPausedRunsDoNotRevealResult() {
        assertFalse(AgentTerminalResultPolicy.showVirtualResult(true, false, true, false))
        assertFalse(AgentTerminalResultPolicy.showVirtualResult(true, false, false, true))
    }
    @Test fun ordinaryRunsKeepTheirExistingPolicy() {
        assertFalse(AgentTerminalResultPolicy.showVirtualResult(false, false, false, false))
    }
}
