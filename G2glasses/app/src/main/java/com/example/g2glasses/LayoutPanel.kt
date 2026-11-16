package com.example.g2glasses

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.g2glasses.glassesui.CalibrationText
import com.example.g2glasses.glassesui.CaptionLayout
import com.example.g2glasses.glassesui.CaptionScreen
import com.example.g2glasses.glassesui.GlassesDisplay
import com.example.g2glasses.glassesui.GlassesPage
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

/** What the Layout tab puts in the caption box while tuning. */
enum class LayoutSample(val label: String) {
    CAPTIONS("Captions"),
    COLUMNS("Column ruler"),
    LINES("Line ruler"),
    CUSTOM("Custom"),
}

/**
 * Debug screen for tuning [CaptionScreen.Style] on the real glasses. Ranges go past Even's
 * documented limits on purpose: with [enforceLimits] off, out-of-spec pages are sent anyway so
 * the lens shows what the firmware really accepts. MainActivity owns and saves the settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LayoutPanel(
    style: CaptionScreen.Style,
    onStyleChange: (CaptionScreen.Style) -> Unit,
    enforceLimits: Boolean,
    onEnforceLimitsChange: (Boolean) -> Unit,
    customText: String,
    onCustomTextChange: (String) -> Unit,
    sample: LayoutSample,
    onSampleChange: (LayoutSample) -> Unit,
    glassesReady: Boolean,
    recentLines: () -> List<CaptionScreen.Line>,
    onSend: (page: GlassesPage, enforceLimits: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var live by rememberSaveable { mutableStateOf(true) }
    var wrapCustom by rememberSaveable { mutableStateOf(false) }
    fun update(s: CaptionScreen.Style) =
        onStyleChange(s.copy(width = max(s.width, Ranges.MIN_SIZE), height = max(s.height, Ranges.MIN_SIZE)))

    val page = when (sample) {
        LayoutSample.CAPTIONS -> CaptionScreen(style).render(
            CaptionScreen.State(recentLines().ifEmpty { CalibrationText.SAMPLE_CAPTIONS })
        )
        // Rulers and custom text go out unwrapped, so the firmware's own wrapping shows what fits.
        LayoutSample.COLUMNS -> GlassesPage(listOf(style.box(CalibrationText.columnRuler(style.maxCols + 20))))
        LayoutSample.LINES -> GlassesPage(listOf(style.box(CalibrationText.lineRuler(style.maxLines + 4))))
        LayoutSample.CUSTOM -> GlassesPage(
            listOf(
                style.box(
                    if (wrapCustom) CaptionLayout.wrap(listOf(customText), style.maxCols, style.maxLines, style.maxBytes)
                    else customText
                )
            )
        )
    }
    val structure = page.structureProblems()
    val warnings = page.limitWarnings()
    val sendable = structure.isEmpty() && (warnings.isEmpty() || !enforceLimits)

    // Live mode: send each change once the sliders (or typing) settle.
    LaunchedEffect(page, live, glassesReady, enforceLimits) {
        if (live && glassesReady && sendable) {
            delay(250)
            onSend(page, enforceLimits)
        }
    }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LensPreview(
            page = page,
            style = style,
            editableText = if (sample == LayoutSample.CUSTOM) customText else null,
            onEditableTextChange = onCustomTextChange,
        )
        if (!glassesReady) {
            Text(
                "Glasses not connected. Connect on the Glasses tab to see changes on the lens.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        for (p in structure) {
            Text("Not sendable: $p", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        for (w in warnings) {
            Text(
                (if (enforceLimits) "Blocked: " else "Past documented limit, sent anyway: ") + w,
                style = MaterialTheme.typography.bodySmall,
                color = if (enforceLimits) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
            )
        }

        // Wraps onto a second line when large system text sizes don't leave room for all four chips.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (s in LayoutSample.entries) {
                FilterChip(selected = sample == s, onClick = { onSampleChange(s) }, label = { Text(s.label) })
            }
        }
        Text(
            "${page.boxes.single().text.toByteArray(Charsets.UTF_8).size} bytes",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
        if (sample != LayoutSample.CUSTOM) Text(
            when (sample) {
                LayoutSample.CAPTIONS -> "Recent captions (or samples) laid out with the settings below."
                LayoutSample.COLUMNS -> "One unbroken row. Each group of ten ends with its group number: " +
                    "if the first lens line ends \"…12345678\" after a group ending in 3, 38 characters fit. " +
                    "Set Characters per line to that."
                LayoutSample.LINES -> "Numbered lines. The last one fully visible is how many lines fit; set Lines to that."
                LayoutSample.CUSTOM -> ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (sample == LayoutSample.CUSTOM) {
            SwitchRow("Wrap with Characters per line and Lines", wrapCustom) { wrapCustom = it }
        }
        SwitchRow("Send every change to the glasses", live) { live = it }
        SwitchRow("Block values past documented limits", enforceLimits, onEnforceLimitsChange)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSend(page, enforceLimits) }, enabled = glassesReady && sendable) { Text("Send now") }
            OutlinedButton(onClick = { update(CaptionScreen.Style()) }) { Text("Reset") }
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("CaptionScreen.Style", style.toKotlin()))
            }) { Text("Copy as code") }
        }
        HorizontalDivider()

        Text("Text", style = MaterialTheme.typography.labelLarge)
        IntControl("Characters per line", style.maxCols, Ranges.COLS) { update(style.copy(maxCols = it)) }
        IntControl("Lines", style.maxLines, Ranges.LINES) { update(style.copy(maxLines = it)) }
        IntControl("Byte limit (documented: ${GlassesDisplay.MAX_TEXT_BYTES})", style.maxBytes, Ranges.BYTES, step = 10) {
            update(style.copy(maxBytes = it))
        }
        SwitchRow("Show original text above English", style.showSourceText) { update(style.copy(showSourceText = it)) }
        HorizontalDivider()

        Text("Box (documented display: ${GlassesDisplay.WIDTH} × ${GlassesDisplay.HEIGHT} px)", style = MaterialTheme.typography.labelLarge)
        IntControl("X", style.x, Ranges.X) { update(style.copy(x = it)) }
        IntControl("Y", style.y, Ranges.Y) { update(style.copy(y = it)) }
        IntControl("Width", style.width, Ranges.WIDTH) { update(style.copy(width = it)) }
        IntControl("Height", style.height, Ranges.HEIGHT) { update(style.copy(height = it)) }
        IntControl("Padding", style.padding, Ranges.PADDING) { update(style.copy(padding = it)) }
        IntControl("Border width", style.borderWidth, Ranges.BORDER) { update(style.copy(borderWidth = it)) }
        IntControl("Border grey (0–15)", style.borderColor, 0..15) { update(style.copy(borderColor = it)) }
        IntControl("Corner radius", style.borderRadius, Ranges.RADIUS) { update(style.copy(borderRadius = it)) }

        Text(
            style.toKotlin(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
    }
}

/** Slider ranges, deliberately past the documented limits for testing. */
private object Ranges {
    const val MIN_SIZE = 20
    const val MARGIN = 64  // how far past each display edge the preview and X/Y reach
    val COLS = 10..200
    val LINES = 1..30
    val BYTES = 100..3000
    val X = -MARGIN..GlassesDisplay.WIDTH + MARGIN
    val Y = -MARGIN..GlassesDisplay.HEIGHT + MARGIN
    val WIDTH = MIN_SIZE..GlassesDisplay.WIDTH + 2 * MARGIN
    val HEIGHT = MIN_SIZE..GlassesDisplay.HEIGHT + 2 * MARGIN
    val PADDING = 0..80
    val BORDER = 0..20
    val RADIUS = 0..40
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A labelled slider with −/+ buttons for exact values. */
@Composable
private fun IntControl(label: String, value: Int, range: IntRange, step: Int = 1, onChange: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { onChange((value - step).coerceIn(range)) }, enabled = value > range.first) { Text("−") }
            Text(
                value.toString(),
                Modifier.widthIn(min = 44.dp),
                textAlign = TextAlign.Center,
                fontFamily = FontFamily.Monospace,
            )
            TextButton(onClick = { onChange((value + step).coerceIn(range)) }, enabled = value < range.last) { Text("+") }
        }
        Slider(
            value = value.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { onChange(((it / step).roundToInt() * step).coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

/**
 * The display scaled to the phone with a [Ranges.MARGIN] border around it, so a box past an edge
 * stays visible. With [editableText] set, the box itself is a text field shaped by the settings.
 */
@Composable
private fun LensPreview(
    page: GlassesPage,
    style: CaptionScreen.Style,
    editableText: String?,
    onEditableTextChange: (String) -> Unit,
) {
    val box = page.boxes.single()
    val regionW = GlassesDisplay.WIDTH + 2 * Ranges.MARGIN
    val regionH = GlassesDisplay.HEIGHT + 2 * Ranges.MARGIN
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(regionW.toFloat() / regionH)
            .clipToBounds()
    ) {
        val dpPerPx: Dp = maxWidth / regionW
        val density = LocalDensity.current
        val pad = box.padding.coerceAtLeast(0)
        val innerHeightPx = (box.height - 2 * pad).coerceAtLeast(1)
        // Line height so that maxLines lines fill the box: a rough stand-in for the lens font.
        val lineDp = dpPerPx * innerHeightPx / max(style.maxLines, 1)
        val fontSize = with(density) { (lineDp * 0.8f).toSp() }

        Canvas(Modifier.matchParentSize()) {
            val k = size.width / regionW
            val origin = Offset(Ranges.MARGIN * k, Ranges.MARGIN * k)
            drawRect(OUTSIDE_BG)
            drawRect(LENS_BG, origin, Size(GlassesDisplay.WIDTH * k, GlassesDisplay.HEIGHT * k))
            drawRect(
                LENS_FRAME, origin, Size(GlassesDisplay.WIDTH * k, GlassesDisplay.HEIGHT * k),
                style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
            )
            val topLeft = Offset((box.x + Ranges.MARGIN) * k, (box.y + Ranges.MARGIN) * k)
            val boxSize = Size(box.width * k, box.height * k)
            drawRect(LENS_DIM, topLeft, boxSize, style = Stroke(width = max(1f, box.borderWidth * k)))

            if (editableText == null) {
                val inner = pad * k
                val lineHeight = (innerHeightPx * k) / max(style.maxLines, 1)
                val paint = Paint().apply {
                    color = android.graphics.Color.rgb(0x5c, 0xf0, 0x8a)
                    typeface = Typeface.MONOSPACE
                    textSize = lineHeight * 0.8f
                    isAntiAlias = true
                }
                clipRect(topLeft.x, topLeft.y, topLeft.x + boxSize.width, topLeft.y + boxSize.height) {
                    box.text.split('\n').forEachIndexed { i, line ->
                        drawContext.canvas.nativeCanvas.drawText(
                            line, topLeft.x + inner, topLeft.y + inner + lineHeight * (i + 0.8f), paint,
                        )
                    }
                }
            }
        }

        if (editableText != null) {
            Box(
                Modifier
                    .offset(dpPerPx * (box.x + Ranges.MARGIN + pad), dpPerPx * (box.y + Ranges.MARGIN + pad))
                    .size(dpPerPx * (box.width - 2 * pad).coerceAtLeast(1), dpPerPx * innerHeightPx)
                    .clipToBounds()
            ) {
                BasicTextField(
                    value = editableText,
                    onValueChange = onEditableTextChange,
                    textStyle = TextStyle(color = LENS_TEXT, fontFamily = FontFamily.Monospace, fontSize = fontSize),
                    cursorBrush = SolidColor(LENS_TEXT),
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}

private val OUTSIDE_BG = Color(0xFF020604)
private val LENS_BG = Color(0xFF06140C)
private val LENS_FRAME = Color(0xFF3FA866)
private val LENS_DIM = Color(0xFF2E7A48)
private val LENS_TEXT = Color(0xFF5CF08A)
