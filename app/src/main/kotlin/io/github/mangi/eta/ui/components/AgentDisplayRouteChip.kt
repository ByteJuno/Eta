package io.github.mangi.eta.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.virtualdisplay.ConversationDisplayChoice
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

/** 会话的屏幕选择；只在启用虚拟屏后提供，否则所有操作本来就在主屏。 */
@Immutable
internal data class DisplayRouteChoice(
    val primary: Boolean,
    val onChange: (Boolean) -> Unit,
)

internal val LocalDisplayRouteChoice = staticCompositionLocalOf<DisplayRouteChoice?> { null }

/** 与思考强度一致：单一图标，选了主屏时以主题色提示。 */
@Composable
internal fun DisplayRouteChip(
    choice: DisplayRouteChoice,
    enabled: Boolean,
    popupAnchorTopPx: Int,
    popupMaxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    var showPopup by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) {
        if (!enabled) showPopup = false
    }
    val popupPositionProvider = remember(popupAnchorTopPx) {
        InputPopupPositionProvider(popupAnchorTopPx)
    }
    val contentColor by animateColorAsState(
        targetValue = if (choice.primary) {
            MiuixTheme.colorScheme.primary
        } else {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        },
        animationSpec = tween(durationMillis = 160),
        label = "display_route_content",
    )
    val options = ConversationDisplayChoice.entries.map { route ->
        route.usesPrimary to stringResource(
            if (route.usesPrimary) R.string.chat_display_primary else R.string.chat_display_virtual,
        )
    }
    val current = options.first { it.first == choice.primary }.second
    Box(modifier = modifier) {
        IconButton(
            onClick = { showPopup = true },
            enabled = enabled,
            minWidth = ChatInputActionSize,
            minHeight = ChatInputActionSize,
        ) {
            Icon(
                imageVector = if (choice.primary) Icons.Rounded.Smartphone else Icons.Rounded.Layers,
                contentDescription = stringResource(R.string.chat_display_route, current),
                modifier = Modifier.size(21.dp),
                tint = contentColor,
            )
        }
        WindowListPopup(
            show = showPopup && enabled && popupAnchorTopPx > 0,
            popupPositionProvider = popupPositionProvider,
            alignment = PopupPositionProvider.Align.TopStart,
            onDismissRequest = { showPopup = false },
            maxHeight = popupMaxHeight,
        ) {
            val dismiss = LocalDismissState.current
            ListPopupColumn {
                options.forEachIndexed { index, (primary, label) ->
                    DropdownImpl(
                        text = label,
                        optionSize = options.size,
                        isSelected = primary == choice.primary,
                        index = index,
                        onSelectedIndexChange = {
                            choice.onChange(primary)
                            dismiss?.invoke()
                        },
                    )
                }
            }
        }
    }
}
