package com.msst.net.ui.components

// Small building blocks of the light-blue minimal design, shared by every
// screen (the Compose counterpart of the Svelte frontend's app.css).

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.msst.net.platform.PlatformServices
import com.msst.net.state.Banner
import com.msst.net.state.BannerKind
import com.msst.net.ui.LocalCompact
import com.msst.net.ui.MdiIcons
import com.msst.net.ui.theme.MonoFamily
import com.msst.net.ui.theme.Msst
import com.msst.net.ui.theme.MsstColors
import com.msst.net.ui.theme.Radius
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Icons and brand ──────────────────────────────────────────────────────────

@Composable
fun MIcon(icon: ImageVector, size: Dp = 18.dp, tint: Color = Color.Unspecified, contentDescription: String? = null) {
    Icon(icon, contentDescription, Modifier.size(size), tint = if (tint == Color.Unspecified) androidx.compose.material3.LocalContentColor.current else tint)
}

/** Brand mark (same drawing as the Svelte `Logo`). */
@Composable
fun Logo(size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) { drawLogo() }
}

/** The brand mark as a [Painter] (window and tray icons). */
object LogoPainter : Painter() {
    override val intrinsicSize = Size(64f, 64f)
    override fun DrawScope.onDraw() = drawLogo()
}

private fun DrawScope.drawLogo() {
    val c = MsstColors()
    val s = size.width / 64f
    drawCircle(c.primaryLighter, radius = 30 * s)
    drawCircle(c.primary, radius = 30 * s, style = Stroke(3 * s))
    val p = Path().apply {
        moveTo(16 * s, 32 * s)
        quadraticTo(24 * s, 20 * s, 32 * s, 32 * s)
        quadraticTo(40 * s, 44 * s, 48 * s, 32 * s)
    }
    drawPath(p, c.primaryDark, style = Stroke(3 * s, cap = StrokeCap.Round))
    drawCircle(c.primaryDark, radius = 4 * s, center = Offset(32 * s, 32 * s))
}

// ── Surfaces ─────────────────────────────────────────────────────────────────

/** White card with a light border (`.settings-card`, `.mode-group`). */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    padding: PaddingValues? = null,
    border: Color = Msst.colors.borderLight,
    background: Color = Msst.colors.bgPrimary,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(Radius.lg)
            .background(background)
            .border(1.dp, border, Radius.lg)
            .padding(padding ?: if (LocalCompact.current) PaddingValues(14.dp) else PaddingValues(horizontal = 20.dp, vertical = 16.dp)),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun SectionHeader(title: String, hint: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Msst.colors.textPrimary)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = Msst.colors.textMuted)
    }
}

@Composable
fun Divider() = HorizontalDivider(color = Msst.colors.borderLight)

/** Light-blue notice row with an icon (`.mode-group-notice`). */
@Composable
fun Notice(text: String, icon: ImageVector = MdiIcons.InformationOutline) {
    Row(
        Modifier.fillMaxWidth().clip(Radius.sm).background(Msst.colors.primaryLighter).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MIcon(icon, 14.dp, Msst.colors.primaryDark)
        Text(text, style = MaterialTheme.typography.labelSmall, color = Msst.colors.primaryDark)
    }
}

/** Core connection / sync banner under the navigation bar. */
@Composable
fun BannerBar(banner: Banner) {
    val c = Msst.colors
    val (bg, fg) = when (banner.kind) {
        BannerKind.Info -> c.primaryLighter to c.primaryDark
        BannerKind.Warn -> c.warningLight to c.warning
        BannerKind.Error -> c.errorLight to c.error
    }
    Column(Modifier.fillMaxWidth().background(bg).padding(horizontal = 16.dp, vertical = 7.dp)) {
        Text(banner.text, style = MaterialTheme.typography.bodySmall, color = fg)
        banner.detail?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun InlineError(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = Msst.colors.error)

@Composable
fun InlineNote(text: String, modifier: Modifier = Modifier, selectable: Boolean = false) {
    val t = @Composable { Text(text, modifier, style = MaterialTheme.typography.labelSmall, color = Msst.colors.textMuted) }
    if (selectable) SelectionContainer { t() } else t()
}

/** Monospace, selectable value (IDs, addresses). */
@Composable
fun MonoText(text: String, modifier: Modifier = Modifier, color: Color = Msst.colors.textPrimary, small: Boolean = false) {
    SelectionContainer(modifier) {
        Text(
            text,
            style = (if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall).copy(fontFamily = MonoFamily),
            color = color,
        )
    }
}

/** Small coloured pill (rule action, record status, …). */
@Composable
fun Pill(text: String, fg: Color, bg: Color) {
    Text(
        text,
        Modifier.clip(Radius.full).background(bg).padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = fg,
    )
}

/** Removable tag (`.tag`). */
@Composable
fun Tag(text: String, onRemove: (() -> Unit)? = null) {
    Row(
        Modifier.clip(Radius.full).background(Msst.colors.primaryLighter)
            .border(1.dp, Msst.colors.primarySoftBorder, Radius.full)
            .padding(start = 10.dp, end = if (onRemove != null) 4.dp else 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Msst.colors.primaryDark)
        if (onRemove != null) {
            Box(Modifier.padding(start = 4.dp).clip(Radius.full).clickable(onClick = onRemove).padding(2.dp)) {
                MIcon(MdiIcons.Close, 12.dp, Msst.colors.primaryDark, "移除")
            }
        }
    }
}

@Composable
fun Spinner(size: Dp = 16.dp, color: Color = Msst.colors.primary) =
    CircularProgressIndicator(Modifier.size(size), color = color, strokeWidth = 2.dp)

/** An icon that spins while [spinning] (refresh button). */
@Composable
fun SpinningIcon(icon: ImageVector, spinning: Boolean, size: Dp = 17.dp, tint: Color = Color.Unspecified) {
    if (!spinning) return MIcon(icon, size, tint)
    val t = rememberInfiniteTransition()
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart))
    Box(Modifier.rotate(angle)) { MIcon(icon, size, tint) }
}

// ── Buttons ──────────────────────────────────────────────────────────────────

private val ButtonPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    icon: ImageVector? = null,
    danger: Boolean = false,
) {
    val c = Msst.colors
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 36.dp),
        enabled = enabled && !busy,
        shape = Radius.md,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) c.error else c.primary,
            contentColor = c.textInverse,
            disabledContainerColor = (if (danger) c.error else c.primary).copy(alpha = 0.45f),
            disabledContentColor = c.textInverse,
        ),
    ) {
        if (busy) Spinner(14.dp, c.textInverse) else if (icon != null) MIcon(icon, 16.dp)
        if (busy || icon != null) Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    danger: Boolean = false,
    active: Boolean = false,
) {
    val c = Msst.colors
    val fg = if (danger) c.error else if (active) c.primaryDark else c.primary
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 36.dp),
        enabled = enabled,
        shape = Radius.md,
        contentPadding = ButtonPadding,
        border = BorderStroke(1.dp, if (danger) c.error.copy(alpha = 0.5f) else if (active) c.primary else c.borderPrimary),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (active) c.primaryLighter else c.bgPrimary,
            contentColor = fg,
            disabledContentColor = fg.copy(alpha = 0.45f),
        ),
    ) {
        if (icon != null) {
            MIcon(icon, 16.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun LinkButton(text: String, onClick: () -> Unit, icon: ImageVector? = null, enabled: Boolean = true, color: Color = Msst.colors.primary) {
    TextButton(onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), shape = Radius.sm) {
        if (icon != null) {
            MIcon(icon, 15.dp, color)
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (enabled) color else color.copy(alpha = 0.45f))
    }
}

@Composable
fun SmallIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = Msst.colors.textTertiary,
    size: Dp = 16.dp,
) {
    // Phones: at least 40 dp to be comfortably tappable.
    IconButton(onClick, Modifier.size(if (LocalCompact.current) 40.dp else 32.dp), enabled = enabled) {
        MIcon(icon, size, if (enabled) tint else tint.copy(alpha = 0.4f), contentDescription)
    }
}

/** Copy button that shows a check for two seconds after copying. */
@Composable
fun CopyButton(text: String, services: PlatformServices, description: String = "复制", size: Dp = 14.dp) {
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    SmallIconButton(
        if (copied) MdiIcons.Check else MdiIcons.ContentCopy,
        description,
        onClick = { scope.launch { copied = services.copyText(text) } },
        tint = if (copied) Msst.colors.success else Msst.colors.textTertiary,
        size = size,
    )
}

// ── Inputs ───────────────────────────────────────────────────────────────────

@Composable
fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    password: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    number: Boolean = false,
    mono: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    val c = Msst.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it, color = c.textMuted) } },
        singleLine = singleLine,
        minLines = minLines,
        textStyle = MaterialTheme.typography.bodyMedium.let { if (mono) it.copy(fontFamily = MonoFamily) else it },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                password -> KeyboardType.Password
                number -> KeyboardType.Number
                else -> KeyboardType.Text
            },
            imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default,
        ),
        keyboardActions = KeyboardActions(onDone = onDone?.let { { it() } }),
        shape = Radius.md,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.primary,
            unfocusedBorderColor = c.borderMedium,
            focusedLabelColor = c.primary,
            cursorColor = c.primary,
            focusedContainerColor = c.bgPrimary,
            unfocusedContainerColor = c.bgPrimary,
            disabledContainerColor = c.bgSecondary,
        ),
    )
}

@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = Msst.colors
    Switch(
        checked, onChange, enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = c.primary,
            checkedThumbColor = c.textInverse,
            uncheckedTrackColor = c.borderMedium,
            uncheckedThumbColor = c.textInverse,
            uncheckedBorderColor = c.borderMedium,
        ),
    )
}

/** A row with a title, optional hint and a switch (`.adv-row`). */
@Composable
fun ToggleRow(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = Msst.colors.textPrimary)
            if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = Msst.colors.textMuted)
        }
        Toggle(checked, onChange, enabled)
    }
}

@Composable
fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit, enabled: Boolean = true, hint: String? = null, mono: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().clip(Radius.sm)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, null, enabled = enabled, colors = RadioButtonDefaults.colors(selectedColor = Msst.colors.primary))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium.let { if (mono) it.copy(fontFamily = MonoFamily) else it },
                color = Msst.colors.textPrimary,
            )
            if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = Msst.colors.textMuted)
        }
    }
}

@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, hint: String? = null) {
    Row(
        Modifier.clip(Radius.sm).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 2.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, null, enabled = enabled, colors = CheckboxDefaults.colors(checkedColor = Msst.colors.primary))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Msst.colors.textPrimary)
            if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = Msst.colors.textMuted)
        }
    }
}

/** A dropdown selector. */
@Composable
fun <T> Select(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "请选择",
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    val c = Msst.colors
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().clip(Radius.md).background(if (enabled) c.bgPrimary else c.bgSecondary)
                .border(1.dp, c.borderMedium, Radius.md)
                .clickable(enabled = enabled) { open = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selected?.let(label) ?: placeholder,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected == null) c.textMuted else c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MIcon(MdiIcons.ChevronDown, 18.dp, c.textTertiary)
        }
        DropdownMenu(open, { open = false }, containerColor = c.bgPrimary) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(label(o), style = MaterialTheme.typography.bodyMedium) }, onClick = {
                    open = false
                    onSelect(o)
                })
            }
        }
    }
}

/** A collapsible section header (`.adv-section-hdr`). */
@Composable
fun Collapsible(title: String, open: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Msst.colors
    Column(Modifier.fillMaxWidth().clip(Radius.md).border(1.dp, c.borderLight, Radius.md)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).background(c.bgSecondary).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
            MIcon(if (open) MdiIcons.ChevronUp else MdiIcons.ChevronDown, 18.dp, c.textTertiary)
        }
        if (open) Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

// ── Dialogs ──────────────────────────────────────────────────────────────────

/**
 * A modal card.  While [busy], it cannot be dismissed (a request is in
 * flight and its outcome must be shown).
 */
@Composable
fun Modal(
    onDismiss: () -> Unit,
    title: String?,
    busy: Boolean = false,
    wide: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy, usePlatformDefaultWidth = false),
    ) {
        val compact = LocalCompact.current
        Surface(
            Modifier.padding(if (compact) 12.dp else 16.dp).widthIn(max = if (wide) 640.dp else 440.dp).fillMaxWidth(),
            shape = Radius.xl,
            color = Msst.colors.bgPrimary,
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.padding(if (compact) 18.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (icon != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { icon() }
                if (title != null) {
                    Text(
                        title,
                        Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.titleLarge,
                        color = Msst.colors.textPrimary,
                        textAlign = if (icon != null) TextAlign.Center else TextAlign.Start,
                    )
                }
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content,
                )
                if (actions != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), content = actions)
                }
            }
        }
    }
}

/** Confirmation for destructive actions (AGENTS.md §7.5). */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = true,
) {
    Modal(onDismiss, title, actions = {
        SecondaryButton("取消", onDismiss)
        PrimaryButton(confirmLabel, { onDismiss(); onConfirm() }, danger = danger)
    }) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Msst.colors.textSecondary)
    }
}

/** Success feedback inside a dialog (`.modal-feedback.success`). */
@Composable
fun SuccessFeedback(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(Radius.md).background(Msst.colors.successLight).padding(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MIcon(MdiIcons.CheckCircleOutline, 18.dp, Msst.colors.success)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = Msst.colors.success)
    }
}

/** Status dot for indicators (not text). */
@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(Radius.full).background(color))

/** Labelled block for read-only key/value lines (`.adv-mono-block`). */
@Composable
fun InfoBlock(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(Radius.md).background(Msst.colors.bgSecondary)
            .border(1.dp, Msst.colors.borderLight, Radius.md).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        content = content,
    )
}

@Composable
fun InfoLine(text: String, color: Color = Msst.colors.textSecondary, mono: Boolean = false) {
    SelectionContainer {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.let { if (mono) it.copy(fontFamily = MonoFamily) else it },
            color = color,
        )
    }
}

/** Empty / loading placeholder for a list. */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Box(modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) Msst.colors.error else Msst.colors.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}
