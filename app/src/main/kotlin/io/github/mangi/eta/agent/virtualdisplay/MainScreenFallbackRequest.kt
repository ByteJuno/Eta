package io.github.mangi.eta.agent.virtualdisplay

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal enum class MainScreenFallbackDecision { PENDING, ALLOWED, DENIED, CANCELLED, DISABLED, TIMEOUT, UNAVAILABLE }

/** 过期、取消或旧轮次的通知不能授权后续运行。 */
internal class MainScreenFallbackRequest(
    val owner: String,
    timeoutMs: Long = 180_000,
) {
    val token: String = UUID.randomUUID().toString()
    private val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    private val decision = AtomicReference(MainScreenFallbackDecision.PENDING)
    private val ready = CountDownLatch(1)

    fun resolve(value: MainScreenFallbackDecision): Boolean {
        require(value != MainScreenFallbackDecision.PENDING)
        val actual =
            if (System.nanoTime() >= deadline) MainScreenFallbackDecision.TIMEOUT else value
        val changed = decision.compareAndSet(MainScreenFallbackDecision.PENDING, actual)
        if (changed) ready.countDown()
        return changed && actual == value
    }

    fun await(isCancelled: () -> Boolean, stillAllowed: () -> Boolean): MainScreenFallbackDecision {
        try {
            while (true) {
                if (isCancelled()) {
                    resolve(MainScreenFallbackDecision.CANCELLED); return MainScreenFallbackDecision.CANCELLED
                }
                if (!stillAllowed()) {
                    resolve(MainScreenFallbackDecision.DISABLED); return MainScreenFallbackDecision.DISABLED
                }
                if (System.nanoTime() >= deadline) {
                    resolve(MainScreenFallbackDecision.TIMEOUT); return MainScreenFallbackDecision.TIMEOUT
                }
                decision.get().takeIf { it != MainScreenFallbackDecision.PENDING }
                    ?.let { return it }
                ready.await(100, TimeUnit.MILLISECONDS)
            }
        } catch (_: InterruptedException) {
            resolve(MainScreenFallbackDecision.CANCELLED)
            Thread.currentThread().interrupt()
            return MainScreenFallbackDecision.CANCELLED
        }
    }
}

