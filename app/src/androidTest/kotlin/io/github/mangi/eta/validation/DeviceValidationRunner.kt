package io.github.mangi.eta.validation

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle

/** 保留与显示引擎无关的插话实机验证入口；旧虚拟屏模式已删除。 */
class DeviceValidationRunner : Instrumentation() {
    private var mode = "interjection"

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        mode = arguments?.getString("mode") ?: "interjection"
        start()
    }

    override fun onStart() {
        val output = Bundle()
        try {
            require(mode == "interjection") { "仅保留 interjection；旧显示引擎验证模式已删除" }
            var checks = 0
            DeviceInterjectionValidation(targetContext) { checks++ }.run()
            output.putInt("checks", checks)
            finish(Activity.RESULT_OK, output)
        } catch (failure: Throwable) {
            output.putString("error_type", failure.javaClass.simpleName)
            finish(Activity.RESULT_CANCELED, output)
        }
    }
}
