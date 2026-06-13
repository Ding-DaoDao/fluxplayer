package com.fluxplayer.app.core.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.CircularProgressIndicator as M3CircularProgressIndicator
import androidx.compose.material3.Checkbox as M3Checkbox
import androidx.compose.material3.LinearProgressIndicator as M3LinearProgressIndicator
import androidx.compose.material3.Slider as M3Slider
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.Switch as M3Switch
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.Card as M3Card
import androidx.compose.material3.RadioButton as M3RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.state.ToggleableState
import com.fluxplayer.app.core.model.ComposeEngine
import com.fluxplayer.app.core.ui.theme.FluxTheme
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Checkbox as MiuixCheckbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator as MiuixCircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator as MiuixLinearProgressIndicator
import top.yukonga.miuix.kmp.basic.RadioButton as MiuixRadioButton
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.basic.Text as MiuixText

@Composable
fun FluxText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: androidx.compose.ui.text.TextStyle = androidx.compose.ui.text.TextStyle.Default,
) {
    when (FluxTheme.engine) {
        ComposeEngine.MIUIX -> MiuixText(text = text, modifier = modifier, color = color, style = style)
        ComposeEngine.MATERIAL -> M3Text(text = text, modifier = modifier, color = color, style = style)
    }
}

@Composable
fun FluxIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    when (FluxTheme.engine) {
        ComposeEngine.MIUIX -> MiuixIcon(imageVector = imageVector, contentDescription = contentDescription, modifier = modifier, tint = tint)
        ComposeEngine.MATERIAL -> M3Icon(imageVector = imageVector, contentDescription = contentDescription, modifier = modifier, tint = tint)
    }
}

@Composable
fun FluxSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    when (FluxTheme.engine) {
        ComposeEngine.MIUIX -> MiuixSwitch(
            checked = checked,
            onCheckedChange = { onCheckedChange?.invoke(it) },
            modifier = modifier,
            enabled = enabled,
        )
        ComposeEngine.MATERIAL -> M3Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
        )
    }
}

@Composable
fun FluxCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    when (FluxTheme.engine) {
        ComposeEngine.MIUIX -> MiuixCheckbox(
            state = if (checked) ToggleableState.On else ToggleableState.Off,
            onClick = { onCheckedChange?.invoke(!checked) },
            modifier = modifier,
            enabled = enabled,
        )
        ComposeEngine.MATERIAL -> M3Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
        )
    }
}

@Composable
fun FluxRadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    when (FluxTheme.engine) {
        ComposeEngine.MIUIX -> MiuixRadioButton(selected = selected, onClick = { onClick?.invoke() }, modifier = modifier, enabled = enabled)
        ComposeEngine.MATERIAL -> M3RadioButton(selected = selected, onClick = onClick, modifier = modifier, enabled = enabled)
    }
}

@Composable
fun FluxButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    when (FluxTheme.engine) {
        ComposeEngine.MIUIX -> MiuixButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
        ComposeEngine.MATERIAL -> M3Button(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    }
}

@Composable
fun FluxIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    when (FluxTheme.engine) {
        ComposeEngine.MIUIX -> MiuixIconButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
        ComposeEngine.MATERIAL -> M3IconButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    }
}

@Composable
fun FluxCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    when (FluxTheme.engine) {
        ComposeEngine.MIUIX -> MiuixCard(modifier = modifier) { content() }
        ComposeEngine.MATERIAL -> M3Card(modifier = modifier) { content() }
    }
}

@Composable
fun FluxSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    when (FluxTheme.engine) {
        ComposeEngine.MIUIX -> MiuixSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
        )
        ComposeEngine.MATERIAL -> M3Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}

@Composable
fun FluxCircularProgressIndicator(
    modifier: Modifier = Modifier,
) {
    when (FluxTheme.engine) {
        ComposeEngine.MIUIX -> MiuixCircularProgressIndicator(modifier = modifier)
        ComposeEngine.MATERIAL -> M3CircularProgressIndicator(modifier = modifier)
    }
}

@Composable
fun FluxLinearProgressIndicator(
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    when (FluxTheme.engine) {
        ComposeEngine.MIUIX -> MiuixLinearProgressIndicator(
            modifier = modifier,
            progress = progress,
        )
        ComposeEngine.MATERIAL -> {
            if (progress != null) {
                M3LinearProgressIndicator(progress = { progress }, modifier = modifier)
            } else {
                M3LinearProgressIndicator(modifier = modifier)
            }
        }
    }
}
