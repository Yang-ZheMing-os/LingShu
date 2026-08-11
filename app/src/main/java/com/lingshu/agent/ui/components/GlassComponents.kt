package com.lingshu.agent.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lingshu.agent.ui.theme.*

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    borderWidth: Dp = 1.dp,
    glowColor: Color = AccentGlow,
    glowAlpha: Float = 0.15f,
    strong: Boolean = false,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val glassColors = LocalGlassColors.current
    val bgColor = if (strong) glassColors.glassBubbleStrong else glassColors.glassBubble

    Box(
        modifier = modifier
            .shadow(
                spotColor = glowColor.copy(alpha = glowAlpha),
                ambientColor = glowColor.copy(alpha = glowAlpha * 0.5f),
                elevation = 8.dp,
                shape = shape
            )
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .blur(40.dp, BlurredEdgeTreatment.Unbounded)
                .background(
                    glowColor.copy(alpha = glowAlpha * 0.3f),
                    shape
                )
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(bgColor, shape)
                .border(
                    width = borderWidth,
                    color = glassColors.glassBorder,
                    shape = shape
                )
                .padding(padding),
            content = content
        )
    }
}

@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    icon: ImageVector? = null,
    iconTint: Color = TextPrimary,
    text: String? = null,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
    gradient: Brush = Brush.horizontalGradient(
        colors = listOf(IceBlueGradientStart, IceBlueGradientMid, IceBlueGradientEnd)
    ),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {
    val isPressed by interactionSource.collectIsPressedAsState()
    val animatedScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = tween(100),
        label = "scale"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .shadow(
                elevation = if (enabled) 12.dp else 4.dp,
                spotColor = AccentGlow.copy(alpha = if (enabled) 0.3f else 0.1f),
                ambientColor = AccentGlow.copy(alpha = if (enabled) 0.15f else 0.05f),
                shape = shape
            )
            .clip(shape)
            .background(
                if (enabled) gradient else Brush.horizontalGradient(
                    listOf(GlassBubbleStrong, GlassBubble)
                ),
                shape
            )
            .border(
                width = 1.dp,
                color = if (enabled) GlassBubbleBorder else GlassBubbleBorder.copy(alpha = 0.5f),
                shape = shape
            )
            .then(
                if (enabled) Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                ) else Modifier
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = if (enabled) iconTint else TextDisabled,
                    modifier = Modifier.size(18.dp)
                )
            }
            text?.let {
                Text(
                    text = it,
                    style = textStyle,
                    color = if (enabled) TextPrimary else TextDisabled
                )
            }
        }
    }
}

@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector,
    iconModifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    size: Dp = 48.dp,
    iconTint: Color = TextPrimary,
    backgroundColor: Color = GlassBubble,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {
    val isPressed by interactionSource.collectIsPressedAsState()
    val animatedScale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = tween(100),
        label = "scale"
    )

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .clip(shape)
            .background(
                if (enabled) backgroundColor else GlassBubble,
                shape
            )
            .border(1.dp, GlassBubbleBorder, shape)
            .then(
                if (enabled) Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                ) else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = iconModifier,
            tint = if (enabled) iconTint else TextDisabled
        )
    }
}

@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    thumbColorActive: Color = AccentGlow,
    thumbColorInactive: Color = TextTertiary,
    trackColorActive: Color = AccentPrimary.copy(alpha = 0.5f),
    trackColorInactive: Color = GlassBubbleStrong
) {
    val animatedThumbColor by animateColorAsState(
        targetValue = if (checked) thumbColorActive else thumbColorInactive,
        label = "thumbColor"
    )
    val animatedTrackColor by animateColorAsState(
        targetValue = if (checked) trackColorActive else trackColorInactive,
        label = "trackColor"
    )

    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = animatedThumbColor,
            uncheckedThumbColor = animatedThumbColor,
            checkedTrackColor = animatedTrackColor,
            uncheckedTrackColor = animatedTrackColor,
            checkedBorderColor = GlassBubbleBorder,
            uncheckedBorderColor = GlassBubbleBorder
        )
    )
}

@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    activeTrackColor: Color = AccentPrimary,
    inactiveTrackColor: Color = GlassBubbleStrong,
    thumbColor: Color = AccentGlow,
    showGradient: Boolean = true
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        colors = SliderDefaults.colors(
            thumbColor = thumbColor,
            activeTrackColor = activeTrackColor,
            inactiveTrackColor = inactiveTrackColor,
            activeTickColor = AccentGlow,
            inactiveTickColor = GlassBubbleBorder
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary),
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    shape: Shape = RoundedCornerShape(12.dp)
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = label?.let {
            { Text(it, style = MaterialTheme.typography.bodyMedium, color = TextSecondary) }
        },
        placeholder = placeholder?.let {
            { Text(it, style = MaterialTheme.typography.bodyLarge, color = TextTertiary) }
        },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = interactionSource,
        shape = shape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            disabledTextColor = TextDisabled,
            errorTextColor = Error,
            focusedContainerColor = GlassBubble,
            unfocusedContainerColor = GlassBubble,
            disabledContainerColor = GlassBubble,
            errorContainerColor = GlassBubble,
            cursorColor = AccentGlow,
            errorCursorColor = Error,
            focusedBorderColor = AccentGlow,
            unfocusedBorderColor = GlassBubbleBorder,
            disabledBorderColor = GlassBubbleBorder.copy(alpha = 0.5f),
            errorBorderColor = Error,
            focusedLeadingIconColor = AccentGlow,
            unfocusedLeadingIconColor = TextSecondary,
            disabledLeadingIconColor = TextDisabled,
            errorLeadingIconColor = Error,
            focusedTrailingIconColor = AccentGlow,
            unfocusedTrailingIconColor = TextSecondary,
            disabledTrailingIconColor = TextDisabled,
            errorTrailingIconColor = Error,
            focusedLabelColor = AccentGlow,
            unfocusedLabelColor = TextSecondary,
            disabledLabelColor = TextDisabled,
            errorLabelColor = Error,
            focusedPlaceholderColor = TextTertiary,
            unfocusedPlaceholderColor = TextTertiary
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    TopAppBar(
        title = title,
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = GlassBubble,
            scrolledContainerColor = GlassBubbleStrong,
            navigationIconContentColor = TextPrimary,
            titleContentColor = TextPrimary,
            actionIconContentColor = TextPrimary
        )
    )
}

@Composable
fun GlassChip(
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(8.dp),
    selectedColor: Color = AccentPrimary.copy(alpha = 0.6f),
    unselectedColor: Color = GlassBubble
) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) selectedColor else unselectedColor,
        label = "chipBg"
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(bgColor, shape)
            .border(
                width = 1.dp,
                color = if (selected) AccentGlow else GlassBubbleBorder,
                shape = shape
            )
            .then(
                if (enabled) Modifier.clickable(onClick = onClick) else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) TextPrimary else TextSecondary
        )
    }
}

@Composable
fun GradientProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
    gradient: Brush = Brush.horizontalGradient(
        colors = listOf(IceBlueGradientStart, IceBlueGradientMid, IceBlueGradientEnd)
    ),
    backgroundColor: Color = GlassBubbleStrong
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(backgroundColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(height / 2))
                .background(gradient)
        )
    }
}

@Composable
fun GlassDivider(
    modifier: Modifier = Modifier,
    color: Color = GlassBubbleBorder,
    thickness: Dp = 1.dp
) {
    Divider(
        modifier = modifier,
        color = color,
        thickness = thickness
    )
}

@Composable
fun GlassSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    showGlow: Boolean = true
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (showGlow) {
            Box(
                modifier = Modifier
                    .size(4.dp, 20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(AccentGlow, AccentPrimary)
                        )
                    )
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> GlassExposedDropdownMenuBox(
    value: String,
    onValueSelected: (T) -> Unit,
    options: List<Pair<T, String>>,
    label: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
    ) {
        GlassTextField(
            value = value,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            readOnly = true,
            label = label,
            enabled = enabled,
            trailingIcon = {
                Text(
                    text = if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            Modifier
                .fillMaxWidth()
                .background(SecondaryBackground)
        ) {
            options.forEach { (option, display) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = display,
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary
                        )
                    },
                    onClick = {
                        onValueSelected(option)
                        expanded = false
                    }
                )
            }
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .alpha(0f)
                .clickable(enabled = enabled) { expanded = true }
        )
    }
}

@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(24.dp)
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = GlassBubbleStrong,
        iconContentColor = AccentGlow,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        tonalElevation = 0.dp
    )
}
