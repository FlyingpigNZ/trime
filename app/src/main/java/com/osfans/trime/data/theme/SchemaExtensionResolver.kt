/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.data.schema.PackageStore
import com.osfans.trime.data.theme.model.ToolBar
import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.mapping
import java.io.File

/**
 * Centralized resolution of per-schema directives from `<schemaId>.extended.yaml`.
 *
 * All "what does the active schema override" questions go through this object so
 * the UI never reads a stale static `theme.toolBar` while a schema override is
 * active. See [SchemaExtension] for the file shape.
 */
object SchemaExtensionResolver {

    /**
     * Compute the effective toolbar for [schemaId]:
     * - no extension file / no `tool_bar` section → [base] unchanged
     * - `__replace: true` → the extension's toolbar replaces [base]
     * - otherwise the extension's toolbar deep-merges onto [base]
     *
     * The merge happens at the **YAML node** level (same deep-merge the
     * component resolver applies to scalar/map sections), so an explicit leaf
     * in the extension always wins and absent leaves keep the base value.
     */
    fun resolveToolBar(
        base: ToolBar,
        schemaId: String,
        workspace: File? = PackageStore.activeWorkspaceDir(),
    ): ToolBar {
        val ext = loadForSchema(schemaId, workspace) ?: return base
        val node = ext.toolBarNode ?: return base
        return if (ext.toolBar?.replace == true) {
            ext.toolBar!!
        } else {
            mergeToolBarNode(base, node)
        }
    }

    /**
     * Apply a decoded per-schema override to [base] (test seam): replaces when
     * the override declares `__replace: true`, otherwise merges.
     */
    fun resolveToolBar(
        base: ToolBar,
        overlay: ToolBar?,
    ): ToolBar = when {
        overlay == null -> base
        overlay.replace -> overlay
        else -> mergeToolBar(base, overlay)
    }

    /**
     * Merge a raw `tool_bar` override node onto a decoded base toolbar. The
     * override node is decoded **on top of** the base so a missing leaf in the
     * override keeps the base value (node-level deep merge, leaf wins).
     */
    fun mergeToolBarNode(
        base: ToolBar,
        overlayNode: Node.Mapping,
    ): ToolBar = decodeMerged(base, overlayNode)

    /**
     * Test seam: merge two decoded toolbars. The scalar/composite fields that
     * hold collections or strings are kept when the overlay leaves them empty
     * (["buttons"], "buttonFont", and the per-button "size"/"longPressAction"/
     * colors/style via [mergeButton]); the plain numeric scalars ("buttonSpacing",
     * "backStyle", and the per-button insets/fontSize/padding/type/cornerRadius)
     * are taken from the overlay unconditionally. This is deliberately not the
     * node-level "leaf wins, absent keeps base" semantics — production code
     * should prefer [mergeToolBarNode]. The seam mirrors the decode defaults but
     * is not an exact presence model; it exists for unit tests only.
     */
    fun mergeToolBar(
        base: ToolBar,
        overlay: ToolBar,
    ): ToolBar = base.copy(
        primaryButton = mergeButton(base.primaryButton, overlay.primaryButton),
        buttons = overlay.buttons.ifEmpty { base.buttons },
        buttonSpacing = overlay.buttonSpacing,
        buttonFont = overlay.buttonFont.ifEmpty { base.buttonFont },
        backStyle = overlay.backStyle,
        replace = false,
    )

    private fun mergeButton(
        base: ToolBar.Button?,
        overlay: ToolBar.Button?,
    ): ToolBar.Button? = when {
        overlay == null -> base
        base == null -> overlay
        else -> ToolBar.Button(
            background = ToolBar.Button.Background(
                type = overlay.background.type,
                cornerRadius = overlay.background.cornerRadius,
                normal = overlay.background.normal.ifEmpty { base.background.normal },
                highlight = overlay.background.highlight.ifEmpty { base.background.highlight },
                verticalInset = overlay.background.verticalInset,
                horizontalInset = overlay.background.horizontalInset,
            ),
            foreground = ToolBar.Button.Foreground(
                style = overlay.foreground.style.ifEmpty { base.foreground.style },
                optionStyles = overlay.foreground.optionStyles.ifEmpty { base.foreground.optionStyles },
                normal = overlay.foreground.normal.ifEmpty { base.foreground.normal },
                highlight = overlay.foreground.highlight.ifEmpty { base.foreground.highlight },
                fontSize = overlay.foreground.fontSize,
                padding = overlay.foreground.padding,
            ),
            action = overlay.action.ifEmpty { base.action },
            longPressAction = overlay.longPressAction.ifEmpty { base.longPressAction },
            size = overlay.size.ifEmpty { base.size },
        )
    }

    private fun decodeMerged(
        base: ToolBar,
        overlayNode: Node.Mapping,
    ): ToolBar {
        // Decode the overlay with the base values as fallback defaults.
        val mergedNode = mergeMappings(baseNode(base), overlayNode)
        return ToolBar.decode(mergedNode).copy(replace = false)
    }

    private fun baseNode(base: ToolBar): Node.Mapping {
        val pairs = LinkedHashMap<Node, Node>()
        base.primaryButton?.let { button ->
            val bp = LinkedHashMap<Node, Node>()
            bp[Node.Scalar("action")] = Node.Scalar(button.action)
            if (button.longPressAction.isNotEmpty()) {
                bp[Node.Scalar("long_press_action")] = Node.Scalar(button.longPressAction)
            }
            if (button.size.isNotEmpty()) {
                bp[Node.Scalar("size")] = Node.Sequence(button.size.map { Node.Scalar(it.toString()) })
            }
            val bg = LinkedHashMap<Node, Node>()
            bg[Node.Scalar("type")] = Node.Scalar(button.background.type.name)
            bg[Node.Scalar("corner_radius")] = Node.Scalar(button.background.cornerRadius.toString())
            if (button.background.normal.isNotEmpty()) {
                bg[Node.Scalar("normal")] = Node.Scalar(button.background.normal)
            }
            if (button.background.highlight.isNotEmpty()) {
                bg[Node.Scalar("highlight")] = Node.Scalar(button.background.highlight)
            }
            bg[Node.Scalar("vertical_inset")] = Node.Scalar(button.background.verticalInset.toString())
            bg[Node.Scalar("horizontal_inset")] = Node.Scalar(button.background.horizontalInset.toString())
            bp[Node.Scalar("background")] = Node.Mapping(bg)
            val fg = LinkedHashMap<Node, Node>()
            if (button.foreground.style.isNotEmpty()) {
                fg[Node.Scalar("style")] = Node.Scalar(button.foreground.style)
            }
            if (button.foreground.optionStyles.isNotEmpty()) {
                fg[Node.Scalar("option_styles")] = Node.Sequence(
                    button.foreground.optionStyles.map { Node.Scalar(it) },
                )
            }
            if (button.foreground.normal.isNotEmpty()) {
                fg[Node.Scalar("normal")] = Node.Scalar(button.foreground.normal)
            }
            if (button.foreground.highlight.isNotEmpty()) {
                fg[Node.Scalar("highlight")] = Node.Scalar(button.foreground.highlight)
            }
            fg[Node.Scalar("font_size")] = Node.Scalar(button.foreground.fontSize.toString())
            fg[Node.Scalar("padding")] = Node.Scalar(button.foreground.padding.toString())
            bp[Node.Scalar("foreground")] = Node.Mapping(fg)
            pairs[Node.Scalar("primary_button")] = Node.Mapping(bp)
        }
        if (base.buttons.isNotEmpty()) {
            pairs[Node.Scalar("buttons")] = Node.Sequence(
                base.buttons.map { button ->
                    val bp = LinkedHashMap<Node, Node>()
                    bp[Node.Scalar("action")] = Node.Scalar(button.action)
                    if (button.longPressAction.isNotEmpty()) {
                        bp[Node.Scalar("long_press_action")] = Node.Scalar(button.longPressAction)
                    }
                    if (button.size.isNotEmpty()) {
                        bp[Node.Scalar("size")] = Node.Sequence(button.size.map { Node.Scalar(it.toString()) })
                    }
                    val bg = LinkedHashMap<Node, Node>()
                    bg[Node.Scalar("type")] = Node.Scalar(button.background.type.name)
                    bg[Node.Scalar("corner_radius")] = Node.Scalar(button.background.cornerRadius.toString())
                    if (button.background.normal.isNotEmpty()) {
                        bg[Node.Scalar("normal")] = Node.Scalar(button.background.normal)
                    }
                    if (button.background.highlight.isNotEmpty()) {
                        bg[Node.Scalar("highlight")] = Node.Scalar(button.background.highlight)
                    }
                    bg[Node.Scalar("vertical_inset")] = Node.Scalar(button.background.verticalInset.toString())
                    bg[Node.Scalar("horizontal_inset")] = Node.Scalar(button.background.horizontalInset.toString())
                    bp[Node.Scalar("background")] = Node.Mapping(bg)
                    val fg = LinkedHashMap<Node, Node>()
                    if (button.foreground.style.isNotEmpty()) {
                        fg[Node.Scalar("style")] = Node.Scalar(button.foreground.style)
                    }
                    if (button.foreground.optionStyles.isNotEmpty()) {
                        fg[Node.Scalar("option_styles")] = Node.Sequence(
                            button.foreground.optionStyles.map { Node.Scalar(it) },
                        )
                    }
                    if (button.foreground.normal.isNotEmpty()) {
                        fg[Node.Scalar("normal")] = Node.Scalar(button.foreground.normal)
                    }
                    if (button.foreground.highlight.isNotEmpty()) {
                        fg[Node.Scalar("highlight")] = Node.Scalar(button.foreground.highlight)
                    }
                    fg[Node.Scalar("font_size")] = Node.Scalar(button.foreground.fontSize.toString())
                    fg[Node.Scalar("padding")] = Node.Scalar(button.foreground.padding.toString())
                    bp[Node.Scalar("foreground")] = Node.Mapping(fg)
                    Node.Mapping(bp)
                },
            )
        }
        pairs[Node.Scalar("button_spacing")] = Node.Scalar(base.buttonSpacing.toString())
        if (base.buttonFont.isNotEmpty()) {
            pairs[Node.Scalar("button_font")] = Node.Sequence(base.buttonFont.map { Node.Scalar(it) })
        }
        pairs[Node.Scalar("back_style")] = Node.Scalar(base.backStyle)
        return Node.Mapping(pairs)
    }

    /** Same deep-merge as the component resolver: nested maps merge, leaf wins. */
    private fun mergeMappings(
        base: Node.Mapping,
        overlay: Node.Mapping,
    ): Node.Mapping {
        val result = LinkedHashMap<Node, Node>(base.pairs)
        overlay.pairs.forEach { (key, value) ->
            val existing = result[key]
            if (existing is Node.Mapping && value is Node.Mapping) {
                result[key] = mergeMappings(existing, value)
            } else {
                result[key] = value
            }
        }
        return Node.Mapping(result)
    }

    /**
     * Load the extension file for [schemaId] from [workspace]. Returns null
     * when the file is absent; a malformed file throws (schema-first).
     */
    fun loadForSchema(
        schemaId: String,
        workspace: File? = PackageStore.activeWorkspaceDir(),
    ): SchemaExtension? {
        if (schemaId.isEmpty() || schemaId.startsWith('.')) return null
        val file = extensionFile(schemaId, workspace) ?: return null
        return SchemaExtension.load(file)
    }

    /** `<schemaId>.extended.yaml` inside the workspace root. */
    fun extensionFile(
        schemaId: String,
        workspace: File? = PackageStore.activeWorkspaceDir(),
    ): File? {
        val dir = workspace ?: return null
        val file = File(dir, "$schemaId${SchemaExtension.FILE_SUFFIX}")
        return file.takeIf { it.isFile }
    }
}
