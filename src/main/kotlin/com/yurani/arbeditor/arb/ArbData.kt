package com.yurani.arbeditor.arb

import javax.swing.table.AbstractTableModel

/**
 * Backing table model for the ARB editor JBTable.
 *
 * Column 0  → KEY  (read-only, keys are managed via Add/Delete buttons)
 * Column 1+ → one column per language file
 */
class ArbTableModel(
    val allKeys: MutableList<String>,
    val languages: List<String>,
    private val data: MutableList<MutableList<String>>   // data[rowIndex][langIndex]
) : AbstractTableModel() {

    // ── TableModel interface ─────────────────────────────────────────────────

    override fun getRowCount(): Int = allKeys.size
    override fun getColumnCount(): Int = 1 + languages.size

    override fun getColumnName(col: Int): String =
        if (col == 0) "KEY" else languages[col - 1].uppercase()

    /** Key column is intentionally read-only; translation columns are editable. */
    override fun isCellEditable(row: Int, col: Int): Boolean = col > 0

    override fun getValueAt(row: Int, col: Int): Any =
        if (col == 0) allKeys[row] else data[row][col - 1]

    override fun setValueAt(value: Any?, row: Int, col: Int) {
        if (col == 0) return                          // keys are not editable inline
        data[row][col - 1] = value?.toString() ?: ""
        fireTableCellUpdated(row, col)
    }

    // ── Convenience accessors ────────────────────────────────────────────────

    fun getTranslation(row: Int, langIndex: Int): String = data[row][langIndex]

    // ── Mutation helpers (used by Add / Delete buttons) ──────────────────────

    fun addKey(key: String) {
        allKeys.add(key)
        data.add(MutableList(languages.size) { "" })
        val idx = allKeys.lastIndex
        fireTableRowsInserted(idx, idx)
    }

    fun deleteKey(row: Int) {
        if (row < 0 || row >= allKeys.size) return
        allKeys.removeAt(row)
        data.removeAt(row)
        fireTableRowsDeleted(row, row)
    }

    /** Renames the key at [row] to [newKey] and fires a cell-update event. */
    fun renameKey(row: Int, newKey: String) {
        if (row < 0 || row >= allKeys.size) return
        allKeys[row] = newKey
        fireTableCellUpdated(row, 0)
    }

    // ── Bulk operations ──────────────────────────────────────────────────────

    /** Sorts all keys alphabetically in-place and fires a full data-changed event. */
    fun sortKeysAlphabetically() {
        if (allKeys.isEmpty()) return
        val combined = allKeys.zip(data).sortedBy { it.first }
        allKeys.clear(); allKeys.addAll(combined.map { it.first })
        data.clear();    data.addAll(combined.map { it.second })
        fireTableDataChanged()
    }

    /**
     * For every blank translation cell in non-reference language columns,
     * copies the value from [refLangIndex].  Returns the number of cells filled.
     */
    fun fillEmptyFromReference(refLangIndex: Int): Int {
        var filled = 0
        for (row in allKeys.indices) {
            val ref = data[row][refLangIndex]
            if (ref.isBlank()) continue
            for (langIdx in languages.indices) {
                if (langIdx == refLangIndex) continue
                if (data[row][langIdx].isBlank()) {
                    data[row][langIdx] = ref
                    filled++
                }
            }
        }
        if (filled > 0) fireTableDataChanged()
        return filled
    }

    // ── Factories ────────────────────────────────────────────────────────────

    companion object {
        fun empty() = ArbTableModel(mutableListOf(), emptyList(), mutableListOf())
    }
}
