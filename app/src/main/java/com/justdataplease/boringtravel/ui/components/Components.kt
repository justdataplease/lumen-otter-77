package com.justdataplease.boringtravel.ui.components

import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.art.PlaneMark
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.theme.BtShadow
import com.justdataplease.boringtravel.ui.theme.BtStroke
import com.justdataplease.boringtravel.ui.theme.BtTokens
import com.justdataplease.boringtravel.ui.theme.Paper
import com.justdataplease.boringtravel.ui.theme.PhotoTape
import com.justdataplease.boringtravel.ui.theme.planeMark
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/** Button, chip and segment shape: fully round in Trail, the theme's small corner elsewhere. */
@Composable
fun btControlShape(): Shape = btControlShape(Bt.tokens)

fun btControlShape(t: BtTokens): Shape = if (t.pill) RoundedCornerShape(50) else RoundedCornerShape(t.pillRadius)

/** Card shape: the theme's corner (Sketchbook's hand-drawn boxes are uneven). */
@Composable
fun btCardShape(): Shape = btCardShape(Bt.tokens)

fun btCardShape(t: BtTokens): Shape = t.skin.cardCorners?.let {
    RoundedCornerShape(topStart = it.topStart, topEnd = it.topEnd, bottomEnd = it.bottomEnd, bottomStart = it.bottomStart)
} ?: RoundedCornerShape(t.radius)

/** Round buttons: circles, or Sketchbook's hand-drawn ones. */
fun btRoundShape(t: BtTokens): Shape =
    if (t.skin.wobbly) RoundedCornerShape(50, 46, 52, 48) else CircleShape

// ---------------------------------------------------------------- paper

/** A dot grid on [color]: dots of [radius] every [step], centred in each square (CSS radial-gradient tiles). */
fun Modifier.paper(color: Color, dot: Color?, step: Dp = 16.dp, radius: Dp = 1.dp): Modifier = this
    .background(color)
    .then(
        if (dot == null) Modifier else Modifier.drawWithCache {
            val s = step.toPx()
            val points = ArrayList<Offset>()
            var y = s / 2
            while (y < size.height) {
                var x = s / 2
                while (x < size.width) {
                    points += Offset(x, y)
                    x += s
                }
                y += s
            }
            val width = radius.toPx() * 2
            // One call with round points: cheap even for Market's fine 9 dp grid.
            onDrawBehind { drawPoints(points, PointMode.Points, dot, strokeWidth = width, cap = StrokeCap.Round) }
        },
    )

/**
 * The theme's page: plain (Postcard Pop, Trail), dots (Sticker Book), fine kraft fibre dots (Market), ruled lines
 * (Sketchbook) or squares.
 */
fun Modifier.paper(tokens: BtTokens): Modifier = when (tokens.paper) {
    Paper.Plain -> background(tokens.colors.bg)
    Paper.Dots -> paper(tokens.colors.bg, tokens.colors.paperDot, tokens.paperStep, tokens.paperDotRadius)
    Paper.Lines -> background(tokens.colors.bg).drawBehind {
        val rule = tokens.colors.paperDot ?: return@drawBehind
        // Whole pixels only: a fractional step (22 dp = 57.75 px at 2.625x) blurs every other line and makes the
        // gaps look uneven. Each rule is a crisp bar of whole pixels at an exact multiple of the step.
        val step = Ruling.stepPx(tokens.paperStep.toPx())
        val stroke = Ruling.strokePx(0.75.dp.toPx())
        for (y in Ruling.lines(size.height, step)) {
            drawRect(rule, Offset(0f, y.toFloat()), Size(size.width, stroke.toFloat()))
        }
        tokens.margin?.let { m ->
            drawRect(m, Offset(Ruling.stepPx(14.dp.toPx()).toFloat(), 0f), Size(stroke.toFloat(), size.height))
        }
    }
    Paper.Grid -> background(tokens.colors.bg).drawWithCache {
        val rule = tokens.colors.paperDot
        val step = Ruling.stepPx(tokens.paperStep.toPx())
        val stroke = Ruling.strokePx(0.75.dp.toPx())
        // One path for all the rules, so each crossing is filled once and doesn't show as a darker dot.
        val path = Path().apply {
            for (r in Ruling.grid(size.width, size.height, step, stroke)) {
                addRect(Rect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()))
            }
        }
        onDrawBehind { if (rule != null) drawPath(path, rule) }
    }
}

/**
 * A hairline between rows. On ruled paper (Sketchbook) the paper's own lines already separate things, and a second
 * line a few pixels from a rule looks messy, so it draws nothing there.
 */
@Composable
fun BtDivider(modifier: Modifier = Modifier) {
    if (Bt.tokens.paper.ruled) return
    HorizontalDivider(modifier, color = Bt.colors.line)
}

/** Ruled and squared paper geometry in whole pixels, so every gap between lines is exactly the same size. */
object Ruling {
    fun stepPx(px: Float): Int = px.roundToInt().coerceAtLeast(2)
    fun strokePx(px: Float): Int = px.roundToInt().coerceAtLeast(1)

    /** Tops of the rules: step, 2·step, 3·step … below [height]. */
    fun lines(height: Float, step: Int): List<Int> =
        if (step <= 0) emptyList() else generateSequence(step) { it + step }.takeWhile { it < height }.toList()

    /**
     * Squared paper: rules across at [lines] of the height and down at [lines] of the width, each [stroke] px thick,
     * so every square is the same whole number of pixels both ways.
     */
    fun grid(width: Float, height: Float, step: Int, stroke: Int): List<IntRect> {
        val w = ceil(width).toInt()
        val h = ceil(height).toInt()
        return lines(height, step).map { y -> IntRect(0, y, w, y + stroke) } +
            lines(width, step).map { x -> IntRect(x, 0, x + stroke, h) }
    }
}

// ---------------------------------------------------------------- shadows and borders

/** CSS blur radius → Skia blur radius (CSS sigma = blur / 2; Skia sigma = 0.57735 × radius + 0.5). */
internal fun skiaBlurRadius(cssBlurPx: Float): Float = ((cssBlurPx / 2f - 0.5f) / 0.57735f).coerceAtLeast(0.1f)

/** A solid border, or Market's dashed stitching. */
fun Modifier.btBorder(stroke: BtStroke?, shape: Shape): Modifier = when {
    stroke == null || stroke.width.value <= 0f -> this
    !stroke.dashed -> border(stroke.width, stroke.color, shape)
    else -> drawWithCache {
        val w = stroke.width.toPx()
        val inner = Size((size.width - w).coerceAtLeast(0f), (size.height - w).coerceAtLeast(0f))
        val path = Path().apply {
            addOutline(shape.createOutline(inner, layoutDirection, this@drawWithCache))
            translate(Offset(w / 2f, w / 2f))
        }
        val style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
        onDrawWithContent {
            drawContent()
            drawPath(path, stroke.color, style = style)
        }
    }
}

// ---------------------------------------------------------------- screens

@Composable
fun BtScreen(modifier: Modifier = Modifier, statusScrim: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().paper(Bt.tokens)) {
        content()
        // Scrolling main screens: a band of the page colour behind the status bar, so lists never run under the clock.
        if (statusScrim) Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(Bt.colors.bg))
    }
}

// ---------------------------------------------------------------- buttons

/**
 * The main button (`.btn`): the theme's primary; Postcard Pop adds an ink outline and a hard shadow. [danger] fills it
 * with the theme's danger colour instead (Exit demo), its words light or dark to stay readable on it.
 */
@Composable
fun BtButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    small: Boolean = false,
    danger: Boolean = false,
) {
    val c = Bt.colors
    val skin = Bt.tokens.skin
    val shape = btControlShape()
    val fill = if (danger) c.danger else c.primary
    val on = if (!danger) c.onPrimary else if (c.danger.luminance() > 0.3f) Color(0xFF1B1B1B) else Color.White
    Row(
        modifier
            .heightIn(min = 48.dp)
            .btShadow(if (enabled) skin.buttonShadow else null, shape)
            .clip(shape)
            .background(if (enabled) fill else c.line)
            .btBorder(if (enabled) skin.buttonBorder else null, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (small) 14.dp else 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = if (enabled) on else c.muted)
        Text(text, Modifier.weight(1f, fill = false), style = Bt.text.button, color = if (enabled) on else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The quiet button (`.btn.ghost`): see-through with a border; Postcard Pop's has the ink outline and hard shadow. */
@Composable
fun BtGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: String? = null,
    enabled: Boolean = true,
    /** Deletes or removes something: words, icon and outline in the danger colour (see BtConfirm.kt). */
    danger: Boolean = false,
) {
    val c = Bt.colors
    val skin = Bt.tokens.skin
    val shape = btControlShape()
    Row(
        modifier
            .heightIn(min = 48.dp)
            .btShadow(if (enabled) skin.buttonShadow else null, shape)
            .clip(shape)
            // A delete is red all over: its outline too (owner, 2026-11-12).
            .btBorder(if (danger && enabled) skin.ghostBorder.copy(color = c.danger) else skin.ghostBorder, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (trailing == null) Alignment.CenterHorizontally else Alignment.Start),
    ) {
        // Disabled: the muted ink at half strength (plain muted reads almost as dark as ink in some looks).
        val ink = if (!enabled) c.muted.copy(alpha = 0.5f) else if (danger) c.danger else c.ink
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = ink)
        Text(text, style = Bt.text.button, color = ink, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            Text(trailing, style = Bt.text.mono)
        }
    }
}

/**
 * A round icon button (`.iconbtn`), [size] across (40 dp by default) inside a 48 dp touch target. [filled] uses the
 * primary colour.
 */
@Composable
fun BtIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    size: Dp = 40.dp,
    /** Deletes or removes something: the icon and its outline in the danger colour (see BtConfirm.kt). */
    danger: Boolean = false,
) {
    val c = Bt.colors
    val t = Bt.tokens
    val shape = btRoundShape(t)
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .size(size.coerceAtLeast(32.dp))
            .clip(shape)
            .background(if (filled) c.primary else t.skin.iconFill ?: c.surface)
            .btBorder(if (filled) null else if (danger) t.skin.iconBorder.copy(color = c.danger) else t.skin.iconBorder, shape)
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(20.dp), tint = if (filled) c.onPrimary else if (danger) c.danger else c.ink)
    }
}

/** Back on a top bar (`.rb`): white with ink in Postcard Pop and Trail, the theme's paper elsewhere. */
@Composable
fun BtBackButton(onClick: () -> Unit, modifier: Modifier = Modifier, contentDescription: String = "Back") {
    val t = Bt.tokens
    val skin = t.skin
    val shape = btRoundShape(t)
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .size(40.dp)
            .btShadow(skin.backShadow, shape)
            .clip(shape)
            .background(skin.backFill)
            .btBorder(skin.backBorder, shape)
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(BtIcons.Back, contentDescription, Modifier.size(20.dp), tint = skin.backIcon)
    }
}

/** The floating action (`.fab`): primary, 48 dp, a soft shadow (Postcard Pop: ink outline and hard shadow). */
@Composable
fun BtFab(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = BtIcons.Add) {
    val c = Bt.colors
    val skin = Bt.tokens.skin
    val shape = btControlShape()
    Row(
        modifier
            .height(48.dp)
            .btShadow(skin.fabShadow, shape)
            .clip(shape)
            .background(c.primary)
            .btBorder(skin.buttonBorder, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        // Centred when it is given a width (Trips' pair of buttons); hugs its content otherwise.
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = c.onPrimary)
        Text(
            text, Modifier.weight(1f, fill = false), style = Bt.text.button.copy(fontSize = Bt.text.button.fontSize * (15f / 14f)),
            color = c.onPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------- chips and segments

/**
 * A chip (`.chip`), 32 dp tall inside a 48 dp touch target. Trail's unselected chips are outlined. [role]: a choice
 * among chips (a tab) by default; an action chip passes Role.Button so it isn't announced as a tab.
 */
@Composable
fun BtChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    role: Role = Role.Tab,
) {
    val c = Bt.colors
    val skin = Bt.tokens.skin
    val shape = btControlShape()
    val outlined = skin.chipBorder != null
    val fg = if (selected) c.onChipOn else c.ink
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 32.dp)
            .semantics { if (role == Role.Tab) this.selected = selected }
            .clip(shape)
            .background(if (selected) c.chipOn else if (outlined) Color.Transparent else c.chip)
            .btBorder(if (selected) null else skin.chipBorder, shape)
            .clickable(role = role, onClick = onClick)
            .padding(horizontal = if (outlined) 13.dp else 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = fg)
        Text(
            text,
            style = Bt.text.label.copy(fontWeight = if (selected || outlined) FontWeight.SemiBold else FontWeight.Medium),
            color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Segmented control (`.s-seg`; Journal: List · Calendar · Media · Map): the chip colour, the picked one raised. */
@Composable
fun BtSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Bt.colors
    val shape = btControlShape()
    val raised = BtShadow(y = 1.dp, blur = 3.dp, color = Color(0x1F000000))
    Row(modifier.fillMaxWidth().clip(shape).background(c.chip).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 42.dp)
                    .semantics { this.selected = on }
                    .then(if (on) Modifier.btShadow(raised, shape) else Modifier)
                    .clip(shape)
                    .background(if (on) c.surface else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                    style = Bt.text.label.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (on) c.ink else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- cards and labels

/**
 * A card (`.card`): the surface with a hairline. Postcard Pop: ink outline and a hard shadow; Sticker Book: a flat
 * offset shadow; Sketchbook: a see-through hand-drawn box; Market: dashed stitching.
 */
@Composable
fun BtCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(20.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Bt.colors
    val skin = Bt.tokens.skin
    val shape = btCardShape()
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .btShadow(skin.cardShadow, shape)
                .clip(shape)
                .background(skin.cardFill ?: c.surface)
                .btBorder(skin.cardBorder, shape)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(padding),
            content = content,
        )
    }
}

/** Small upper-case label in the mono face (`.s-mono`): dates, sections. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, accent: Boolean = false, color: Color? = null) {
    Text(text.uppercase(), modifier, style = Bt.text.eyebrow, color = color ?: if (accent) Bt.colors.accentInk else Bt.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A section's label ("Theme", "Fonts", "Next"): the mono eyebrow. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) = Eyebrow(text, modifier)

/** Screen title in the display face (upper-case in Trail). */
@Composable
fun H1(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(Bt.text.display(text), modifier, style = Bt.text.h1, color = color ?: Bt.colors.ink)
}

/** The PRO tag (`.s-pro`): the highlighter with its ink. */
@Composable
fun ProTag(modifier: Modifier = Modifier) = BtTag("PRO", modifier)

/** The DEMO tag on the example trips (rows, the Now card, the trip and place headers): made like the PRO tag. */
@Composable
fun DemoTag(modifier: Modifier = Modifier) = BtTag("DEMO", modifier)

/** A small mono caps tag on the accent (`.s-pro`). */
@Composable
fun BtTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.clip(RoundedCornerShape(5.dp)).background(Bt.colors.accent).padding(horizontal = 7.dp, vertical = 3.dp),
        style = Bt.text.eyebrow.copy(fontSize = Bt.text.eyebrow.fontSize * 0.92f, fontWeight = FontWeight.SemiBold, letterSpacing = Bt.text.eyebrow.letterSpacing * 1.25f),
        color = Bt.colors.onAccent,
        maxLines = 1,
    )
}

/** A settings row with a switch: title, one line under it, and the switch in the theme's colours. */
@Composable
fun BtSwitchRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Bt.colors
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Bt.text.item)
            Text(body, style = Bt.text.sub)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onPrimary,
                checkedTrackColor = c.primary,
                checkedBorderColor = c.primary,
                uncheckedThumbColor = c.muted,
                uncheckedTrackColor = c.chip,
                uncheckedBorderColor = c.line,
            ),
        )
    }
}

// ---------------------------------------------------------------- highlighter

/**
 * A highlighter stroke behind the element, from [from] to [to] of its height (`.hl`: 45 %–90 %), leaning [tiltDeg]
 * (the Sticker Book bar's −2°).
 */
fun Modifier.highlighter(color: Color, from: Float = 0.45f, to: Float = 0.9f, tiltDeg: Float = 0f): Modifier = drawBehind {
    rotate(tiltDeg) { drawRect(color, Offset(0f, size.height * from), Size(size.width, size.height * (to - from))) }
}

// ---------------------------------------------------------------- photos

/**
 * A photo as the theme places it (`.ph`): a frame ([BtPhoto.frameColor], padding, a deeper polaroid bottom in Sticker
 * Book), its shadow, a lean and the theme's tape (washi, yellow corners, kraft). The angle is [tiltDeg], else the
 * list lean for [index], else the theme's single-photo lean, always × the theme's tilt factor. [caption] is
 * handwritten on the bottom-left of the frame. The photo fills whatever size the frame is given.
 */
@Composable
fun BtPhotoFrame(
    modifier: Modifier = Modifier,
    index: Int? = null,
    tiltDeg: Float? = null,
    caption: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val t = Bt.tokens
    val p = t.photo
    val angle = when {
        tiltDeg != null -> tiltDeg * p.tiltK
        index != null -> p.tiltFor(index)
        else -> p.tiltDeg * p.tiltK
    }
    val frameShape = RoundedCornerShape(p.frameCorner)
    val tape = p.tape
    // The caption sits on the frame, not the page: dark ink on a light frame even in dark themes (Trail).
    val captionInk = if (p.frameColor.luminance() > .5f && t.look.dark) t.colors.bg else t.colors.ink
    val captionStyle = Bt.text.caption.copy(color = captionInk)
    Layout(
        content = {
            Box(Modifier.clip(RoundedCornerShape(p.corner)), propagateMinConstraints = true, content = content)
            if (caption != null) Text(caption, style = captionStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        modifier = modifier
            .rotate(angle)
            .btShadow(p.drop, frameShape)
            .background(p.frameColor, frameShape)
            .btBorder(p.border, frameShape)
            .drawWithContent {
                drawContent()
                if (tape != PhotoTape.None) drawTape(tape)
            },
    ) { measurables, constraints ->
        val pad = p.framePad.roundToPx()
        val band = p.frameBottom.roundToPx()
        val innerMaxW = if (constraints.hasBoundedWidth) (constraints.maxWidth - 2 * pad).coerceAtLeast(0) else Constraints.Infinity
        val innerMaxH = if (constraints.hasBoundedHeight) (constraints.maxHeight - pad - band).coerceAtLeast(0) else Constraints.Infinity
        val innerMinW = (constraints.minWidth - 2 * pad).coerceIn(0, innerMaxW)
        val innerMinH = (constraints.minHeight - pad - band).coerceIn(0, innerMaxH)
        val photo = measurables[0].measure(Constraints(innerMinW, innerMaxW, innerMinH, innerMaxH))
        val w = photo.width + 2 * pad
        val h = photo.height + pad + band
        val label = measurables.getOrNull(1)?.measure(Constraints(maxWidth = (w - 20.dp.roundToPx()).coerceAtLeast(0)))
        layout(w, h) {
            photo.place(pad, pad)
            label?.place(10.dp.roundToPx(), h - 4.dp.roundToPx() - label.height)
        }
    }
}

/** The theme's tape over a photo frame of [size], as the boards draw it (`.ph::before` / `::after`). */
private fun DrawScope.drawTape(tape: PhotoTape) {
    val w = size.width
    when (tape) {
        PhotoTape.None -> Unit
        PhotoTape.Washi -> {
            // Pink washi across the top: 44 % wide, 12 dp tall, 7 dp above the frame, −5°, 6 dp stripes.
            val left = w * 0.28f
            val tw = w * 0.44f
            val th = 12.dp.toPx()
            val top = -7.dp.toPx()
            rotate(-5f, Offset(left + tw / 2, top + th / 2)) {
                clipRect(left, top, left + tw, top + th) {
                    val stripe = 6.dp.toPx()
                    var x = left
                    var i = 0
                    while (x < left + tw) {
                        drawRect(if (i % 2 == 0) WASHI_A else WASHI_B, Offset(x, top), Size(stripe, th))
                        x += stripe
                        i++
                    }
                }
            }
        }
        PhotoTape.Corners -> {
            // Two strips of yellow tape on the top-left and bottom-right corners, −35°.
            val tw = 26.dp.toPx()
            val th = 10.dp.toPx()
            val a = Offset(-8.dp.toPx(), -5.dp.toPx())
            val b = Offset(w + 8.dp.toPx() - tw, size.height + 5.dp.toPx() - th)
            for (o in listOf(a, b)) {
                rotate(-35f, Offset(o.x + tw / 2, o.y + th / 2)) { drawRect(CORNER_TAPE, o, Size(tw, th)) }
            }
        }
        PhotoTape.Kraft -> {
            // A strip of kraft tape across the top: 36 % wide, 12 dp tall, 6 dp above the frame, 4°.
            val left = w * 0.32f
            val tw = w * 0.36f
            val th = 12.dp.toPx()
            val top = -6.dp.toPx()
            rotate(4f, Offset(left + tw / 2, top + th / 2)) { drawRect(KRAFT_TAPE, Offset(left, top), Size(tw, th)) }
        }
    }
}

private val WASHI_A = Color(0xD9FF8FB1)
private val WASHI_B = Color(0xD9FFAAC4)
private val CORNER_TAPE = Color(0xD9FFE45C)
private val KRAFT_TAPE = Color(0xD9C9AA78)

/** The earlier name of [BtPhotoFrame], kept for the screens that use it. */
@Composable
fun PhotoFrame(
    modifier: Modifier = Modifier,
    index: Int? = null,
    tiltDeg: Float? = null,
    caption: String? = null,
    content: @Composable BoxScope.() -> Unit,
) = BtPhotoFrame(modifier, index, tiltDeg, caption, content)

/**
 * The logo as a tile, like the app icon: the navy-edged paper plane on the theme's yellow ([PlaneMark], [planeMark]),
 * so it follows the theme.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 64.dp) {
    val colors = Bt.colors.planeMark()
    Canvas(modifier.size(size)) { drawPlaneMark(colors) }
}

// ---------------------------------------------------------------- bars and rows

/** Top bar for pushed screens: back, centred title, optional trailing action. */
@Composable
fun BtTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Layout(
        modifier = modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        content = {
            Box { if (onBack != null) BtBackButton(onBack) }
            Text(title, style = Bt.text.item, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
        },
    ) { children, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val edgeConstraints = loose.copy(maxWidth = (constraints.maxWidth * 0.4f).toInt())
        val leading = children[0].measure(edgeConstraints)
        val ending = children[2].measure(edgeConstraints)
        val reserve = maxOf(leading.width, ending.width) + 8.dp.roundToPx()
        val heading = children[1].measure(loose.copy(maxWidth = (constraints.maxWidth - reserve * 2).coerceAtLeast(0)))
        val height = maxOf(48.dp.roundToPx(), leading.height, ending.height, heading.height)
        layout(constraints.maxWidth, height) {
            leading.placeRelative(0, (height - leading.height) / 2)
            heading.placeRelative((constraints.maxWidth - heading.width) / 2, (height - heading.height) / 2)
            ending.placeRelative(constraints.maxWidth - ending.width, (height - ending.height) / 2)
        }
    }
}

/** Big title header for the four main tabs (eyebrow + H1 + optional trailing). */
@Composable
fun BtHeader(title: String, modifier: Modifier = Modifier, eyebrow: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            if (eyebrow != null) Eyebrow(eyebrow)
            H1(title)
        }
        trailing()
    }
}

@Composable
fun BtRadioRow(title: String, body: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Bt.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { this.selected = selected }.clickable(role = Role.RadioButton, onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .padding(top = 1.dp)
                .size(18.dp)
                .clip(CircleShape)
                .border(if (selected) 6.dp else 2.dp, if (selected) c.primary else c.muted, CircleShape),
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = Bt.text.item)
            Text(body, style = Bt.text.sub)
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = Bt.text.title, textAlign = TextAlign.Center)
        Text(body, style = Bt.text.sub, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            action()
        }
    }
}
