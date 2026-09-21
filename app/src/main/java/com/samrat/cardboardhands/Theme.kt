package com.samrat.cardboardhands

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import zone.ien.hig.CupertinoActivityIndicator
import zone.ien.hig.CupertinoAlertDialog
import zone.ien.hig.CupertinoButton
import zone.ien.hig.CupertinoButtonDefaults
import zone.ien.hig.CupertinoButtonSize
import zone.ien.hig.CupertinoIcon
import zone.ien.hig.CupertinoNavigateBackButton
import zone.ien.hig.CupertinoNavigationBar
import zone.ien.hig.CupertinoNavigationBarItem
import zone.ien.hig.CupertinoSwitch
import zone.ien.hig.CupertinoText
import zone.ien.hig.ExperimentalCupertinoApi
import zone.ien.hig.cancel
import zone.ien.hig.default
import zone.ien.hig.destructive
import zone.ien.hig.icons.CupertinoIcons
import zone.ien.hig.icons.outlined.Checkmark
import zone.ien.hig.icons.outlined.ChevronForward
import zone.ien.hig.section.CupertinoSection
import zone.ien.hig.section.SectionItem
import zone.ien.hig.section.SectionLink
import zone.ien.hig.section.SectionScope
import zone.ien.hig.section.sectionTitle
import zone.ien.hig.theme.CupertinoTheme
import zone.ien.hig.theme.darkColorScheme
import zone.ien.hig.theme.lightColorScheme
import androidx.compose.material3.ColorScheme as MaterialColors
import androidx.compose.material3.darkColorScheme as materialDarkColors
import androidx.compose.material3.lightColorScheme as materialLightColors

/** PhoneXR orange, the accent of the launcher icon. */
private val orange = Color(0xFFFF7A1A)
private val orangeDark = Color(0xFFFF9544)

/** The two looks PhoneXR can wear. The user picks one in Settings, and it applies at once. */
enum class UiStyle(val title: String, val detail: String) {
    CUPERTINO("PhoneXR UI", "Сгруппированные списки и оранжевый акцент PhoneXR"),
    MATERIAL("Material You", "Как в Android: карточки и цвета из обоев системы")
}

/** The chosen look, where every screen can read it and recompose the moment it changes. */
object Ui {
    var style by mutableStateOf(UiStyle.CUPERTINO)
        private set

    fun load(context: Context) {
        style = Settings.uiStyle(context)
    }

    fun set(context: Context, value: UiStyle) {
        Settings.setUiStyle(context, value)
        style = value
    }
}

/** Apple HIG (compose-hig) or Material You, in light and dark. */
@Composable
fun PhoneXRTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    remember { Ui.load(context) }
    val dark = isSystemInDarkTheme()
    if (Ui.style == UiStyle.MATERIAL) {
        val colors = materialColors(context, dark)
        MaterialTheme(colorScheme = colors) {
            // Everything still drawn by compose-hig (texts, switches, dialogs) takes these colours too.
            CupertinoTheme(colorScheme = cupertinoFrom(colors, dark), content = content)
        }
    } else {
        val colors = if (dark) darkColorScheme(accent = orangeDark) else lightColorScheme(accent = orange)
        CupertinoTheme(colorScheme = colors, content = content)
    }
}

/** Material You: the wallpaper palette on Android 12 and newer, the PhoneXR orange before that. */
private fun materialColors(context: Context, dark: Boolean): MaterialColors = when {
    Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
    Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
    dark -> materialDarkColors(primary = orangeDark, secondary = orangeDark)
    else -> materialLightColors(primary = orange, secondary = orange)
}

/** The Material palette said in Cupertino's words, so both looks agree on colour. */
private fun cupertinoFrom(colors: MaterialColors, dark: Boolean) =
    (if (dark) darkColorScheme(accent = colors.primary) else lightColorScheme(accent = colors.primary)).copy(
        accent = colors.primary,
        link = colors.primary,
        label = colors.onSurface,
        secondaryLabel = colors.onSurfaceVariant,
        tertiaryLabel = colors.outline,
        separator = colors.outlineVariant,
        opaqueSeparator = colors.outlineVariant,
        systemBackground = colors.surface,
        secondarySystemBackground = colors.surfaceContainer,
        systemGroupedBackground = colors.surface,
        secondarySystemGroupedBackground = colors.surfaceContainer,
        tertiarySystemGroupedBackground = colors.surfaceContainerHigh
    )

/** Colours screens name directly, whichever look is on. */
object HigColors {
    val accent: Color @Composable get() = CupertinoTheme.colorScheme.accent
    val label: Color @Composable get() = CupertinoTheme.colorScheme.label
    val secondary: Color @Composable get() = CupertinoTheme.colorScheme.secondaryLabel
    val good: Color @Composable get() = if (Ui.style == UiStyle.MATERIAL) MaterialTheme.colorScheme.primary else Color(0xFF34C759)
    val bad: Color @Composable get() = if (Ui.style == UiStyle.MATERIAL) MaterialTheme.colorScheme.error else Color(0xFFFF3B30)
}

// ---------------------------------------------------------------- pages and sections

/** Grouped settings-style page: optional back button, large title, scrolling sections. */
@OptIn(ExperimentalCupertinoApi::class)
@Composable
fun HigPage(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    /** Room under the content, e.g. for the tab bar floating above it. */
    bottomInset: Dp = 24.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val material = Ui.style == UiStyle.MATERIAL
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (material) MaterialTheme.colorScheme.surface
                else CupertinoTheme.colorScheme.systemGroupedBackground
            )
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = bottomInset)
    ) {
        if (onBack != null) {
            if (material) {
                TextButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp, top = 4.dp)) {
                    Text("← " + tr("Назад"))
                }
            } else {
                CupertinoNavigateBackButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
                    CupertinoText(tr("Назад"))
                }
            }
        } else {
            Spacer(Modifier.height(16.dp))
        }
        if (material) {
            Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(horizontal = 20.dp))
        } else {
            CupertinoText(title, style = CupertinoTheme.typography.largeTitle, modifier = Modifier.padding(horizontal = 20.dp))
        }
        if (subtitle != null) {
            if (material) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp)
                )
            } else {
                CupertinoText(
                    subtitle,
                    style = CupertinoTheme.typography.subhead,
                    color = CupertinoTheme.colorScheme.secondaryLabel,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp)
                )
            }
        }
        content()
    }
}

/**
 * The rows of a section. In the Cupertino look they are drawn by compose-hig and need its own scope;
 * in the Material look there is none, and the rows lay themselves out.
 */
class HigScope internal constructor(internal val section: SectionScope?)

/** Inset grouped section (Cupertino) or a filled card (Material), with a header and footer text. */
@OptIn(ExperimentalCupertinoApi::class)
@Composable
fun HigSection(
    title: String? = null,
    footer: String? = null,
    content: @Composable HigScope.() -> Unit
) {
    if (Ui.style != UiStyle.MATERIAL) {
        CupertinoSection(
            title = title?.let { { CupertinoText(it.sectionTitle()) } },
            caption = footer?.let { { CupertinoText(it) } },
            content = { HigScope(this).content() }
        )
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, bottom = 6.dp)
            )
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column { HigScope(null).content() }
        }
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp)
            )
        }
    }
}

// ---------------------------------------------------------------- rows

/** Tappable row with a chevron and an optional value on the right. */
@OptIn(ExperimentalCupertinoApi::class)
@Composable
fun HigScope.HigLink(title: String, value: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val scope = section
    if (scope != null) {
        with(scope) {
            SectionLink(
                onClick = onClick,
                enabled = enabled,
                caption = { if (value != null) CupertinoText(value) },
                title = { CupertinoText(title) }
            )
        }
    } else {
        MaterialRow(onClick = onClick, enabled = enabled, chevron = true, title = { MaterialTitle(title, enabled) }) {
            if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Plain row: title with a secondary line under it and anything on the right. */
@OptIn(ExperimentalCupertinoApi::class)
@Composable
fun HigScope.HigRow(
    title: String,
    detail: String? = null,
    detailColor: Color = Color.Unspecified,
    trailing: @Composable () -> Unit = {}
) {
    val scope = section
    if (scope != null) {
        with(scope) {
            SectionItem(trailingContent = trailing) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    CupertinoText(title)
                    if (detail != null) {
                        CupertinoText(
                            detail,
                            style = CupertinoTheme.typography.footnote,
                            color = if (detailColor == Color.Unspecified) CupertinoTheme.colorScheme.secondaryLabel else detailColor
                        )
                    }
                }
            }
        }
    } else {
        MaterialRow(title = { MaterialTitle(title, enabled = true, detail = detail, detailColor = detailColor) }, trailing = trailing)
    }
}

/** Row with a value and two buttons, for a number the user nudges up and down. */
@Composable
fun HigScope.HigStepper(title: String, value: String, detail: String? = null, onStep: (Int) -> Unit) {
    val trailing: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StepButton("−") { onStep(-1) }
            Box(Modifier.width(76.dp), contentAlignment = Alignment.Center) {
                HigText(value)
            }
            StepButton("+") { onStep(1) }
        }
    }
    HigRow(title, detail, trailing = trailing)
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    if (Ui.style == UiStyle.MATERIAL) {
        FilledTonalButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), modifier = Modifier.size(40.dp)) {
            Text(label)
        }
    } else {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CupertinoTheme.colorScheme.tertiarySystemFill)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) { CupertinoText(label) }
    }
}

/** Row with a switch on the right. */
@OptIn(ExperimentalCupertinoApi::class)
@Composable
fun HigScope.HigSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val scope = section
    if (scope != null) {
        with(scope) {
            SectionItem(trailingContent = {
                CupertinoSwitch(checked = checked, onCheckedChange = onCheckedChange)
            }) { CupertinoText(title) }
        }
    } else {
        MaterialRow(onClick = { onCheckedChange(!checked) }, title = { MaterialTitle(title, enabled = true) }) {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

/** Row of a single-choice list: tapping selects it, the chosen one is marked. */
@OptIn(ExperimentalCupertinoApi::class)
@Composable
fun HigScope.HigChoice(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    val scope = section
    if (scope != null) {
        with(scope) {
            SectionLink(
                onClick = onClick,
                chevron = {
                    if (selected) {
                        CupertinoIcon(CupertinoIcons.Default.Checkmark, null, tint = CupertinoTheme.colorScheme.accent)
                    }
                },
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        CupertinoText(title)
                        if (detail != null) {
                            CupertinoText(
                                detail,
                                style = CupertinoTheme.typography.footnote,
                                color = CupertinoTheme.colorScheme.secondaryLabel
                            )
                        }
                    }
                }
            )
        }
    } else {
        MaterialRow(onClick = onClick, title = { MaterialTitle(title, enabled = true, detail = detail) }) {
            RadioButton(selected = selected, onClick = onClick)
        }
    }
}

/**
 * Tappable row with an icon, several lines of text and anything on the right — a game in the
 * library, an item in the store.
 */
@OptIn(ExperimentalCupertinoApi::class)
@Composable
fun HigScope.HigItem(
    title: String,
    details: List<String> = emptyList(),
    detailColor: Color = Color.Unspecified,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
    trailing: @Composable () -> Unit = {},
    onClick: () -> Unit
) {
    val scope = section
    if (scope != null) {
        with(scope) {
            SectionLink(
                onClick = onClick,
                enabled = enabled,
                icon = icon,
                caption = trailing,
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        CupertinoText(title)
                        details.forEach { line ->
                            CupertinoText(
                                line,
                                style = CupertinoTheme.typography.footnote,
                                color = if (detailColor == Color.Unspecified) CupertinoTheme.colorScheme.secondaryLabel else detailColor
                            )
                        }
                    }
                }
            )
        }
    } else {
        MaterialRow(
            onClick = onClick,
            enabled = enabled,
            icon = icon,
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    details.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (detailColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else detailColor
                        )
                    }
                }
            },
            trailing = trailing
        )
    }
}

@Composable
private fun MaterialRow(
    title: @Composable () -> Unit,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    chevron: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit = {}
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        if (icon != null) icon()
        Box(Modifier.weight(1f)) { title() }
        trailing()
        if (chevron) {
            Icon(
                CupertinoIcons.Default.ChevronForward,
                null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun MaterialTitle(
    title: String,
    enabled: Boolean,
    detail: String? = null,
    detailColor: Color = Color.Unspecified
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
        )
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (detailColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else detailColor
            )
        }
    }
}

// ---------------------------------------------------------------- buttons, text, dialogs

/** Full-width prominent button placed between sections. */
@OptIn(ExperimentalCupertinoApi::class)
@Composable
fun HigButton(text: String, enabled: Boolean = true, filled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        if (Ui.style == UiStyle.MATERIAL) {
            if (filled) {
                Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(text) }
            } else {
                FilledTonalButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(text) }
            }
        } else {
            CupertinoButton(
                onClick = onClick,
                enabled = enabled,
                size = CupertinoButtonSize.Large,
                colors = if (filled) CupertinoButtonDefaults.filledButtonColors() else CupertinoButtonDefaults.tintedButtonColors(),
                modifier = Modifier.fillMaxWidth()
            ) { CupertinoText(text) }
        }
    }
}

/** A line of text in whichever look is on. */
@Composable
fun HigText(text: String, color: Color = Color.Unspecified, modifier: Modifier = Modifier) {
    if (Ui.style == UiStyle.MATERIAL) {
        Text(text, color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color, modifier = modifier)
    } else {
        CupertinoText(text, color = color, modifier = modifier)
    }
}

/** The small spinner shown while something is loading. */
@OptIn(ExperimentalCupertinoApi::class)
@Composable
fun HigSpinner() {
    if (Ui.style == UiStyle.MATERIAL) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
    } else {
        CupertinoActivityIndicator()
    }
}

/** How a dialog button reads: the plain choice, the one that cancels, the one that destroys. */
enum class HigActionStyle { DEFAULT, CANCEL, DESTRUCTIVE }

class HigAction(
    val title: String,
    val style: HigActionStyle = HigActionStyle.DEFAULT,
    val onClick: () -> Unit
)

/** Alert with a title, a message and a list of buttons. */
@OptIn(ExperimentalCupertinoApi::class)
@Composable
fun HigAlert(
    title: String,
    message: String? = null,
    actions: List<HigAction>,
    onDismiss: () -> Unit
) {
    if (Ui.style != UiStyle.MATERIAL) {
        CupertinoAlertDialog(
            onDismissRequest = onDismiss,
            title = { CupertinoText(title) },
            message = { if (message != null) CupertinoText(message) },
            buttonsOrientation = if (actions.size > 2) androidx.compose.foundation.gestures.Orientation.Vertical
            else androidx.compose.foundation.gestures.Orientation.Horizontal
        ) {
            actions.forEach { action ->
                when (action.style) {
                    HigActionStyle.CANCEL -> cancel(onClick = action.onClick) { CupertinoText(action.title) }
                    HigActionStyle.DESTRUCTIVE -> destructive(onClick = action.onClick) { CupertinoText(action.title) }
                    HigActionStyle.DEFAULT -> default(onClick = action.onClick) { CupertinoText(action.title) }
                }
            }
        }
        return
    }
    val cancel = actions.lastOrNull { it.style == HigActionStyle.CANCEL }
    val rest = actions.filter { it.style != HigActionStyle.CANCEL }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
            ) {
                if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium)
                // A list of choices does not fit two slots at the bottom, so it stands in the body.
                if (rest.size > 1) rest.forEach { action -> DialogAction(action, Modifier.fillMaxWidth()) }
            }
        },
        confirmButton = { rest.singleOrNull()?.let { DialogAction(it) } },
        dismissButton = cancel?.let { { DialogAction(it) } }
    )
}

@Composable
private fun DialogAction(action: HigAction, modifier: Modifier = Modifier) {
    TextButton(onClick = action.onClick, modifier = modifier) {
        Text(
            action.title,
            color = if (action.style == HigActionStyle.DESTRUCTIVE) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary
        )
    }
}

// ---------------------------------------------------------------- the tab bar

class HigTab(val icon: ImageVector, val label: String)

/** The bar at the bottom of the main screen: liquid glass in Cupertino, a nav bar in Material. */
@OptIn(ExperimentalCupertinoApi::class)
@Composable
fun HigTabBar(
    tabs: List<HigTab>,
    selected: Int,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) {
    if (Ui.style == UiStyle.MATERIAL) {
        NavigationBar(modifier = modifier) {
            tabs.forEachIndexed { index, tab ->
                NavigationBarItem(
                    selected = index == selected,
                    onClick = { onSelect(index) },
                    icon = { Icon(tab.icon, null, modifier = Modifier.size(24.dp)) },
                    label = { Text(tab.label) }
                )
            }
        }
        return
    }
    // compose-hig keeps its own remembered indicator; recreate it when the app changes tabs so
    // "Settings" cannot leave the highlight stuck on "Menu".
    key(selected) {
        CupertinoNavigationBar(
            modifier = modifier,
            backdrop = backdrop,
            selectedTabIndex = { selected },
            onTabSelected = onSelect,
            tabsCount = tabs.size
        ) {
            tabs.forEachIndexed { index, tab ->
                CupertinoNavigationBarItem(
                    onClick = { onSelect(index) },
                    icon = { CupertinoIcon(tab.icon, null) },
                    label = { CupertinoText(tab.label) }
                )
            }
        }
    }
}
