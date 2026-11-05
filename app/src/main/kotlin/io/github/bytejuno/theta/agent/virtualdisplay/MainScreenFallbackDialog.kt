package io.github.bytejuno.theta.agent.virtualdisplay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bytejuno.theta.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** Eta 在前台时的主屏操作授权确认；离开 Eta 时由 [MainScreenFallbackOverlay] 接管。 */
@Composable
internal fun MainScreenFallbackDialog() {
    val requests by MainScreenFallbackApproval.state.collectAsState()
    val request = requests.firstOrNull() ?: return
    fun decide(decision: MainScreenFallbackDecision) = MainScreenFallbackApproval.resolve(request.token, decision)
    val choices = MainScreenFallbackOverlay.choices()
    WindowDialog(show = true, title = stringResource(MainScreenFallbackOverlay.titleRes()),
        onDismissRequest = { decide(MainScreenFallbackOverlay.dismissDecision()) }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(request.reason, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurface)
            choices.forEach { (label, decision) ->
                TextButton(text = stringResource(label), modifier = Modifier.fillMaxWidth(),
                    onClick = { decide(decision) })
            }
        }
    }
}
