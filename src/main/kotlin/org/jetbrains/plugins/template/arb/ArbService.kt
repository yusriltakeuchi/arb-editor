package org.jetbrains.plugins.template.arb

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

// ── Data ──────────────────────────────────────────────────────────────────────

/**
 * Represents a single .arb file loaded from disk.
 *
 * [originalJson] is the full parsed JSON (including metadata keys).
 * [translationEntries] contains only the non-@ keys (the real translations).
 * [metadata] contains only the @-prefixed keys, preserving their original JsonElement
 * so complex objects (e.g. {"description":"…"}) round-trip without stringification.
 */
data class ArbFile(
    val path: File,
    val languageCode: String,
    val originalJson: JsonObject
) {
    val translationEntries: LinkedHashMap<String, String> by lazy {
        LinkedHashMap<String, String>().also { map ->
            originalJson.entrySet()
                .filter { !it.key.startsWith("@") }
                .forEach { (k, v) -> map[k] = if (v.isJsonPrimitive) v.asString else v.toString() }
        }
    }

    val metadata: LinkedHashMap<String, JsonElement> by lazy {
        LinkedHashMap<String, JsonElement>().also { map ->
            originalJson.entrySet()
                .filter { it.key.startsWith("@") }
                .forEach { (k, v) -> map[k] = v }
        }
    }
}

// ── Service ───────────────────────────────────────────────────────────────────

object ArbService {

    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create()

    // ── Load ──────────────────────────────────────────────────────────────────

    /**
     * Scans [dir] for .arb files, merges all translation keys, and returns an
     * [ArbTableModel] together with the list of parsed [ArbFile]s.
     *
     * Missing keys are auto-filled with an empty string — no key is ever absent
     * from any language column.
     */
    fun loadFolder(dir: File): Pair<ArbTableModel, List<ArbFile>> {
        val files = dir.listFiles { f -> f.extension == "arb" }
            ?.sortedBy { it.nameWithoutExtension }
            ?: return ArbTableModel.empty() to emptyList()

        if (files.isEmpty()) return ArbTableModel.empty() to emptyList()

        val arbFiles = files.map { f ->
            ArbFile(
                path = f,
                languageCode = f.nameWithoutExtension,
                originalJson = parseJsonSafely(f)
            )
        }

        // Merge translation keys from all files, preserving first-seen order
        val mergedKeys = linkedSetOf<String>()
        arbFiles.forEach { arb -> arb.translationEntries.keys.forEach { mergedKeys.add(it) } }

        val languages = arbFiles.map { it.languageCode }
        val allKeys = mergedKeys.toMutableList()

        val data: MutableList<MutableList<String>> = allKeys.map { key ->
            languages.map { lang ->
                arbFiles.find { it.languageCode == lang }?.translationEntries?.get(key) ?: ""
            }.toMutableList()
        }.toMutableList()

        return ArbTableModel(allKeys, languages, data) to arbFiles
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    /**
     * Writes every language's .arb file back to disk.
     *
     * Ordering in the output file:
     * 1. "Orphaned" metadata keys — those not associated with any current
     *    translation key (e.g. @@locale) — appear at the top.
     * 2. Each translation key, immediately followed by its @key annotation
     *    object (if any), preserving the original JsonElement structure so
     *    complex annotation objects are not stringified.
     */
    fun saveAll(model: ArbTableModel, arbFiles: List<ArbFile>) {
        model.languages.forEachIndexed { langIndex, lang ->
            val arbFile = arbFiles.find { it.languageCode == lang } ?: return@forEachIndexed
            val out = JsonObject()

            // 1. Orphaned metadata first (@@locale, etc.)
            arbFile.metadata.forEach { (k, v) ->
                val refKey = k.removePrefix("@")
                val isOrphaned = refKey.isEmpty() || !model.allKeys.contains(refKey)
                if (isOrphaned) out.add(k, v)
            }

            // 2. Translation keys + immediately-following @key annotation
            model.allKeys.forEachIndexed { keyIndex, key ->
                out.addProperty(key, model.getTranslation(keyIndex, langIndex))
                arbFile.metadata["@$key"]?.let { out.add("@$key", it) }
            }

            try {
                arbFile.path.writeText(gson.toJson(out))
            } catch (_: Exception) { /* swallow — don't crash the plugin */ }
        }
    }

    // ── Create language file ──────────────────────────────────────────────────

    /**
     * Creates a brand-new [langCode].arb inside [dir], pre-populated with
     * [keys] as empty strings and a top-level @@locale entry.
     * Throws [IOException] on failure — caller is responsible for error handling.
     */
    fun createLanguageFile(dir: File, langCode: String, keys: List<String>) {
        val file = File(dir, "$langCode.arb")
        val out = JsonObject()
        out.addProperty("@@locale", langCode)
        keys.forEach { key -> out.addProperty(key, "") }
        file.writeText(gson.toJson(out))
    }

    // ── Export ────────────────────────────────────────────────────────────────

    /**
     * Writes all translations to [outputFile] as a UTF-8 CSV.
     * Header row: key, lang1, lang2, …
     * Subsequent rows: one row per translation key.
     */
    fun exportToCsv(model: ArbTableModel, outputFile: File) {
        val sb = StringBuilder()
        // header
        sb.append("key")
        model.languages.forEach { lang -> sb.append(',').append(escapeCsv(lang)) }
        sb.append('\n')
        // rows
        model.allKeys.forEachIndexed { rowIdx, key ->
            sb.append(escapeCsv(key))
            model.languages.indices.forEach { langIdx ->
                sb.append(',').append(escapeCsv(model.getTranslation(rowIdx, langIdx)))
            }
            sb.append('\n')
        }
        outputFile.writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun escapeCsv(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n'))
            "\"${value.replace("\"", "\"\"")}\""
        else value

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseJsonSafely(file: File): JsonObject = try {
        val el = JsonParser.parseString(file.readText())
        if (el.isJsonObject) el.asJsonObject else JsonObject()
    } catch (_: Exception) {
        JsonObject()
    }
}
