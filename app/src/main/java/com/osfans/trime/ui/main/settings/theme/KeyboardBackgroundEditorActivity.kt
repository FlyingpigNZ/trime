// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ui.main.settings.theme

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.radiobutton.MaterialRadioButton
import com.osfans.trime.R
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.schema.ImePackageManager
import com.osfans.trime.data.theme.PackageThemeLoader
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeColor
import com.osfans.trime.data.theme.ThemeCustomization
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.util.toast
import com.osfans.trime.util.yaml.Node
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * In-app "change keyboard background" editor.
 *
 * Flow: preview (day/night mode + simplified keyboard render) → pick an image
 * → fixed-aspect draggable/zoomable crop → back to preview with the crop as
 * the pending background → "Apply" persists a PNG into
 * `<workspace>/backgrounds/`, updates `customization.yaml` and refreshes the
 * running theme. "Use default background" removes the customization entry and
 * the slot file again.
 */
class KeyboardBackgroundEditorActivity : AppCompatActivity() {

    private var mode = ThemeCustomization.MODE_LIGHT
    private val modeLightId = View.generateViewId()
    private val modeDarkId = View.generateViewId()
    private lateinit var radioLight: MaterialRadioButton
    private lateinit var radioDark: MaterialRadioButton

    private lateinit var previewHost: FrameLayout
    private lateinit var preview: KeyboardBackgroundPreview
    private lateinit var rootColumn: LinearLayout
    private lateinit var contentCard: MaterialCardView
    private lateinit var mainArea: FrameLayout
    private lateinit var cropHost: FrameLayout
    private lateinit var cropView: KeyboardBackgroundCropView
    private lateinit var actionRow: LinearLayout
    private lateinit var cropRow: LinearLayout
    private lateinit var btnChange: MaterialButton
    private lateinit var btnDefault: MaterialButton
    private lateinit var btnApply: MaterialButton
    private lateinit var btnReset: MaterialButton
    private lateinit var btnCropDone: MaterialButton

    private var pendingBitmap: Bitmap? = null

    /**
     * Current scheme × mode custom background file name, cached by the
     * off-main preview refresh (never read from disk on the main thread).
     * Drives the [缺省] button visibility.
     */
    private var customFileNameCache: String? = null

    /**
     * Style dims snapshot set by the off-main model build; keeps
     * [keyboardAspect] free of theme parsing on the main thread (cold start
     * before the workspace theme finished loading on IO).
     */
    private var cachedCandidateHeightDp = DEFAULT_CANDIDATE_HEIGHT_DP
    private var cachedKeyboardHeightDp = DEFAULT_KEYBOARD_HEIGHT_DP

    /**
     * Keyboard layout id chosen when the editor opened/resumed. Snapshotting it
     * (instead of re-reading `session.lastKeyboard` on every refresh) keeps the
     * preview stable across apply/default refreshes — IME-internal keyboard
     * re-resolves (theme rebuilds) must not flip the preview mid-editing to
     * another layout.
     */
    private var previewKeyboardId: String? = null

    /** In-flight preview/model refresh; a newer refresh cancels the older one. */
    private var refreshJob: Job? = null

    /** In-flight image pick / apply work; prevents double taps mid-operation. */
    private var pickJob: Job? = null
    private var applying = false

    /**
     * Theme used for the real preview (方案 A). The running IME theme wins when
     * this process has it; otherwise the workspace theme is read once directly,
     * because the settings UI can start without the IME service having run.
     */
    private val resolvedTheme: Theme?
        get() =
            if (ThemeManager.isInitialized) {
                ThemeManager.activeTheme
            } else {
                cachedWorkspaceTheme
            }

    private val cachedWorkspaceTheme: Theme? by lazy {
        runCatching { PackageThemeLoader.load(workspace()) }.getOrNull()
    }

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                openImage(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = initialMode()
        // Prewarm the workspace theme parse off the main thread so cold starts
        // (IME never ran in this process) never parse the theme on the UI
        // thread via the lazy property.
        lifecycleScope.launch(Dispatchers.IO) { cachedWorkspaceTheme }
        setContentView(buildContent())
        setTitle(R.string.keyboard_background_editor)
        refreshPreview()
        applyModeColors()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (cropHost.isVisible) {
                        leaveCropMode()
                    } else {
                        finish()
                    }
                }
            },
        )
    }

    override fun onResume() {
        super.onResume()
        if (
            ::preview.isInitialized &&
            ::cropHost.isInitialized &&
            !cropHost.isVisible &&
            pendingBitmap == null
        ) {
            // Reflect external changes made while this editor was paused (e.g.
            // the user switched schema/keyboard, or the running theme was
            // rebuilt) into the preview: keyboard layout, palette and dims.
            // resnapshotKeyboard forces the keyboard id to re-resolve inside
            // the off-main model build, so a switch made while paused (14键 →
            // 9键) is picked up on return.
            refreshPreview(resnapshotKeyboard = true)
        }
    }

    private fun schemeId(): String = currentSchemeId() ?: ThemeManager.prefs.normalModeColor.getValue()

    /**
     * The scheme being edited, resolved the same way ColorManager picks the
     * active scheme: the preferred id when it exists, otherwise "default",
     * then the first scheme. Null only when no theme is usable at all, in
     * which case writes are refused with a proper message.
     */
    private fun currentSchemeId(): String? {
        val ids = schemeIds()
        if (ids.isEmpty()) return null
        val preferred = ThemeManager.prefs.normalModeColor.getValue()
        return when {
            preferred in ids -> preferred
            DEFAULT_SCHEME_ID in ids -> DEFAULT_SCHEME_ID
            else -> ids.first()
        }
    }

    private fun schemeIds(): List<String> = resolvedTheme?.colorSchemes?.map { it.id } ?: emptyList()

    private fun workspace(): File = DataManager.userDataDir

    private fun backgroundFolderName(): String = if (ThemeManager.isInitialized) {
        runCatching { ThemeManager.activeTheme.generalStyle.backgroundFolder }
            .getOrDefault(ThemeCustomization.BACKGROUND_DIR_NAME)
    } else {
        ThemeCustomization.BACKGROUND_DIR_NAME
    }

    private fun backgroundDir(): File = File(workspace(), ThemeCustomization.BACKGROUND_DIR_NAME)

    /**
     * Width/height ratio of the preview canvas. Reads the style dims snapshot
     * cached by the off-main model build (never parses the theme here, so cold
     * starts stay off the main thread); defaults match the placeholder until
     * the first IO refresh lands.
     */
    private fun keyboardAspect(): Float {
        val widthPx = minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val widthDp = widthPx / resources.displayMetrics.density
        return widthDp / (cachedCandidateHeightDp + cachedKeyboardHeightDp).toFloat()
    }

    private fun initialMode(): String {
        val followSystemDayNight = ThemeManager.prefs.followSystemDayNight.getValue()
        val night =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        return if (followSystemDayNight && night) {
            ThemeCustomization.MODE_DARK
        } else {
            ThemeCustomization.MODE_LIGHT
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────

    private fun buildContent(): View {
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }.also { rootColumn = it }

        // Action row: [选取] [应用] [缺省]. Buttons always have the same fixed
        // width so a hidden button never stretches the remaining ones.
        btnChange = actionButton(R.string.keyboard_background_change).apply {
            setOnClickListener { pickImageLauncher.launch("image/*") }
        }
        btnApply = actionButton(R.string.keyboard_background_apply).apply {
            isVisible = false
            setOnClickListener { applyPendingBackground() }
        }
        btnDefault = actionButton(R.string.keyboard_background_default).apply {
            isVisible = false
            setOnClickListener { applyDefaultBackground() }
        }
        actionRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(
                    btnChange,
                    LinearLayout.LayoutParams(dp(ACTION_BUTTON_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(6)
                    },
                )
                addView(
                    btnApply,
                    LinearLayout.LayoutParams(dp(ACTION_BUTTON_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = dp(3)
                        marginEnd = dp(3)
                    },
                )
                addView(
                    btnDefault,
                    LinearLayout.LayoutParams(dp(ACTION_BUTTON_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = dp(6)
                    },
                )
            }

        // Day/night radios sit below the preview (UX decision) so the preview
        // can use the full card width; their position never changes when the
        // action buttons appear/disappear either.
        radioLight =
            modeButton(ThemeCustomization.MODE_LIGHT, R.string.keyboard_background_mode_light, modeLightId).apply {
                setOnClickListener { selectMode(ThemeCustomization.MODE_LIGHT) }
            }
        radioDark =
            modeButton(ThemeCustomization.MODE_DARK, R.string.keyboard_background_mode_dark, modeDarkId).apply {
                setOnClickListener { selectMode(ThemeCustomization.MODE_DARK) }
            }
        updateModeCheck()

        previewHost =
            FrameLayout(this)
        preview = KeyboardBackgroundPreview(this).apply { setAspectRatio(keyboardAspect()) }
        previewHost.addView(
            preview,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val modeRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(radioLight, wrap().apply { marginEnd = dp(12) })
                addView(radioDark, wrap())
            }
        // Preview, radios and actions sit on one day/night-aware surface card
        // centered on the window, instead of floating on a raw white block.
        val mainColumn =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(previewHost, matchWrap())
                addView(modeRow, matchWrap().apply { topMargin = dp(8) })
                addView(actionRow, matchWrap().apply { topMargin = dp(10) })
            }
        contentCard =
            MaterialCardView(this).apply {
                radius = dp(16).toFloat()
                elevation = dp(3).toFloat()
                setContentPadding(dp(16), dp(16), dp(16), dp(16))
                addView(
                    mainColumn,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
        mainArea =
            FrameLayout(this)
        mainArea.addView(
            contentCard,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
                marginStart = dp(12)
                marginEnd = dp(12)
            },
        )

        cropHost =
            FrameLayout(this).apply {
                isVisible = false
            }
        cropView = KeyboardBackgroundCropView(this)
        cropHost.addView(
            cropView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        btnCropDone = actionButton(R.string.keyboard_background_crop).apply {
            setOnClickListener { confirmCrop() }
        }
        btnReset = actionButton(R.string.keyboard_background_reset).apply {
            setOnClickListener { cropView.resetSelection() }
        }
        cropRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                isVisible = false
                addView(
                    btnCropDone,
                    LinearLayout.LayoutParams(dp(ACTION_BUTTON_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(3)
                    },
                )
                addView(
                    btnReset,
                    LinearLayout.LayoutParams(dp(ACTION_BUTTON_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = dp(3)
                    },
                )
            }

        root.addView(mainArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(cropHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(cropRow, matchWrap().apply { topMargin = dp(8) })
        return root
    }

    /** Explicit switch of the day/night profile; apply and default target this mode. */
    private fun selectMode(newMode: String) {
        if (mode != newMode) {
            mode = newMode
            // Keep an uncropped/pending image when present: switching the
            // preview colors must not discard the crop the user is about to
            // apply (P2). Only the palette/model changes, the background stays.
            refreshPreview(preservePending = pendingBitmap != null)
        }
        // Re-assert both radio states (no RadioGroup): tapping the active one
        // must never leave both visually unchecked.
        updateModeCheck()
        applyModeColors()
    }

    /**
     * Day/night-aware window and card surfaces, plus radio text color that
     * keeps contrast on whichever profile is selected.
     */
    private fun applyModeColors() {
        val night = mode == ThemeCustomization.MODE_DARK
        rootColumn.setBackgroundColor(if (night) COLOR_WINDOW_NIGHT else COLOR_WINDOW_DAY)
        contentCard.setCardBackgroundColor(if (night) COLOR_CARD_NIGHT else COLOR_CARD_DAY)
        val textColor = if (night) COLOR_TEXT_NIGHT else COLOR_TEXT_DAY
        radioLight.setTextColor(textColor)
        radioDark.setTextColor(textColor)
    }

    private fun updateModeCheck() {
        radioLight.isChecked = mode == ThemeCustomization.MODE_LIGHT
        radioDark.isChecked = mode == ThemeCustomization.MODE_DARK
    }

    private fun modeButton(
        mode: String,
        labelRes: Int,
        id: Int,
    ) = MaterialRadioButton(this).apply {
        text = getString(labelRes)
        gravity = Gravity.CENTER
        this.id = id
        tag = mode
    }

    private fun actionButton(labelRes: Int) = MaterialButton(this).apply {
        text = getString(labelRes)
        isAllCaps = false
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun enterCropMode(bitmap: Bitmap) {
        cropView.setImageBitmap(bitmap, keyboardAspect())
        mainArea.isVisible = false
        actionRow.isVisible = false
        cropHost.isVisible = true
        cropRow.isVisible = true
    }

    private fun leaveCropMode() {
        cropHost.isVisible = false
        cropRow.isVisible = false
        mainArea.isVisible = true
        actionRow.isVisible = true
        refreshPreview()
    }

    private fun confirmCrop() {
        val cropped = cropView.croppedBitmap() ?: run {
            toast(R.string.keyboard_background_decode_failed)
            return
        }
        // Return to the main screen with the crop as the pending background.
        // No refresh here: it would asynchronously overwrite the pending
        // preview with the stored file before "Apply" runs.
        cropHost.isVisible = false
        cropRow.isVisible = false
        mainArea.isVisible = true
        actionRow.isVisible = true
        pendingBitmap = cropped
        preview.setPreviewBackground(BitmapDrawable(resources, cropped))
        updateActionButtons()
    }

    /**
     * Action rows are: [选取] always, [应用] only while a crop is pending,
     * [缺省] only when the current scheme×mode actually has a customization.
     * Cancel is handled by the system back gesture.
     */

    /**
     * Action rows are: [选取] always, [应用] only while a crop is pending,
     * [缺省] only when the current scheme×mode actually has a customization.
     * The customization value comes from the off-main cache, never from a
     * synchronous file read on the main thread (P1).
     */
    private fun updateActionButtons() {
        btnApply.isVisible = pendingBitmap != null
        btnDefault.isVisible = customFileNameCache != null
    }

    // ── data helpers ──────────────────────────────────────────────────────

    private fun customBackgroundValue(): String? {
        val root = runCatching { ThemeCustomization.load(workspace()) }.getOrNull() ?: return null
        val schemes = (root.get(Node.Scalar(ThemeCustomization.COLOR_SCHEMES_KEY)) as? Node.Mapping) ?: return null
        val scheme = (schemes.get(Node.Scalar(schemeId())) as? Node.Mapping) ?: return null
        val palette = (scheme.get(Node.Scalar(mode)) as? Node.Mapping) ?: return null
        return (palette.get(Node.Scalar(ThemeColor.KEYBOARD_BACKGROUND.key)) as? Node.Scalar)?.string
    }

    private fun baseBackgroundValue(): String? {
        val scheme = baseColorScheme() ?: return null
        val palette = if (mode == ThemeCustomization.MODE_DARK) scheme.darkColors else scheme.colors
        return palette[ThemeColor.KEYBOARD_BACKGROUND.key]
    }

    private fun baseColorScheme(): com.osfans.trime.data.theme.model.ColorScheme? = resolvedTheme?.colorSchemes?.firstOrNull { it.id == schemeId() }

    /**
     * Rebuild the preview off the main thread: customization/theme reads,
     * workspace manifest registry access and image decoding all happen on IO;
     * only the resulting model/drawable land on the UI. A newer refresh
     * cancels the previous in-flight one, and cancellation mid-IO skips the
     * UI update.
     *
     * With [preservePending] (mode switch while a crop is waiting to be
     * applied) only the palette/model is refreshed; the pending crop stays on
     * the preview and the [应用] flow keeps it.
     *
     * With [resnapshotKeyboard] (returning to the editor after typing
     * elsewhere) the keyboard snapshot is forced to re-resolve on IO, so a
     * schema/keyboard switch made while paused is picked up.
     */
    private fun refreshPreview(
        preservePending: Boolean = false,
        resnapshotKeyboard: Boolean = false,
    ) {
        if (!preservePending) {
            pendingBitmap = null
        }
        if (resnapshotKeyboard) {
            previewKeyboardId = null
        }
        refreshJob?.cancel()
        refreshJob =
            lifecycleScope.launch {
                val aspect = keyboardAspect()
                // Only the *custom* file name drives the [缺省] button; the
                // display may legitimately fall back to a packaged background,
                // which must not make the button appear.
                val customName = withContext(Dispatchers.IO) { customBackgroundValue() }
                customFileNameCache = customName
                val displayName =
                    customName
                        ?: withContext(Dispatchers.IO) { baseBackgroundValue() }
                val background =
                    if (preservePending || displayName == null) {
                        null
                    } else {
                        withContext(Dispatchers.IO) { resolveImage(displayName) }
                    }
                val renderModel = withContext(Dispatchers.IO) { buildRenderModel() }
                preview.setAspectRatio(aspect)
                preview.setRenderModel(renderModel)
                if (!preservePending) {
                    preview.setPreviewBackground(background)
                }
                updateActionButtons()
            }
    }

    /**
     * Real-data preview model for the currently selected scheme×mode. The
     * keyboard layout shown comes from the [previewKeyboardId] snapshot
     * (refreshed on open/resume only); see [resolvePreviewKeyboardId] for the
     * data chain. The exact *in-session* layout (user-switched keyboards,
     * per-schema `.default` targets, ascii variants) would need
     * `KeyboardSwitcher`/Rime state the settings UI does not own.
     */
    private fun buildRenderModel(): RenderModel? {
        val theme = resolvedTheme ?: return null
        val scheme = theme.colorSchemes.firstOrNull { it.id == schemeId() } ?: return null
        val style = theme.generalStyle
        // Keep quick-read snapshots for the main-thread aspect/button logic.
        cachedCandidateHeightDp = style.candidateViewHeight.takeIf { it > 0 } ?: DEFAULT_CANDIDATE_HEIGHT_DP
        cachedKeyboardHeightDp = style.keyboardHeight.takeIf { it > 0 } ?: DEFAULT_KEYBOARD_HEIGHT_DP
        val palette =
            if (mode == ThemeCustomization.MODE_DARK) scheme.darkColors else scheme.colors
        return RenderModel(
            palette = palette,
            themeFallbackColors = theme.fallbackColors,
            presetKeys = theme.presetKeys,
            keyboard = previewKeyboardConfig(theme),
            style = style,
            candidateHeightDp = cachedCandidateHeightDp,
            keyboardHeightDp = cachedKeyboardHeightDp,
        )
    }

    /**
     * Preview keyboard id, following the same data chain the IME uses when it
     * can be answered without a live Rime session: the last keyboard the IME
     * resolved (persisted by `KeyboardSwitcher`, follows schema switches like
     * 14键 → 9键), then the active package's manifest `default_keyboard`
     * binding, then a preset named like the edited scheme, then `default`,
     * then the first preset.
     */
    private fun resolvePreviewKeyboardId(theme: Theme): String? {
        val ids = theme.presetKeyboards.keys
        if (ids.isEmpty()) return null
        val lastKeyboard =
            runCatching { AppPrefs.defaultInstance().session.lastKeyboard.getValue() }.getOrNull()
        val manifestBinding =
            runCatching { ImePackageManager.registry().singleDefaultKeyboard() }.getOrNull()
        return listOfNotNull(
            lastKeyboard?.takeIf { it in ids },
            manifestBinding?.takeIf { it in ids },
            schemeId().takeIf { it in ids },
            "default".takeIf { it in ids },
            ids.firstOrNull(),
        ).first()
    }

    private fun previewKeyboardConfig(theme: Theme): TextKeyboard? {
        val keyboards = theme.presetKeyboards
        if (keyboards.isEmpty()) return null
        // Use the snapshot (stable across this editing session); fall back to a
        // fresh resolution and memorize it when no snapshot exists yet.
        val id =
            previewKeyboardId?.takeIf { it in keyboards }
                ?: resolvePreviewKeyboardId(theme)?.also { previewKeyboardId = it }
                ?: return null
        return keyboards[id]
    }

    private fun resolveImage(fileName: String): Drawable? {
        val file =
            listOf(
                File(workspace(), "backgrounds/${backgroundFolderName()}/$fileName"),
                File(workspace(), "backgrounds/$fileName"),
            ).firstOrNull { it.isFile } ?: return null
        return runCatching {
            BitmapDrawable(resources, BitmapFactory.decodeFile(file.absolutePath))
        }.getOrNull()
    }

    /**
     * Persist the pending crop off the main thread (PNG encode + customization
     * writes). Every apply alternates the slot file generation so the stored
     * name actually changes and the running IME refreshes through its normal
     * theme-change path; the superseded generation file is removed.
     */
    private fun applyPendingBackground() {
        val bitmap = pendingBitmap ?: return
        if (applying) return
        applying = true
        btnApply.isEnabled = false
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    writeBackgroundSlot(bitmap)
                }
                result.onSuccess { writtenName ->
                    // The just-written file is exactly the pending crop, so the
                    // preview can reuse the in-memory bitmap instantly instead
                    // of re-decoding the PNG from disk (that round-trip was
                    // felt as a pause after tapping Apply). The theme reload
                    // still runs so the real keyboard picks the new image.
                    pendingBitmap = null
                    customFileNameCache = writtenName
                    preview.setPreviewBackground(BitmapDrawable(resources, bitmap))
                    updateActionButtons()
                    refreshRunningTheme()
                }.onFailure { t ->
                    toast(t)
                }
            } finally {
                applying = false
                btnApply.isEnabled = true
            }
        }
    }

    /** Encode + write next-generation slot + customization; returns new slot name. */
    private fun writeBackgroundSlot(bitmap: Bitmap): Result<String> {
        val dir = backgroundDir()
        var nextName: String? = null
        var previousDeleted = false
        return runCatching {
            if (currentSchemeId() == null) {
                throw IllegalStateException(getString(R.string.keyboard_background_no_scheme))
            }
            dir.mkdirs()
            val previousName = customBackgroundValue()
            val slotName = ThemeCustomization.nextSlotFileName(schemeId(), mode, previousName)
            nextName = slotName
            val slot = File(dir, slotName)
            FileOutputStream(slot).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) {
                    "Failed to encode the cropped background"
                }
            }
            ThemeCustomization.write(
                workspace(),
                ThemeCustomization.updatedColorSchemeBackground(
                    ThemeCustomization.load(workspace()),
                    schemeId(),
                    mode,
                    slot.name,
                ),
            )
            if (previousName != null && previousName != slot.name) {
                previousDeleted = File(dir, previousName).delete()
            }
            slot.name
        }.onFailure {
            // A failure leaves the previous generation referenced in the
            // customization intact, so only drop the partial new file.
            if (!previousDeleted) {
                nextName?.let { runCatching { File(dir, it).delete() } }
            }
        }
    }

    /**
     * Remove the customization entry and every slot generation of this
     * scheme × mode, off the main thread.
     */
    private fun applyDefaultBackground() {
        if (applying) return
        applying = true
        btnDefault.isEnabled = false
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        if (currentSchemeId() == null) {
                            throw IllegalStateException(getString(R.string.keyboard_background_no_scheme))
                        }
                        ThemeCustomization.write(
                            workspace(),
                            ThemeCustomization.updatedColorSchemeBackground(
                                ThemeCustomization.load(workspace()),
                                schemeId(),
                                mode,
                                null,
                            ),
                        )
                        ThemeCustomization.slotFileNameVariants(schemeId(), mode)
                            .forEach { File(backgroundDir(), it).delete() }
                    }
                }
                result.onSuccess {
                    // The customization is gone, so the [缺省] button hides
                    // immediately (cache-based, no file re-read). Same ordering
                    // as apply: the theme reload (which drops the customization
                    // and restores the packaged background) must land before
                    // the preview re-reads the base background.
                    customFileNameCache = null
                    updateActionButtons()
                    refreshRunningTheme()
                    refreshPreview()
                }.onFailure { t ->
                    toast(t)
                }
            } finally {
                applying = false
                btnDefault.isEnabled = true
            }
        }
    }

    /**
     * Suspend best-effort refresh of the running theme so callers can order it
     * *before* the preview re-read (see the apply/default success handlers):
     * the workspace parse runs on IO, applying the result re-enters the normal
     * theme-change path — the alternated slot name is a real data change, so
     * the open keyboard rebuilds with the new image on its own.
     */
    private suspend fun refreshRunningTheme() {
        if (!ThemeManager.isInitialized) return
        val loaded =
            withContext(Dispatchers.IO) {
                runCatching { PackageThemeLoader.load(DataManager.userDataDir) }.getOrNull()
            } ?: return
        runCatching {
            ThemeManager.applySchemaLayout(loaded, replaceTheme = false)
        }.onFailure {
            Timber.w(it, "Failed to refresh the running theme after a background change")
        }
    }

    /** Decode the picked image off the main thread, then enter crop mode. */
    private fun openImage(uri: Uri) {
        pickJob?.cancel()
        pickJob =
            lifecycleScope.launch {
                val decoded =
                    withContext(Dispatchers.IO) {
                        runCatching { decodeUri(uri) ?: throw IOException("decode returned null") }
                            .getOrNull()
                    }
                if (decoded != null) {
                    enterCropMode(decoded)
                } else {
                    toast(R.string.keyboard_background_decode_failed)
                }
            }
    }

    private fun decodeUri(uri: Uri): Bitmap? {
        val cache = File(cacheDir, "kb_picker_${UUID.randomUUID()}.img")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                cache.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(cache.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sampleSize = 1
            while (
                bounds.outWidth / sampleSize > MAX_DECODE_EDGE ||
                bounds.outHeight / sampleSize > MAX_DECODE_EDGE
            ) {
                sampleSize *= 2
            }
            val decoded =
                BitmapFactory.decodeFile(
                    cache.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sampleSize },
                ) ?: return null
            val degrees = exifRotationDegrees(cache)
            if (degrees == 0) return decoded
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        } finally {
            cache.delete()
        }
    }

    private fun exifRotationDegrees(file: File): Int = try {
        when (
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: Exception) {
        0
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PNG_QUALITY = 100
        const val MAX_DECODE_EDGE = 2048
        const val DEFAULT_CANDIDATE_HEIGHT_DP = 28
        const val DEFAULT_KEYBOARD_HEIGHT_DP = 250
        const val DEFAULT_SCHEME_ID = "default"
        const val ACTION_BUTTON_WIDTH_DP = 84

        // Day/night UI surfaces (tints, not config data).
        val COLOR_WINDOW_DAY = Color.argb(255, 239, 241, 245)
        val COLOR_WINDOW_NIGHT = Color.argb(255, 18, 21, 24)
        val COLOR_CARD_DAY = Color.argb(255, 255, 255, 255)
        val COLOR_CARD_NIGHT = Color.argb(255, 32, 36, 43)
        val COLOR_TEXT_DAY = Color.argb(255, 27, 29, 34)
        val COLOR_TEXT_NIGHT = Color.argb(255, 226, 229, 234)
    }
}
