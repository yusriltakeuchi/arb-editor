package com.yurani.arbeditor.arb

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

    // ── Import CSV ───────────────────────────────────────────────────────────

    /**
     * Result of parsing a CSV file for import.
     *
     * [keys]      — ordered list of translation keys (column 0)
     * [languages] — language codes from the header row (columns 1+)
     * [data]      — data\[rowIndex]\[langIndex] = translation text
     */
    data class CsvImportResult(
        val keys: List<String>,
        val languages: List<String>,
        val data: List<List<String>>
    )

    /**
     * Parses a UTF-8 CSV file into [CsvImportResult].
     * Returns null if the file is empty or has fewer than 2 columns.
     */
    fun importFromCsv(csvFile: File): CsvImportResult? {
        val lines = csvFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        val header = parseCsvLine(lines[0])
        if (header.size < 2) return null

        val languages = header.drop(1)
        val keys  = mutableListOf<String>()
        val data  = mutableListOf<List<String>>()

        for (i in 1 until lines.size) {
            val fields = parseCsvLine(lines[i])
            if (fields.isEmpty()) continue
            val key = fields[0].trim()
            if (key.isBlank()) continue
            keys.add(key)
            data.add(languages.indices.map { idx -> fields.getOrElse(idx + 1) { "" } })
        }

        return CsvImportResult(keys, languages, data)
    }

    /**
     * Merges [csv] into the current [model], updating existing translations
     * and adding new keys.
     *
     * @return Triple(newKeysCount, updatedCellsCount, newLanguages)
     */
    fun mergeCsvIntoModel(
        csv: CsvImportResult,
        model: ArbTableModel
    ): Triple<Int, Int, List<String>> {
        var newKeys = 0
        var updatedCells = 0
        val newLanguages = csv.languages.filter { it !in model.languages }

        // Build a mapping: csv lang → model lang index (only for existing languages)
        val langMapping = csv.languages.mapIndexedNotNull { csvIdx, lang ->
            val modelIdx = model.languages.indexOf(lang)
            if (modelIdx >= 0) csvIdx to modelIdx else null
        }

        csv.keys.forEachIndexed { csvRow, key ->
            val modelRow = model.allKeys.indexOf(key)
            if (modelRow < 0) {
                // New key → add it
                model.addKey(key)
                val addedRow = model.allKeys.lastIndex
                for ((csvLangIdx, modelLangIdx) in langMapping) {
                    val value = csv.data[csvRow].getOrElse(csvLangIdx) { "" }
                    if (value.isNotBlank()) {
                        model.setValueAt(value, addedRow, modelLangIdx + 1)
                        updatedCells++
                    }
                }
                newKeys++
            } else {
                // Existing key → update non-blank CSV values
                for ((csvLangIdx, modelLangIdx) in langMapping) {
                    val value = csv.data[csvRow].getOrElse(csvLangIdx) { "" }
                    if (value.isNotBlank()) {
                        val current = model.getTranslation(modelRow, modelLangIdx)
                        if (current != value) {
                            model.setValueAt(value, modelRow, modelLangIdx + 1)
                            updatedCells++
                        }
                    }
                }
            }
        }

        return Triple(newKeys, updatedCells, newLanguages)
    }

    /** Parses a single CSV line, handling quoted fields correctly. */
    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                inQuotes && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i += 2
                }
                ch == '"' -> { inQuotes = !inQuotes; i++ }
                ch == ',' && !inQuotes -> { fields.add(sb.toString()); sb.clear(); i++ }
                else -> { sb.append(ch); i++ }
            }
        }
        fields.add(sb.toString())
        return fields
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
