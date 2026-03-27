package com.yurani.arbeditor.arb

/**
 * Describes a placeholder mismatch in a single translation cell.
 *
 * [missing] = placeholders present in the reference language but absent here.
 * [extra]   = placeholders present here but absent in the reference language.
 */
data class PlaceholderIssue(
    val row: Int,
    val langIndex: Int,
    val key: String,
    val language: String,
    val missing: Set<String>,
    val extra: Set<String>
) {
    fun summary(): String = buildString {
        if (missing.isNotEmpty()) append("Missing: ${missing.joinToString(", ") { "{$it}" }}")
        if (missing.isNotEmpty() && extra.isNotEmpty()) append(" · ")
        if (extra.isNotEmpty()) append("Extra: ${extra.joinToString(", ") { "{$it}" }}")
    }
}

/**
 * Validates that every non-blank translation contains the same `{placeholder}`
 * tokens as the reference language.  Missing or extra tokens cause Flutter
 * runtime crashes — this validator catches them at edit-time.
 */
object PlaceholderValidator {

    private val PLACEHOLDER_REGEX = Regex("""\{(\w+)\}""")

    /** Extracts all `{name}` tokens from [text]. */
    fun extractPlaceholders(text: String): Set<String> =
        PLACEHOLDER_REGEX.findAll(text).map { it.groupValues[1] }.toSet()

    /**
     * Validates every cell against the reference language at [refLangIndex].
     *
     * @return a map keyed by `(translationKey, languageCode)` for fast lookup
     *         in cell renderers.
     */
    fun validate(
        model: ArbTableModel,
        refLangIndex: Int
    ): Map<Pair<String, String>, PlaceholderIssue> {
        val issues = mutableMapOf<Pair<String, String>, PlaceholderIssue>()

        for (row in 0 until model.rowCount) {
            val refText = model.getTranslation(row, refLangIndex)
            val refPlaceholders = extractPlaceholders(refText)

            for (langIdx in model.languages.indices) {
                if (langIdx == refLangIndex) continue
                val text = model.getTranslation(row, langIdx)
                if (text.isBlank()) continue          // skip untranslated cells

                val langPlaceholders = extractPlaceholders(text)
                val missing = refPlaceholders - langPlaceholders
                val extra   = langPlaceholders - refPlaceholders

                if (missing.isNotEmpty() || extra.isNotEmpty()) {
                    val issue = PlaceholderIssue(
                        row       = row,
                        langIndex = langIdx,
                        key       = model.allKeys[row],
                        language  = model.languages[langIdx],
                        missing   = missing,
                        extra     = extra
                    )
                    issues[issue.key to issue.language] = issue
                }
            }
        }
        return issues
    }
}

