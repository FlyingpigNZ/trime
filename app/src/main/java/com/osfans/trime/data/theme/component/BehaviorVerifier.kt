// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme.component

import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.mapping
import com.osfans.trime.util.yaml.sequence
import com.osfans.trime.util.yaml.string

/**
 * Pure verification of keypress behavior in a resolved component definition.
 *
 * Walks every keyboard and every action field. If the action value names a
 * preset key, the preset key definition is checked; otherwise it is treated as
 * literal output.
 */
object BehaviorVerifier {
    private val ACTION_FIELDS =
        setOf(
            "click",
            "long_click",
            "swipe_up",
            "swipe_down",
            "swipe_left",
            "swipe_right",
            "composing",
        )

    private val SPECIAL_SELECTS =
        setOf(".next", ".last", ".default", ".ascii", ".last_lock")

    fun verify(sections: Map<String, Node.Mapping>): List<String> {
        val errors = mutableListOf<String>()
        val presetKeys = sections["preset_keys"] ?: Node.Mapping()
        val keyboards = sections["preset_keyboards"] ?: Node.Mapping()
        val switchPolicy = sections["keyboard_switch_policy"] ?: Node.Mapping()

        keyboards.pairs.forEach { (nameNode, valueNode) ->
            val name = nameNode.string ?: return@forEach
            val keyboard = valueNode.mapping ?: run {
                errors += "keyboard '$name' is not a mapping"
                return@forEach
            }
            verifyKeyboardReferences(name, keyboard, keyboards, errors)
            val keys = keyboard["keys"]?.sequence?.nodes ?: emptyList()
            keys.forEachIndexed { index, keyNode ->
                val key = keyNode.mapping ?: run {
                    errors += "keyboard '$name' key[$index] is not a mapping"
                    return@forEachIndexed
                }
                verifyKeyActions(name, index, key, presetKeys, keyboards, errors)
            }
        }

        listOf("default_keyboard", "ascii_keyboard").forEach { field ->
            val target = switchPolicy[field]?.string
            if (!target.isNullOrEmpty() && keyboards[target] == null) {
                errors += "keyboard_switch_policy.$field references unknown keyboard '$target'"
            }
        }
        switchPolicy["aux"]?.mapping?.pairs?.forEach { (role, targetNode) ->
            val target = targetNode.string
            if (!target.isNullOrEmpty() && keyboards[target] == null) {
                errors += "keyboard_switch_policy.aux.${role.string} references unknown keyboard '$target'"
            }
        }

        val style = sections["style"] ?: Node.Mapping()
        style["keyboards"]?.sequence?.nodes?.mapNotNull { it.string }?.forEach { name ->
            if (name != ".default" && keyboards[name] == null) {
                errors += "style.keyboards references unknown keyboard '$name'"
            }
        }

        return errors
    }

    private fun verifyKeyboardReferences(
        name: String,
        keyboard: Node.Mapping,
        keyboards: Node.Mapping,
        errors: MutableList<String>,
    ) {
        listOf("ascii_keyboard", "landscape_keyboard").forEach { field ->
            val target = keyboard[field]?.string
            if (!target.isNullOrEmpty() && keyboards[target] == null) {
                errors += "keyboard '$name'.$field references unknown keyboard '$target'"
            }
        }
    }

    private fun verifyKeyActions(
        keyboardName: String,
        index: Int,
        key: Node.Mapping,
        presetKeys: Node.Mapping,
        keyboards: Node.Mapping,
        errors: MutableList<String>,
    ) {
        ACTION_FIELDS.forEach { field ->
            val value = key[field] ?: return@forEach
            val text = value.string ?: return@forEach
            if (presetKeys[text]?.mapping != null) {
                verifyPresetKey(
                    presetKeys[text]!!.mapping!!,
                    where = "keyboard '$keyboardName' key[$index] $field '$text'",
                    keyboards = keyboards,
                    errors = errors,
                )
            }
        }
    }

    private fun verifyPresetKey(
        definition: Node.Mapping,
        where: String,
        keyboards: Node.Mapping,
        errors: MutableList<String>,
    ) {
        definition["select"]?.string?.let { select ->
            if (select.isEmpty()) {
                errors += "$where: 'select' must be a non-empty string"
            } else if (select !in SPECIAL_SELECTS && keyboards[select] == null) {
                errors += "$where: 'select' references unknown keyboard '$select'"
            }
        }

        definition["toggle"]?.string?.let { toggle ->
            if (toggle.isEmpty()) {
                errors += "$where: 'toggle' must be a non-empty string"
            }
            val states = definition["states"]?.sequence?.nodes
            if (states.isNullOrEmpty()) {
                errors += "$where: toggle '$toggle' must have a non-empty 'states' list"
            }
        }

        definition["command"]?.string?.let { command ->
            if (command.isEmpty()) {
                errors += "$where: 'command' must be a non-empty string"
            }
        }
    }
}
