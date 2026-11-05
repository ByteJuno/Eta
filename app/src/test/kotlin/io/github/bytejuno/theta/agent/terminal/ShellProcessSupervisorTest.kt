package io.github.bytejuno.theta.agent.terminal

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellProcessSupervisorTest {
    @Test
    fun missingSetsidFailsClosedWhenTreeFallbackIsDisabled() {
        val supervisor = ShellProcessSupervisor(
            allowTreeFallback = false,
            setsidCommand = "eta-test-missing-setsid",
        )

        val process = supervisor.startShellProcess(
            identity = "user",
            command = "echo should-not-run",
            mergeStderr = false,
        )

        assertNull(process)
    }

    @Test(timeout = 20_000)
    fun groupCleanupPrefersSingleToyboxListingAndKeepsProcFallbackAndExitStatus() {
        val launcher = ShellProcessSupervisor().buildTrackedShellLauncher(
            ownershipFile = File(System.getProperty("java.io.tmpdir"), "eta-cleanup-test.owner"),
            ownershipToken = "token123",
            command = "exit 7",
            identity = "user",
            environment = TerminalEnvironment.ANDROID,
            linuxRootfsPath = null,
        )

        // Fast path: one absolute-path toybox listing, checked before the per-process /proc loop.
        val fast = launcher.indexOf("eta_cleanup_toybox_group && eta_cleanup_toybox_group")
        val slow = launcher.indexOf("eta_cleanup_proc_group; eta_cleanup_proc_group")
        assertTrue(launcher.contains("/system/bin/toybox ps -A -o PID=,PGID="))
        assertTrue(fast in 0 until slow)
        // The /proc loop still runs whenever toybox is missing or fails, so no orphan is left behind.
        assertTrue(launcher.contains("then :; else eta_cleanup_proc_group; eta_cleanup_proc_group; fi"))
        // Non-/proc systems keep the ps/awk path, and the payload status is still returned.
        assertTrue(launcher.contains("else eta_cleanup_ps_group; eta_cleanup_ps_group; fi"))
        assertTrue(launcher.contains("eta_status=${'$'}?"))
        assertTrue(launcher.contains("exit ${'$'}eta_status"))
    }

    @Test(timeout = 20_000)
    fun toyboxCleanupKillsOnlyItsOwnGroupInARealShell() {
        val sh = File("/bin/sh").takeIf { it.canExecute() } ?: return
        // Extract just the cleanup functions and drive the toybox branch with a fake toybox that
        // reports one sibling in our group, one stranger, and ourselves.
        val launcher = ShellProcessSupervisor().buildTrackedShellLauncher(
            ownershipFile = File(System.getProperty("java.io.tmpdir"), "eta-cleanup-run.owner"),
            ownershipToken = "token123",
            command = "true",
            identity = "user",
            environment = TerminalEnvironment.ANDROID,
            linuxRootfsPath = null,
        )
        val function = Regex("eta_cleanup_toybox_group\\(\\) \\{.*?return 0; \\}").find(launcher)!!.value
        val dir = kotlin.io.path.createTempDirectory("eta-toybox").toFile()
        try {
            val fake = File(dir, "toybox").apply {
                writeText("#!/bin/sh\nprintf '%s %s\\n' \"${'$'}ETA_SELF\" \"${'$'}ETA_SELF\" 4242 \"${'$'}ETA_SELF\" 9999 1\n")
                setExecutable(true)
            }
            val script = function.replace("/system/bin/toybox", fake.absolutePath) +
                "; kill() { echo \"killed ${'$'}*\"; }; ETA_SELF=${'$'}${'$'}; export ETA_SELF; eta_cleanup_toybox_group; echo rc=${'$'}?"
            val process = ProcessBuilder(sh.absolutePath, "-c", script).redirectErrorStream(true).start()
            // Bound the run: a hung child must fail this test, not stall the whole CI job.
            assertTrue("cleanup script did not exit", process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText()

            assertTrue(output, output.contains("killed -9 4242"))
            assertFalse(output, output.contains("9999"))
            assertEquals(1, Regex("killed ").findAll(output).count())
            assertTrue(output, output.contains("rc=0"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rootAndroidPayloadUsesDiscoveredBusyBoxWithoutChangingUserShell() {
        val supervisor = ShellProcessSupervisor()

        val rootPayload = supervisor.buildAndroidPayload("root", "command -v xz")
        val userPayload = supervisor.buildAndroidPayload("user", "id")

        assertTrue(rootPayload.contains("/data/adb/magisk/busybox"))
        assertTrue(rootPayload.contains("ASH_STANDALONE=1"))
        assertEquals("sh -c 'id'", userPayload)
    }

    @Test
    fun linuxPayloadKeepsShellQuotesAndMountsPrivateExchangeDirectory() {
        val supervisor = ShellProcessSupervisor()

        val payload = supervisor.buildLinuxPayload(
            rootfsPath = "/data/user/0/io.github.bytejuno.theta/files/terminal/alpine/rootfs",
            command = "printf '%s' \"hello\"",
        )

        assertFalse(payload.contains("\\\""))
        assertTrue(payload.contains("unshare -m --propagation private"))
        assertTrue(payload.contains("mount -t proc"))
        assertTrue(payload.contains("eta_mount_required /data/local/tmp"))
        assertTrue(payload.contains("eta_mount_required /data/local/tmp/eta"))
        assertTrue(payload.contains("eta_rootfs/workspace"))
        assertTrue(payload.contains("chroot"))
        assertTrue(payload.contains(AlpineEnvironmentPaths.READY_MARKER))
        assertTrue(payload.contains("/bin/busybox env -i"))
        // Alpine 的 /bin/sh 是绝对符号链接，Android 侧就绪检查必须放行符号链接。
        assertTrue(payload.contains("[ -h \"\$eta_rootfs/bin/sh\" ]"))

        val debianPayload = supervisor.buildLinuxPayload(
            rootfsPath = "/data/user/0/io.github.bytejuno.theta/files/terminal/debian/rootfs",
            command = "python3 --version",
        )
        assertTrue(debianPayload.contains("/usr/bin/env -i"))
    }

    @Test
    fun ptyLauncherUsesNativePtyForEveryIdentity() {
        val executable = File("/data/app/eta/lib/arm64/libeta_pty.so")
        val supervisor = ShellProcessSupervisor(ptyExecutable = { executable })
        listOf("user", "root").forEach { identity ->
            val launcher = supervisor.buildTrackedShellLauncher(
                ownershipFile = File(System.getProperty("java.io.tmpdir"), "eta-pty-test.owner"),
                ownershipToken = "token123",
                command = null,
                identity = identity,
                environment = TerminalEnvironment.ANDROID,
                linuxRootfsPath = null,
                pty = true,
                ptyCols = 120,
                ptyRows = 40,
            )

            assertTrue(launcher.contains("exec '${executable.absolutePath}' 40 120 -- sh -c"))
            assertTrue(launcher.contains("TERM=xterm-256color"))
            assertTrue(launcher.contains(" group"))
            assertFalse(launcher.contains("script -qfc"))
        }
    }

    @Test(expected = IOException::class)
    fun ptyLauncherRejectsMissingNativePty() {
        ShellProcessSupervisor(ptyExecutable = { null }).buildTrackedShellLauncher(
            ownershipFile = File(System.getProperty("java.io.tmpdir"), "eta-pty-test.owner"),
            ownershipToken = "token123",
            command = null,
            identity = "user",
            environment = TerminalEnvironment.ANDROID,
            linuxRootfsPath = null,
            pty = true,
        )
    }

    @Test
    fun interactiveRootConsoleAvoidsBusyBoxAshWithSigwinchBug() {
        val supervisor = ShellProcessSupervisor()

        val payload = supervisor.buildAndroidPayload("root", command = null, interactivePty = true)

        assertTrue(payload.contains("*' v1.35.'*|*' v1.36.'*)"))
        assertTrue(payload.contains("--install -s '/data/local/tmp/eta/.busybox'"))
        assertTrue(payload.contains("export PATH=\"${'$'}PATH:\"'/data/local/tmp/eta/.busybox'; sh;;"))
        assertTrue(payload.contains("*) export ASH_STANDALONE=1; \"${'$'}eta_busybox\" ash ;;"))
        val syntax = ProcessBuilder("sh", "-n", "-c", payload).redirectErrorStream(true).start()
        val output = syntax.inputStream.bufferedReader().readText()
        assertEquals(output, 0, syntax.waitFor())
    }

    @Test
    fun nonInteractiveRootCommandsKeepBusyBoxAsh() {
        val payload = ShellProcessSupervisor().buildAndroidPayload("root", "id")

        assertFalse(payload.contains("v1.36."))
        assertTrue(payload.contains("ash -c 'id'"))
    }
}
