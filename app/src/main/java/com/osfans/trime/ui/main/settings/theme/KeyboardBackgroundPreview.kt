// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ui.main.settings.theme

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import com.osfans.trime.R
import com.osfans.trime.data.theme.BuiltinFallbackColors
import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.PresetKey
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.ime.keyboard.KeyActionDefinition
import com.osfans.trime.ime.keyboard.KeyBehavior
import com.osfans.trime.ime.keyboard.isIconFont
import com.osfans.trime.util.ColorUtils
import kotlin.math.max

/**
 * Keyboard render used inside the background editor.
 *
 * Two draw paths share the same region geometry (the background image
 * cover-fitted to the whole card, a candidate strip on top, the keyboard band
 * below):
 *
 * - With a [RenderModel] (方案 A) the content is drawn from the *real* theme
 *   data: the selected scheme×mode palette, the theme's fallback colors and
 *   the real `preset_keyboards` layout ([TextKeyboard]) wrapped with the same
 *   weight algorithm the IME uses ([PreviewKeyboardLayout]). Key faces, key
 *   text/symbol colors, borders/corners, gaps and the candidate strip all come
 *   from the palette, so the user can judge on the actual custom background
 *   whether the keys stay readable.
 * - Without a model (no scheme/theme usable) it falls back to the neutral
 *   placeholder grid, so the editor never shows a blank card.
 *
 * Deliberate preview simplifications (not pixel-perfect IME rendering, see the
 * handoff doc): no `InputView`/Rime reuse; no icon-font glyphs (`ic@…` labels);
 * keys are drawn in their idle appearance, so scheme keys that only differ via
 * off/on state classes without per-key colors approximate to the normal key
 * colors; candidate text is sample copy, not the live candidate list.
 */
class KeyboardBackgroundPreview
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var background: Drawable? = null

    /** width / height of the keyboard render area. */
    private var aspect = DEFAULT_ASPECT

    /** Real-data render model (方案 A); null keeps the placeholder path. */
    private var model: RenderModel? = null
        set(value) {
            if (field == value) return
            field = value
            resolverCache.clear()
            actionDefinitionCache.clear()
            fallback = if (value == null) {
                emptyMap()
            } else {
                BuiltinFallbackColors + value.themeFallbackColors
            }
            invalidate()
        }

    private var fallback: Map<String, String> = emptyMap()
    private val resolverCache = mutableMapOf<String, Int>()

    /**
     * Parsed click/long-click definitions per (behavior, token). Drawing
     * resolves them once per token instead of re-running
     * [KeyActionDefinition.parse] on the UI thread for every key every frame;
     * cleared together with the resolver when the model changes.
     */
    private val actionDefinitionCache =
        mutableMapOf<Pair<KeyBehavior, KeyActionToken>, KeyActionDefinition>()

    private val sampleCandidates: List<String> =
        context.resources.getStringArray(R.array.keyboard_background_preview_candidates).toList()

    private val fallbackPaint = Paint().apply { color = FALLBACK_BG }
    private val candidateShade = Paint().apply { color = CANDIDATE_SHADE }
    private val separator = Paint().apply {
        color = SEPARATOR_COLOR
        strokeWidth = context.resources.displayMetrics.density
    }
    private val keyFill = Paint().apply { color = KEY_FILL }
    private val keyStroke = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density
        color = KEY_STROKE
    }
    private val density = context.resources.displayMetrics.density

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    /** Show [bg] (or a neutral placeholder when null). Not named
     * `setBackgroundDrawable` to avoid clashing with the View API. */
    fun setPreviewBackground(bg: Drawable?) {
        background = bg
        invalidate()
    }

    fun setAspectRatio(value: Float) {
        if (value > 0f && value != aspect) {
            aspect = value
            requestLayout()
        }
    }

    fun setRenderModel(value: RenderModel?) {
        model = value
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (width / aspect).toInt().coerceAtLeast(1)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawCover(canvas)
        val renderModel = model
        if (renderModel == null) {
            drawPlaceholderOverlay(canvas)
        } else {
            drawCandidateStrip(canvas, renderModel)
            drawKeys(canvas, renderModel)
        }
    }

    // ── common background cover ────────────────────────────────────────────

    private fun drawCover(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val bg = background
        if (bg == null) {
            canvas.drawRect(0f, 0f, w, h, fallbackPaint)
            return
        }
        val iw = bg.intrinsicWidth.toFloat()
        val ih = bg.intrinsicHeight.toFloat()
        if (iw <= 0f || ih <= 0f) {
            bg.setBounds(0, 0, width, height)
            bg.draw(canvas)
            return
        }
        val scale = max(w / iw, h / ih)
        val bw = iw * scale
        val bh = ih * scale
        bg.setBounds(
            ((w - bw) / 2f).toInt(),
            ((h - bh) / 2f).toInt(),
            ((w + bw) / 2f).toInt(),
            ((h + bh) / 2f).toInt(),
        )
        bg.draw(canvas)
    }

    // ── placeholder path (no usable theme) ─────────────────────────────────

    private fun drawPlaceholderOverlay(canvas: Canvas) {
        drawPlaceholderCandidateBar(canvas)
        drawKeyGrid(canvas)
    }

    private fun drawPlaceholderCandidateBar(canvas: Canvas) {
        val h = height.toFloat()
        val barHeight = h * CANDIDATE_BAR_FRACTION
        canvas.drawRect(0f, 0f, width.toFloat(), barHeight, candidateShade)
        canvas.drawLine(0f, barHeight, width.toFloat(), barHeight, separator)
    }

    private fun drawKeyGrid(canvas: Canvas) {
        val h = height.toFloat()
        val top = h * CANDIDATE_BAR_FRACTION
        val area = RectF(0f, top, width.toFloat(), h)
        val gap = density * 2f
        val rows = KEY_ROW_COUNT
        val columns = KEY_COLUMN_COUNT
        val cellW = (area.width() - gap * (columns + 1)) / columns
        val cellH = (area.height() - gap * (rows + 1)) / rows
        val corner = density * 4f
        for (row in 0 until rows) {
            for (col in 0 until columns) {
                val left = area.left + gap + col * (cellW + gap)
                val topY = area.top + gap + row * (cellH + gap)
                val rect = RectF(left, topY, left + cellW, topY + cellH)
                canvas.drawRoundRect(rect, corner, corner, keyFill)
                canvas.drawRoundRect(rect, corner, corner, keyStroke)
            }
        }
    }

    // ── real-color candidate strip ─────────────────────────────────────────

    private fun drawCandidateStrip(
        canvas: Canvas,
        model: RenderModel,
    ) {
        val unit = unitPx(model)
        val stripHeight = model.candidateHeightDp * unit
        if (stripHeight <= 0f) return

        // Surface in the real candidate-window color, drawn translucent so the
        // underlying background image stays visible (same overlay semantics as
        // the old placeholder bar, but now palette-driven).
        val surface = resolveColor("text_back_color")
            ?: resolveColor("back_color")
            ?: Color.TRANSPARENT
        if (surface != Color.TRANSPARENT) {
            canvas.drawRect(
                0f,
                0f,
                width.toFloat(),
                stripHeight,
                Paint().apply {
                    color = withOverlayAlpha(surface, CANDIDATE_SURFACE_ALPHA)
                },
            )
        }
        val separatorColor = resolveColor("candidate_separator_color")
        if (separatorColor != null) {
            separator.color = separatorColor
            separator.strokeWidth = max(density, SEPARATOR_MIN_DP * unit)
            canvas.drawLine(0f, stripHeight, width.toFloat(), stripHeight, separator)
        }

        val fallbackText =
            if (surface != Color.TRANSPARENT && ColorUtils.isContrastedDark(surface)) {
                FALLBACK_TEXT_ON_DARK
            } else {
                FALLBACK_TEXT_ON_LIGHT
            }
        val candidateText = resolveColor("candidate_text_color") ?: fallbackText
        val commentText = resolveColor("comment_text_color") ?: fallbackText
        val hilitedBack = resolveColor("hilited_candidate_back_color")
        val hilitedText =
            resolveColor("hilited_candidate_text_color")
                ?: resolveColor("hilited_text_color")
                ?: candidateText
        val hilitedComment = resolveColor("hilited_comment_text_color") ?: commentText

        val mainTextSize =
            (model.style.candidateTextSize.takeIf { it > 0f } ?: DEFAULT_TEXT_SIZE_DP) * unit
        val commentTextSize =
            (model.style.commentTextSize.takeIf { it > 0f } ?: DEFAULT_COMMENT_SIZE_DP) * unit
        val chipHeight = stripHeight - CANDIDATE_CHIP_V_INSET_DP * unit * 2f
        val chipTop = (stripHeight - chipHeight) / 2f
        val chipCorner = CANDIDATE_CHIP_CORNER_DP * unit
        // Mirror the IME: the candidate/input-bar content is inset by the same
        // keyboard padding as the key board, so neither runs into the edge.
        val sidePad = keyboardSidePaddingPx(model, unit)
        var cursorX = sidePad + CANDIDATE_PADDING_DP * unit

        sampleCandidates.forEachIndexed { index, text ->
            if (text.isEmpty() || chipHeight <= 0f) return@forEachIndexed
            val isHilited = index == 0
            val textColor = if (isHilited) hilitedText else candidateText
            val chipWidth =
                drawCandidateChip(
                    canvas = canvas,
                    text = text,
                    textColor = textColor,
                    commentColor = if (isHilited) hilitedComment else commentText,
                    textSize = mainTextSize,
                    commentSize = commentTextSize,
                    chipTop = chipTop,
                    chipHeight = chipHeight,
                    startX = cursorX,
                    back = if (isHilited) hilitedBack else null,
                    corner = chipCorner,
                    unit = unit,
                    showComment = isHilited,
                )
            cursorX += chipWidth + CANDIDATE_SPACING_DP * unit
            if (cursorX > width - sidePad) return
        }
    }

    /** Draw one sample candidate chip; returns its content width in px. */
    private fun drawCandidateChip(
        canvas: Canvas,
        text: String,
        textColor: Int,
        commentColor: Int,
        textSize: Float,
        commentSize: Float,
        chipTop: Float,
        chipHeight: Float,
        startX: Float,
        back: Int?,
        corner: Float,
        unit: Float,
        showComment: Boolean,
    ): Float {
        labelPaint.color = textColor
        labelPaint.textSize = textSize
        val textWidth = labelPaint.measureText(text)
        var commentWidth = 0f
        if (showComment && commentSize > 0f) {
            symbolPaint.color = commentColor
            symbolPaint.textSize = commentSize
            commentWidth = symbolPaint.measureText(SAMPLE_COMMENT)
        }
        val textPad = CANDIDATE_CHIP_TEXT_PAD_DP * unit
        val chipWidth = textWidth + commentWidth + textPad * 2f
        val rect = RectF(startX, chipTop, startX + chipWidth, chipTop + chipHeight)
        if (back != null) {
            canvas.drawRoundRect(rect, corner, corner, Paint().apply { color = back })
        }
        val labelMetrics = labelPaint.fontMetrics
        val baseline = rect.centerY() - (labelMetrics.ascent + labelMetrics.descent) / 2f
        canvas.drawText(text, startX + textPad, baseline, labelPaint)
        if (commentWidth > 0f) {
            val commentStart = startX + textPad + textWidth + CANDIDATE_CHIP_COMMENT_GAP_DP * unit
            val commentMetrics = symbolPaint.fontMetrics
            val commentBaseline = rect.centerY() - (commentMetrics.ascent + commentMetrics.descent) / 2f
            canvas.drawText(SAMPLE_COMMENT, commentStart, commentBaseline, symbolPaint)
        }
        return chipWidth
    }

    // ── real keys ──────────────────────────────────────────────────────────

    private fun drawKeys(
        canvas: Canvas,
        model: RenderModel,
    ) {
        val keyboard = model.keyboard ?: return
        val unit = unitPx(model)
        val bandTop = model.candidateHeightDp * unit
        val bandHeight = height - bandTop
        if (bandHeight <= 0f) return
        val widthPx = width.toFloat()

        val defaultWidthWeight = keyboard.width
        val defaultRowHeightDp =
            keyboard.height.takeIf { it > 0f }
                ?: model.style.keyHeight.takeIf { it > 0 }
                    ?.toFloat()
                ?: 0f
        val layout =
            PreviewKeyboardLayout.build(
                keys = keyboard.keys.map {
                    PreviewKeyInput(it.width, it.height, it.hasClickAction)
                },
                defaultWidthWeight = defaultWidthWeight,
                defaultRowHeightDp = defaultRowHeightDp,
                maxColumns = keyboard.columns,
            )

        // The real keyboard lays its rows out on the window width minus the
        // style's keyboard padding on each side (Keyboard.allowedWidth), so the
        // preview insets the key board the same way — without this the rows
        // run flush into the card edge and look side-clipped.
        val sidePadPx = keyboardSidePaddingPx(model, unit)
        val boardWidth = widthPx - sidePadPx * 2f
        if (boardWidth <= 0f) return
        val horizontalGapDp = firstNonZero(
            keyboard.horizontalGap.takeIf { it > 0 },
            model.style.horizontalGap,
        ).toFloat() * unit
        val verticalGapDp = firstNonZero(
            keyboard.verticalGap.takeIf { it > 0 },
            model.style.verticalGap,
        ).toFloat() * unit
        val cornerDp = firstNonZero(
            keyboard.roundCorner.takeIf { it >= 0f },
            model.style.roundCorner,
        ) * unit
        val borderDp = firstNonZero(
            keyboard.keyBorder.takeIf { it >= 0 },
            model.style.keyBorder,
        ).toFloat() * unit

        // Scale each row's raw dp height so the rows exactly fill the band
        // (the IME scales rows into the keyboard height the same way); the
        // last row absorbs the rounding remainder.
        val rawHeightSum = layout.rows.sumOf { it.rawHeightDp.toDouble() }.toFloat()
        val rowTops = mutableListOf<Float>()
        val rowHeights = mutableListOf<Float>()
        var cursor = 0f
        layout.rows.forEachIndexed { index, row ->
            if (index == layout.rows.lastIndex) {
                rowHeights += bandHeight - cursor
            } else {
                val height =
                    if (rawHeightSum > 0f) {
                        bandHeight * row.rawHeightDp / rawHeightSum
                    } else {
                        bandHeight / layout.rows.size
                    }
                rowHeights += height
            }
            rowTops += cursor
            cursor += rowHeights.last()
        }

        labelPaint.typeface = null
        symbolPaint.typeface = null

        layout.rows.forEachIndexed { rowIndex, row ->
            if (row.isEmpty) return@forEachIndexed
            // Rows are positioned inside the band below the candidate strip —
            // rowTops are band-relative, so the candidate height is added back
            // here (an earlier version forgot this and drew the first key row
            // on top of the candidate strip).
            val rowTop = bandTop + rowTops[rowIndex]
            val rowHeight = rowHeights[rowIndex]
            for (placed in row.keys) {
                val textKey = keyboard.keys[placed.inputIndex]
                val left =
                    sidePadPx + placed.leftWeight / PreviewKeyboardLayout.MAX_TOTAL_WEIGHT * boardWidth
                val keyWidth =
                    placed.widthWeight / PreviewKeyboardLayout.MAX_TOTAL_WEIGHT * boardWidth
                val inner = RectF(
                    left + horizontalGapDp / 2f,
                    rowTop + verticalGapDp / 2f,
                    left + keyWidth - horizontalGapDp / 2f,
                    rowTop + rowHeight - verticalGapDp / 2f,
                )
                if (inner.width() <= 0f || inner.height() <= 0f) return@forEachIndexed
                drawKey(canvas, textKey, inner, model, keyboard, cornerDp, borderDp, unit)
            }
        }
    }

    private fun drawKey(
        canvas: Canvas,
        key: TextKeyboard.TextKey,
        rect: RectF,
        model: RenderModel,
        keyboard: TextKeyboard,
        cornerPx: Float,
        borderPx: Float,
        unit: Float,
    ) {
        // Click/long-click definitions come from the exact pure layer the IME
        // uses pre-render (KeyActionDefinition.parse): preset labels, letter
        // labels and the sticky/functional appearance classes all match Key.
        // Results are cached per token so the UI thread never re-parses them
        // on every redraw.
        val clickDef = actionDefinition(key, KeyBehavior.CLICK, model)
        val longDef = actionDefinition(key, KeyBehavior.LONG_CLICK, model)
        // Idle appearance: Key.getBackgroundDrawable types sticky/functional
        // keys (appearance 1) to the off-key colors; everything else uses the
        // plain key colors. Modifier/on states never occur in a static preview.
        val offStateColors = clickDef?.isSticky == true || clickDef?.isFunctional == true
        val keyBack =
            perKeyColor(key.keyBackColor)
                ?: resolveColor(if (offStateColors) "off_key_back_color" else "key_back_color")
        val cornerRadius = max(0f, cornerPx)
        if (keyBack != null) {
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, Paint().apply { color = keyBack })
        }
        val borderColor = resolveColor("key_border_color")
        if (borderColor != null && borderPx > 0f) {
            canvas.drawRoundRect(
                rect,
                cornerRadius,
                cornerRadius,
                Paint().apply {
                    style = Paint.Style.STROKE
                    strokeWidth = borderPx
                    color = borderColor
                },
            )
        }

        val backUsed = keyBack ?: FALLBACK_KEY_FILL
        val defaultText = if (ColorUtils.isContrastedDark(backUsed)) {
            FALLBACK_TEXT_ON_DARK
        } else {
            FALLBACK_TEXT_ON_LIGHT
        }
        val textColor =
            perKeyColor(key.keyTextColor)
                ?: resolveColor(if (offStateColors) "off_key_text_color" else "key_text_color")
                ?: defaultText
        val symbolColor =
            perKeyColor(key.keySymbolColor)
                ?: resolveColor(if (offStateColors) "off_key_symbol_color" else "key_symbol_color")
                ?: resolveColor("key_symbol_color")
                ?: defaultText

        val label = previewKeyLabel(key, keyboard, model, clickDef)
        if (label.isNotEmpty() && !label.isIconFont) {
            drawKeyLabel(canvas, key, label, rect, textColor, model, keyboard, unit)
        }
        val symbol = previewKeySymbol(key, keyboard, longDef)
        if (symbol.isNotEmpty() && !symbol.isIconFont) {
            drawKeySymbol(canvas, key, symbol, rect, symbolColor, model, keyboard, unit, top = true)
        }
        if (key.hint.isNotEmpty()) {
            drawKeySymbol(canvas, key, key.hint, rect, symbolColor, model, keyboard, unit, top = false)
        }
    }

    private fun drawKeyLabel(
        canvas: Canvas,
        key: TextKeyboard.TextKey,
        label: String,
        rect: RectF,
        color: Int,
        model: RenderModel,
        keyboard: TextKeyboard,
        unit: Float,
    ) {
        val sizeDp =
            key.keyTextSize.takeIf { it > 0f }
                ?: if (label.length > 1) {
                    model.style.keyLongTextSize.takeIf { it > 0f } ?: model.style.keyTextSize
                } else {
                    model.style.keyTextSize
                }
        val size = sizeDp.takeIf { it > 0f } ?: DEFAULT_TEXT_SIZE_DP
        labelPaint.color = color
        labelPaint.textSize = size * unit
        val offsetX = keyOffsetX(key, keyboard, model) * unit
        val offsetY = keyOffsetY(key, keyboard, model) * unit
        val centerX = rect.centerX() + offsetX
        val centerY = rect.centerY() + offsetY
        val fontMetrics = labelPaint.fontMetrics
        val baseline = centerY - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText(label, centerX, baseline, labelPaint)
    }

    private fun drawKeySymbol(
        canvas: Canvas,
        key: TextKeyboard.TextKey,
        text: String,
        rect: RectF,
        color: Int,
        model: RenderModel,
        keyboard: TextKeyboard,
        unit: Float,
        top: Boolean,
    ) {
        val sizeDp =
            key.symbolTextSize.takeIf { it > 0f }
                ?: model.style.symbolTextSize.takeIf { it > 0f }
                ?: model.style.keyTextSize
        val size = sizeDp.takeIf { it > 0f } ?: DEFAULT_SYMBOL_SIZE_DP
        symbolPaint.color = color
        symbolPaint.textSize = size * unit
        val offsetX = if (top) keySymbolOffsetX(key, keyboard, model) else keyHintOffsetX(key, keyboard, model)
        val offsetY = if (top) keySymbolOffsetY(key, keyboard, model) else keyHintOffsetY(key, keyboard, model)
        val centerX = rect.centerX() + offsetX * unit
        val fontMetrics = symbolPaint.fontMetrics
        val lineY =
            if (top) {
                rect.top - fontMetrics.top + offsetY * unit
            } else {
                rect.bottom - fontMetrics.bottom + offsetY * unit
            }
        canvas.drawText(text, centerX, lineY, symbolPaint)
    }

    // ── effective key metrics (mirror the IME's firstNonZero fallbacks) ─────

    private fun keyOffsetX(
        key: TextKeyboard.TextKey,
        keyboard: TextKeyboard,
        model: RenderModel,
    ): Float = firstNonZero(
        key.keyTextOffsetX.takeIf { it != 0f },
        keyboard.keyTextOffsetX.takeIf { it != 0f },
        model.style.keyTextOffsetX,
    )

    private fun keyOffsetY(
        key: TextKeyboard.TextKey,
        keyboard: TextKeyboard,
        model: RenderModel,
    ): Float = firstNonZero(
        key.keyTextOffsetY.takeIf { it != 0f },
        keyboard.keyTextOffsetY.takeIf { it != 0f },
        model.style.keyTextOffsetY,
    )

    private fun keySymbolOffsetX(
        key: TextKeyboard.TextKey,
        keyboard: TextKeyboard,
        model: RenderModel,
    ): Float = firstNonZero(
        key.keySymbolOffsetX.takeIf { it != 0f },
        keyboard.keySymbolOffsetX.takeIf { it != 0f },
        model.style.keySymbolOffsetX,
    )

    private fun keySymbolOffsetY(
        key: TextKeyboard.TextKey,
        keyboard: TextKeyboard,
        model: RenderModel,
    ): Float = firstNonZero(
        key.keySymbolOffsetY.takeIf { it != 0f },
        keyboard.keySymbolOffsetY.takeIf { it != 0f },
        model.style.keySymbolOffsetY,
    )

    private fun keyHintOffsetX(
        key: TextKeyboard.TextKey,
        keyboard: TextKeyboard,
        model: RenderModel,
    ): Float = firstNonZero(
        key.keyHintOffsetX.takeIf { it != 0f },
        keyboard.keyHintOffsetX.takeIf { it != 0f },
        model.style.keyHintOffsetX,
    )

    private fun keyHintOffsetY(
        key: TextKeyboard.TextKey,
        keyboard: TextKeyboard,
        model: RenderModel,
    ): Float = firstNonZero(
        key.keyHintOffsetY.takeIf { it != 0f },
        keyboard.keyHintOffsetY.takeIf { it != 0f },
        model.style.keyHintOffsetY,
    )

    /** Cached [KeyActionDefinition.parse] for one (behavior, token) pair. */
    private fun actionDefinition(
        key: TextKeyboard.TextKey,
        behavior: KeyBehavior,
        model: RenderModel,
    ): KeyActionDefinition? {
        val token = key.behaviors[behavior] ?: return null
        return actionDefinitionCache.getOrPut(behavior to token) {
            KeyActionDefinition.parse(token, model.presetKeys)
        }
    }

    /**
     * Preview label of a key, resolved from the same pure definition layer the
     * IME draws from (`KeyActionDefinition.parse`, which carries the theme's
     * `preset_keys`): named tokens (`14keyqw`, `num1`, `BackSpace`, …),
     * letters/digits and inline `{commit,text,label}` maps all go through it.
     * Static yaml `label` wins; a static preview has no shift/composing state,
     * so labels are rendered in the idle Chinese-mode appearance (single-char
     * labels upper-cased only when the keyboard's `label_transform` says so).
     */
    private fun previewKeyLabel(
        key: TextKeyboard.TextKey,
        keyboard: TextKeyboard,
        model: RenderModel,
        clickDef: KeyActionDefinition?,
    ): String {
        if (key.label.isNotEmpty()) return key.label
        return idleDefinitionLabel(clickDef, keyboard)
    }

    /**
     * Preview symbol of a key: the static `label_symbol` wins; otherwise the
     * long-press definition's idle label is used, mirroring how the IME fills
     * `symbolLabel` from the long-press action.
     */
    private fun previewKeySymbol(
        key: TextKeyboard.TextKey,
        keyboard: TextKeyboard,
        longDef: KeyActionDefinition?,
    ): String {
        if (key.labelSymbol.isNotEmpty()) return key.labelSymbol
        return idleDefinitionLabel(longDef, keyboard)
    }

    /**
     * Idle label of an action definition: for toggle/state presets the off
     * state text; otherwise the parsed label (already printer-resolved for
     * plain letters/digits), with the keyboard's single-char uppercase rule
     * applied — the same result `KeyAction.getLabel` yields with an idle
     * (non-shifted, non-composing) Rime state. Space keys whose label is empty
     * stay empty in the preview (the IME would print the live schema name).
     */
    private fun idleDefinitionLabel(
        def: KeyActionDefinition?,
        keyboard: TextKeyboard,
    ): String {
        if (def == null) return ""
        if (def.states.isNotEmpty() && def.toggle.isNotEmpty()) return def.states.first()
        val label = def.label
        return if (label.length == 1 && keyboard.labelTransform == TextKeyboard.LabelTransform.UPPERCASE) {
            label.uppercase()
        } else {
            label
        }
    }

    // ── palette resolution (mirrors ColorManager, stateless for the preview) ─

    private fun perKeyColor(name: String): Int? {
        if (name.isEmpty()) return null
        return resolveColor(name)
    }

    private fun resolveColor(key: String): Int? {
        if (key.isEmpty()) return null
        resolverCache[key]?.let { return it }
        val value = resolveTerminal(key, HashSet()) ?: return null
        val color = runCatching { ColorUtils.parseColor(value) }.getOrNull()
            ?: runCatching { ColorUtils.parseColor(key) }.getOrNull()
            ?: return null
        resolverCache[key] = color
        return color
    }

    private fun resolveTerminal(
        key: String,
        visited: MutableSet<String>,
    ): String? {
        if (!visited.add(key)) return null
        val paletteValue = model?.palette?.get(key).orEmpty()
        if (paletteValue.isNotEmpty()) return paletteValue
        val next = fallback[key] ?: return null
        return resolveTerminal(next, visited)
    }

    private fun withOverlayAlpha(
        color: Int,
        alpha: Int,
    ): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    /**
     * Horizontal keyboard padding in px (portrait), mirroring
     * `Keyboard.allowedWidth` / the IME's left/right padding spaces: rows are
     * laid out on the board width minus this on each side.
     */
    private fun keyboardSidePaddingPx(
        model: RenderModel,
        unit: Float,
    ): Float = (model.style.keyboardPadding.takeIf { it >= 0 } ?: 0).toFloat() * unit

    private fun unitPx(model: RenderModel): Float {
        val total = model.candidateHeightDp + model.keyboardHeightDp
        return if (total > 0 && height > 0) {
            height.toFloat() / total
        } else {
            density
        }
    }

    private fun firstNonZero(vararg values: Float?): Float = values.firstOrNull { it != null && it != 0f } ?: 0f

    private fun firstNonZero(vararg values: Int?): Int = values.firstOrNull { it != null && it != 0 } ?: 0

    companion object {
        // 360dp reference width over (candidate 28dp + keyboard 250dp); only
        // used before the theme provides real dims.
        val DEFAULT_ASPECT = 360f / 278f

        const val CANDIDATE_BAR_FRACTION = 0.1f
        const val KEY_ROW_COUNT = 4
        const val KEY_COLUMN_COUNT = 10

        /** Fallback font sizes when the theme style carries none. */
        const val DEFAULT_TEXT_SIZE_DP = 15f
        const val DEFAULT_COMMENT_SIZE_DP = 10f
        const val DEFAULT_SYMBOL_SIZE_DP = 12f

        /** Sample candidate comment shown next to the primary sample chip. */
        const val SAMPLE_COMMENT = "①"

        /** Sample-chip geometry (dp). */
        const val CANDIDATE_PADDING_DP = 8f
        const val CANDIDATE_SPACING_DP = 10f
        const val CANDIDATE_CHIP_TEXT_PAD_DP = 6f
        const val CANDIDATE_CHIP_COMMENT_GAP_DP = 4f
        const val CANDIDATE_CHIP_V_INSET_DP = 2f
        const val CANDIDATE_CHIP_CORNER_DP = 6f
        const val SEPARATOR_MIN_DP = 1f

        /** Translucency of the real candidate-surface color over the image. */
        const val CANDIDATE_SURFACE_ALPHA = 96

        // Placeholder-path tints (UI-only, not config data).
        val FALLBACK_BG = Color.argb(255, 233, 235, 240)
        val CANDIDATE_SHADE = Color.argb(28, 0, 0, 0)
        val SEPARATOR_COLOR = Color.argb(40, 0, 0, 0)
        val KEY_FILL = Color.argb(16, 255, 255, 255)
        val KEY_STROKE = Color.argb(46, 0, 0, 0)

        // Real-data path defaults used only when a scheme palette lacks the
        // key entirely (minimal fallback themes).
        val FALLBACK_KEY_FILL = Color.argb(255, 255, 255, 255)
        val FALLBACK_TEXT_ON_LIGHT = Color.argb(255, 32, 32, 32)
        val FALLBACK_TEXT_ON_DARK = Color.argb(255, 232, 232, 232)
    }
}

/**
 * Everything the real renderer needs from the theme data, resolved for one
 * scheme×mode by the editor activity. Deliberately no singletons: the IME
 * process is not required for the settings UI.
 */
data class RenderModel(
    /** Selected scheme palette (light or dark colors) as raw yaml strings. */
    val palette: Map<String, String>,
    /** Theme-level `fallback_colors` (merged over the builtin table inside the view). */
    val themeFallbackColors: Map<String, String>,
    /**
     * Theme `preset_keys`. Passed to [KeyActionDefinition.parse] so preview
     * labels/appearances come from the exact definition layer the IME uses
     * before rendering (named click tokens like `14keyqw`/`num1` resolve their
     * labels here).
     */
    val presetKeys: Map<String, PresetKey>,
    /** Real keyboard layout entry (`preset_keyboards`) chosen for the preview. */
    val keyboard: TextKeyboard?,
    /** Style defaults for text sizes, gaps, corners and offsets. */
    val style: GeneralStyle,
    /** Candidate strip height in dp (same value the crop aspect uses). */
    val candidateHeightDp: Int,
    /** Keyboard band height in dp (same value the crop aspect uses). */
    val keyboardHeightDp: Int,
)
