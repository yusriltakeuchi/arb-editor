package com.yurani.arbeditor.arb

/**
 * A group of translation keys that share an identical value in a given language.
 */
data class DuplicateGroup(
    val value: String,
    val keys: List<String>,
    val rows: List<Int>
)

/**
 * Scans a single language column for translation values that appear in more
 * than one key — possible copy-paste errors or consolidation opportunities.
 */
object DuplicateDetector {

    /**
     * Returns groups of keys whose translation value is identical within
     * the language at [langIndex].  Only groups with 2+ keys are returned.
     */
    fun findDuplicates(model: ArbTableModel, langIndex: Int): List<DuplicateGroup> {
        val valueToEntries = linkedMapOf<String, MutableList<Pair<String, Int>>>()

        for (row in 0 until model.rowCount) {
            val value = model.getTranslation(row, langIndex).trim()
            if (value.isBlank()) continue
            valueToEntries.getOrPut(value) { mutableListOf() }
                .add(model.allKeys[row] to row)
        }

        return valueToEntries
            .filter { it.value.size > 1 }
            .map { (value, pairs) ->
                DuplicateGroup(
                    value = value,
                    keys  = pairs.map { it.first },
                    rows  = pairs.map { it.second }
                )
            }
    }
}

