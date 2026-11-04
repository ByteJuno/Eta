package io.github.mangi.eta.agent.terminal

import org.json.JSONObject

/**
 * terminal 原始结果中针对退出码 127 的提示。
 *
 * 127 只说明 shell 没能执行这条命令（命令不存在、不可执行，或环境本身未就绪），
 * 单次采样无法确认具体原因。因此提示只描述常见成因与排查方向，不断言缺失的是哪个工具，
 * 也不触发重跑或切换环境。提示只由退出码与环境类型决定，不含命令文本、路径等敏感内容。
 */
internal object TerminalExitHint {
    const val COMMAND_NOT_FOUND_EXIT_CODE = 127

    /** 提示保持单行、有界，避免进入每步上下文后淹没真正的 stdout/stderr。 */
    const val MAX_HINT_CHARS = 300

    private const val ANDROID_ENVIRONMENT_HINT =
        "退出码 127 通常表示 shell 无法执行该命令，常见原因是命令不存在或没有执行权限。" +
            "Android 宿主一般只带 toybox 与系统命令，Python、Node.js、git、jq 等开发工具可能未安装；" +
            "这类工具需要在已安装它们的 Linux 环境中使用（environment=linux）。" +
            "本次结果未自动重试，也不会自动切换环境；如需在 Linux 环境运行，请显式指定并确认工具已安装。"

    private const val LINUX_ENVIRONMENT_HINT =
        "退出码 127 通常表示命令不存在、未安装或不在 PATH 中，也可能与 Linux 环境未就绪有关" +
            "（例如 busybox 或 rootfs 缺失时会以 127 退出）。" +
            "请先确认该工具是否已安装、PATH 是否正确，必要时在 Linux 环境中完成安装。" +
            "本次结果未自动重试，也不会自动切换环境。"

    /** 非 127（含成功、普通失败、超时 -2、启动失败 -1 等）不产生提示。 */
    fun forExitCode(exitCode: Int, environment: TerminalEnvironment): String? = when {
        exitCode != COMMAND_NOT_FOUND_EXIT_CODE -> null
        environment.isLinux -> LINUX_ENVIRONMENT_HINT
        else -> ANDROID_ENVIRONMENT_HINT
    }

    /**
     * 把提示写入 terminal 结果。只会新增 hint 字段，
     * 已在结果中的 ok、exit_code、stdout 等字段保持原值。
     */
    fun attachTo(result: JSONObject, exitCode: Int, environment: TerminalEnvironment): JSONObject {
        forExitCode(exitCode, environment)?.let { hint ->
            result.put("hint", hint.take(MAX_HINT_CHARS))
        }
        return result
    }
}
