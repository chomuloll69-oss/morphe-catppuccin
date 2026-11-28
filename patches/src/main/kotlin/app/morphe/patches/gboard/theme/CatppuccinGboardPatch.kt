package app.morphe.patches.gboard.theme

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.resourcePatch
import java.io.ByteArrayOutputStream
import org.w3c.dom.Element

private fun varint(value: Long): ByteArray {
    var remaining = value
    val out = ByteArrayOutputStream()
    do {
        val byte = (remaining and 127L).toInt()
        remaining = remaining ushr 7
        out.write(byte or (if (remaining != 0L) 128 else 0))
    } while (remaining != 0L)
    return out.toByteArray()
}

private class Field(val number: Int, val wire: Int, val value: ByteArray, val raw: ByteArray)

private fun fields(bytes: ByteArray): List<Field> {
    var offset = 0
    fun read(): Long {
        var result = 0L
        for (shift in 0..63 step 7) {
            require(offset < bytes.size) { "Truncated Gboard theme" }
            val byte = bytes[offset++].toInt() and 255
            result = result or ((byte and 127).toLong() shl shift)
            if ((byte and 128) == 0) return result
        }
        error("Invalid Gboard theme varint")
    }
    val result = mutableListOf<Field>()
    while (offset < bytes.size) {
        val start = offset
        val tag = read().toInt()
        val wire = tag and 7
        var valueStart = offset
        when (wire) {
            0 -> read()
            1 -> offset += 8
            2 -> {
                val length = read()
                require(length in 0..Int.MAX_VALUE.toLong())
                valueStart = offset
                offset += length.toInt()
            }
            5 -> offset += 4
            else -> error("Unsupported Gboard theme wire type $wire")
        }
        require(offset <= bytes.size && offset >= valueStart)
        result += Field(tag ushr 3, wire, bytes.copyOfRange(valueStart, offset), bytes.copyOfRange(start, offset))
    }
    return result
}

private fun message(number: Int, value: ByteArray): ByteArray =
    varint(((number shl 3) or 2).toLong()) + varint(value.size.toLong()) + value

private fun text(number: Int, value: String) = message(number, value.toByteArray(Charsets.UTF_8))

// Catppuccin Mocha, mirrors the catppuccin_mocha_* entries in palette.xml.
// If palette.xml is bundled as a patch resource it is read from there instead.
private val mochaFallback = mapOf(
    "base" to "#1e1e2e",
    "mantle" to "#181825",
    "crust" to "#11111b",
    "surface0" to "#313244",
    "overlay0" to "#6c7086",
    "text" to "#cdd6f4",
    "subtext0" to "#a6adc8",
    "mauve" to "#cba6f7",
    "blue" to "#89b4fa",
    "green" to "#a6e3a1",
    "red" to "#f38ba8",
)

private fun parseColor(hex: String): Long {
    val digits = hex.removePrefix("#")
    val value = digits.toLong(16)
    return if (digits.length <= 6) 0xff000000L or value else value
}

private fun mochaPalette(): Map<String, Long> {
    val merged = mochaFallback.toMutableMap()
    val xml = runCatching {
        object {}.javaClass.getResourceAsStream("/palette.xml")?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()
    if (xml != null) {
        Regex("""<color\s+name="catppuccin_mocha_(\w+)"\s*>\s*(#[0-9a-fA-F]{6,8})\s*</color>""")
            .findAll(xml).forEach { merged[it.groupValues[1]] = it.groupValues[2] }
    }
    return merged.mapValues { parseColor(it.value) }
}

// Preserve the input APK's selector rules and unknown protobuf fields.
// Only this new theme's named color bindings are replaced or appended.
private fun palette(source: ByteArray, colors: Map<String, Long>): ByteArray {
    val out = ByteArrayOutputStream()
    val seen = mutableSetOf<String>()
    fun binding(name: String, color: Long) = message(2,
        text(1, name) + message(2, varint(8) + varint(color)))
    for (field in fields(source)) {
        val name = if (field.number == 2 && field.wire == 2)
            fields(field.value).firstOrNull { it.number == 1 && it.wire == 2 }?.value?.toString(Charsets.UTF_8)
        else null
        val replacement = name?.let { colors[it] }
        if (name != null && replacement != null) {
            out.write(binding(name, replacement)); seen += name
        } else out.write(field.raw)
    }
    for ((name, color) in colors) if (name !in seen) out.write(binding(name, color))
    return out.toByteArray()
}

private const val THEME_ID = "catppuccin_mocha_feedback_clear_symbols"

private fun metadata(source: ByteArray): ByteArray {
    val replacements = mapOf(
        "style_sheet_google_blue_dark.binarypb" to "style_sheet_$THEME_ID.binarypb",
        "style_sheet_google_blue_dark_border.binarypb" to "style_sheet_${THEME_ID}_border.binarypb",
    )
    val out = ByteArrayOutputStream()
    for (field in fields(source)) {
        if (field.number == 2 && field.wire == 2) {
            val original = field.value.toString(Charsets.UTF_8)
            out.write(text(2, replacements[original] ?: original))
        } else if (field.number == 3 && field.wire == 2) {
            out.write(message(3, metadata(field.value)))
        } else out.write(field.raw)
    }
    return out.toByteArray()
}

@Suppress("unused")
val catppuccinGboardPatch = resourcePatch(
    name = "Gboard Catppuccin theme",
    description = "Adds a Catppuccin Mocha keyboard theme with mauve accents, inspired by HeliBoard.",
    default = false,
) {
    compatibleWith(Compatibility(name = "Gboard", packageName = "com.google.android.inputmethod.latin",
        targets = listOf(
            AppTarget(version = "18.4.1.985164140-release-arm64-v8a"),
            AppTarget(version = "18.5.4.993608717-release-arm64-v8a"),
        )))
    execute {
        val mocha = mochaPalette()
        val base = mocha.getValue("base")
        val mantle = mocha.getValue("mantle")
        val surface = mocha.getValue("surface0")
        val overlay = mocha.getValue("overlay0")
        val text = mocha.getValue("text")
        val mauve = mocha.getValue("mauve")
        val blue = mocha.getValue("blue")

        val colors = linkedMapOf<String, Long>()
        fun roles(color: Long, vararg names: String) { for (name in names) colors[name] = color }
        roles(base, "default_keyboard_background_primary_color", "color_expression_footer_background")
        roles(mantle, "default_keyboard_background_secondary_color", "default_keyboard_background_header_color")
        roles(surface, "color_keyboard_editing_overlay", "default_popup_background_color")
        roles(text, "default_label_color", "color_state_label_candidate", "color_state_label_candidate_selected",
            "color_keyboard_editing_button", "default_label_color_variant", "default_borderless_key_dark_color_contrast")
        roles(mauve, "default_generic_accent_color", "default_generic_accent_color_strong",
            "default_borderless_space_bar_color",
            "default_action_key_background_color", "color_bottom_indicator_active", "color_label_secondary",
            "color_label_dynamic", "color_expression_content_accented", "default_popup_item_color_pressed",
            "color_state_popup_item_pressed")
        roles(blue, "default_generic_accent_color_pressed",
            "default_borderless_space_bar_color_pressed", "default_borderless_space_bar_color_hovered",
            "default_action_key_background_color_pressed", "default_action_key_background_color_hovered")
        roles(base, "default_generic_accent_color_contrast", "default_action_key_label_color")
        roles(surface, "default_bordered_key_color", "default_bordered_key_dark_color",
            "color_generic_extension_background", "color_icon_more_candidates_background",
            "default_pill_shaped_key_color_hovered", "default_transparent_key_color_pressed",
            "default_transparent_key_color_hovered", "color_softkey_highlight_fill",
            "color_keyboard_separator", "color_keyboard_top_separator",
            "color_candidate_separator", "color_candidate_panel_separator")
        roles(0x00000000L, "default_pill_shaped_key_color")
        roles(overlay, "default_bordered_key_color_pressed", "default_bordered_key_dark_color_pressed",
            "color_generic_extension_background_activated", "color_expression_corpus_selector_background_active",
            "color_jarvis_background_active", "default_pill_shaped_key_color_pressed")

        val theme = "assets/theme/"
        for (suffix in listOf("", "_border")) {
            val source = get(theme + "style_sheet_google_blue_dark$suffix.binarypb")
            require(source.isFile) { "This Gboard APK has a different bundled theme format" }
            get(theme + "style_sheet_${THEME_ID}$suffix.binarypb").writeBytes(palette(source.readBytes(), colors))
        }
        get(theme + "theme_package_metadata_$THEME_ID.binarypb").writeBytes(
            metadata(get(theme + "theme_package_metadata_google_blue_dark.binarypb").readBytes()))
        val spec = "assets:theme_package_metadata_$THEME_ID.binarypb"
        document("res/values/strings.xml").use { xml ->
            for ((name, value) in mapOf("catppuccin_gboard_spec" to spec, "catppuccin_gboard_label" to "Catppuccin Mocha")) {
                val nodes = xml.documentElement.childNodes
                (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
                    .filter { it.getAttribute("name") == name }.forEach { xml.documentElement.removeChild(it) }
                xml.documentElement.appendChild(xml.createElement("string").apply {
                    setAttribute("name", name); textContent = value
                })
            }
        }
        // Resource IDs are obfuscated and shift between Gboard builds, so find the stock
        // theme-spec string by value instead of hardcoding its ID.
        val strings = mutableMapOf<String, String>()
        document("res/values/strings.xml").use { xml ->
            val nodes = xml.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                if (e.tagName == "string") strings[e.getAttribute("name")] = e.textContent
            }
        }
        val stockSpec = "assets:theme_package_metadata_google_blue_dark.binarypb"
        val anchors = strings.filterValues { it == stockSpec }.keys.map { "@string/$it" }.toMutableSet()
        anchors += "@string/string_0x7f1408d8" // Gboard 18.4.1
        document("res/values/arrays.xml").use { xml ->
            val arrays = xml.documentElement.childNodes
            var catalog = false
            var labels = false
            for (i in 0 until arrays.length) {
                val array = arrays.item(i) as? Element ?: continue
                val nodes = array.childNodes
                val values = (0 until nodes.length).mapNotNull { (nodes.item(it) as? Element)?.textContent }
                if (values.none { it in anchors }) continue
                val withLabels = if ("@string/string_0x7f1408d8" in values) "@string/string_0x7f140595" in values
                    else values.any { strings[it.removePrefix("@string/")]?.startsWith("assets:") != true }
                if (withLabels) labels = true else catalog = true
                if ("@string/catppuccin_gboard_spec" !in values) {
                    array.appendChild(xml.createElement("item").apply { textContent = "@string/catppuccin_gboard_spec" })
                    if (withLabels) array.appendChild(xml.createElement("item").apply { textContent = "@string/catppuccin_gboard_label" })
                }
            }
            require(catalog && labels) { "Could not locate Gboard's bundled theme catalog" }
        }
    }
}
