package io.github.mangi.eta.agent.display

import android.os.Binder
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import org.json.JSONObject
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VirtualScreenBridgeProviderTest {
    @Test
    fun modelArgumentsCannotForgeManualControlAuthority() {
        val args = JSONObject().put("action", "manual_control").put("manual", true)
            .put("controlGeneration", 100).put("enabled", false)
        val result = VirtualScreenSession.execute(RuntimeEnvironment.getApplication(), "owner", args)
        assertEquals("VIRTUAL_ACTION_UNSUPPORTED", JSONObject(result.content).getString("code"))
    }

    @Test
    fun rootNonceAndSessionAreAllRequiredAndRegistrationIsSingleUse() {
        val provider = VirtualScreenRootBridgeProvider()
        val pending = VirtualScreenRootBridgeProvider.expect("nonce", "session")
        val binder = Binder()
        val request =
            Bundle().apply {
                putString("nonce", "nonce")
                putString("sessionId", "session")
                putBinder("display", binder)
            }
        try {
            ShadowBinder.setCallingUid(2000)
            assertThrows(IllegalStateException::class.java) {
                provider.call("register", null, request)
            }
            assertFalse(pending.binder.isDone)
            ShadowBinder.setCallingUid(0)
            request.putString("sessionId", "different")
            assertThrows(IllegalStateException::class.java) {
                provider.call("register", null, request)
            }
            request.putString("sessionId", "session")
            request.putString("nonce", "unknown")
            assertThrows(IllegalStateException::class.java) {
                provider.call("register", null, request)
            }
            request.putString("nonce", "nonce")
            provider.call("register", null, request)
            assertSame(binder, pending.binder.get())
            assertThrows(IllegalStateException::class.java) {
                provider.call("register", null, request)
            }
        } finally {
            VirtualScreenRootBridgeProvider.forget("nonce")
            ShadowBinder.reset()
        }
    }
}
