package io.github.bytejuno.theta.ui.screens.extensions

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bytejuno.theta.R
import io.github.bytejuno.theta.ui.components.MiuixScaffoldPage
import io.github.bytejuno.theta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Navigation only: each capability keeps its existing permission and lifecycle model. */
@Composable
internal fun ExtensionsScreen(onNavigate: (AppRoute) -> Unit, onBack: () -> Unit) {
    MiuixScaffoldPage(title = stringResource(R.string.route_extensions), onBack = onBack) {
        item(key = "categories") {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.extensions_builtin),
                    summary = stringResource(R.string.extensions_builtin_summary),
                    onClick = { onNavigate(AppRoute.Tools) },
                )
                ArrowPreference(
                    title = stringResource(R.string.extensions_services),
                    summary = stringResource(R.string.extensions_services_summary),
                    onClick = { onNavigate(AppRoute.McpServers) },
                )
                ArrowPreference(
                    title = stringResource(R.string.extensions_skills),
                    summary = stringResource(R.string.extensions_skills_summary),
                    onClick = { onNavigate(AppRoute.Skills) },
                )
            }
            io.github.bytejuno.theta.ui.components.ExtensionOperationHistory()
            io.github.bytejuno.theta.ui.components.RecentPluginCalls()
            Text(
                text = stringResource(R.string.extensions_scope),
                style = MiuixTheme.textStyles.footnote2,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp),
            )
        }
    }
}
