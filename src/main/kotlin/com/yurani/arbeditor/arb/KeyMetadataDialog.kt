package com.yurani.arbeditor.arb

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.BorderFactory
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

/**
 * Modal dialog to view and edit the @key metadata annotation for a single
 * ARB translation key.
 *
 * Supported fields (matching the Flutter ARB spec):
 *   - description  — free-text description of the string
 *   - type         — "text" | "plural" | "select"  (empty = omit)
 *   - placeholders — table of {name, type, example} rows
 *
 * Call [getMetadata] after [showAndGet] returns true to retrieve the
 * resulting [JsonObject].  Returns null when every field is blank
 * (= remove the @key annotation entirely).
 */
class KeyMetadataDialog(
    project: Project,
    key: String,               // plain param — only needed during init
    existing: JsonObject?
) : DialogWrapper(project) {

    // ── Fields ────────────────────────────────────────────────────────────────

    private val descriptionField  = JBTextField(44)
    private val typeCombo         = ComboBox(arrayOf("", "text", "plural", "select"))
    private val placeholdersModel = PlaceholderTableModel()

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        title   = "Metadata — @$key"
        isModal = true
        init()                         // ← Build UI (creates JBTable) FIRST
        populateFromExisting(existing) // ← THEN fill so the attached table receives events
    }

    /**
     * Populates all dialog controls from [existing] @key metadata.
     * Must run AFTER [init] so the [JBTable] is already attached to
     * [placeholdersModel] and will receive [fireTableRowsInserted] events.
     */
    private fun populateFromExisting(existing: JsonObject?) {
        existing ?: return

        descriptionField.text  = existing.get("description")?.asString ?: ""
        typeCombo.selectedItem = existing.get("type")?.asString        ?: ""

        existing.getAsJsonObject("placeholders")?.entrySet()?.forEach { (name, el) ->
            val obj       = if (el.isJsonObject) el.asJsonObject else JsonObject()
            val phType    = obj.get("type")?.asString    ?: ""
            val phExample = obj.get("example")?.asString ?: ""
            placeholdersModel.addRow(name, phType, phExample)
        }
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc   = GridBagConstraints().apply {
            insets  = JBUI.insets(6, 8, 4, 8)
            fill    = GridBagConstraints.HORIZONTAL
            weightx = 0.0
        }

        gbc.gridx = 0; gbc.gridy = 0
        panel.add(JBLabel("Description:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(descriptionField, gbc)

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        panel.add(JBLabel("Type:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(typeCombo, gbc)

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2
        gbc.weightx = 1.0; gbc.weighty = 1.0
        gbc.fill    = GridBagConstraints.BOTH
        gbc.insets  = JBUI.insets(8, 8, 4, 8)
        panel.add(buildPlaceholdersPanel(), gbc)

        panel.preferredSize = Dimension(540, 360)
        return panel
    }

    private fun buildPlaceholdersPanel(): JComponent {
        val phTable = JBTable(placeholdersModel).apply {
            setShowGrid(true)
            rowHeight = 24
            columnModel.getColumn(0).preferredWidth = 160   // Placeholder Name
            columnModel.getColumn(1).preferredWidth = 130   // Type
            columnModel.getColumn(2).preferredWidth = 150   // Example

            // Fix #2 ── ComboBox editor for the Dart/Flutter placeholder Type column.
            // isEditable = true so any non-standard type already stored in the file is
            // preserved rather than silently overwritten by the first list item.
            val typeEditor = ComboBox(
                arrayOf("", "String", "int", "double", "num", "DateTime", "Object")
            ).also { it.isEditable = true }
            columnModel.getColumn(1).cellEditor = DefaultCellEditor(typeEditor)
        }

        val decorator = ToolbarDecorator.createDecorator(phTable)
            .setAddAction    { placeholdersModel.addRow("", "", "") }
            .setRemoveAction {
                val row = phTable.selectedRow
                if (row >= 0) placeholdersModel.removeRow(row)
            }

        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder(
                "Placeholders  (auto-detected {var} tokens are pre-filled)"
            )
            add(decorator.createPanel(), BorderLayout.CENTER)
            preferredSize = Dimension(500, 200)
        }
    }

    // ── Result ────────────────────────────────────────────────────────────────

    /**
     * Returns the assembled [JsonObject] for the @key annotation,
     * or null if every field is blank (meaning: delete the annotation).
     */
    fun getMetadata(): JsonObject? {
        val meta = JsonObject()

        descriptionField.text.trim().takeIf { it.isNotEmpty() }
            ?.let { meta.addProperty("description", it) }

        (typeCombo.selectedItem?.toString()?.trim() ?: "").takeIf { it.isNotEmpty() }
            ?.let { meta.addProperty("type", it) }

        val placeholders = JsonObject()
        for (row in 0 until placeholdersModel.rowCount) {
            val name    = placeholdersModel.getValueAt(row, 0).toString().trim()
            if (name.isEmpty()) continue
            val phType  = placeholdersModel.getValueAt(row, 1).toString().trim()
            val example = placeholdersModel.getValueAt(row, 2).toString().trim()
            val ph = JsonObject()
            if (phType.isNotEmpty())  ph.addProperty("type",    phType)
            if (example.isNotEmpty()) ph.addProperty("example", example)
            placeholders.add(name, ph)
        }
        if (placeholders.size() > 0) meta.add("placeholders", placeholders)

        return if (meta.size() == 0) null else meta
    }

    // ── Placeholder table model ───────────────────────────────────────────────

    private class PlaceholderTableModel : AbstractTableModel() {

        private val COLUMNS = arrayOf("Placeholder Name", "Type", "Example")
        private val rows    = mutableListOf<Array<String>>()

        fun addRow(name: String, type: String, example: String) {
            rows.add(arrayOf(name, type, example))
            fireTableRowsInserted(rows.lastIndex, rows.lastIndex)
        }

        fun removeRow(index: Int) {
            if (index < 0 || index >= rows.size) return
            rows.removeAt(index)
            fireTableRowsDeleted(index, index)
        }

        override fun getRowCount()                      = rows.size
        override fun getColumnCount()                   = COLUMNS.size
        override fun getColumnName(col: Int)            = COLUMNS[col]
        override fun isCellEditable(row: Int, col: Int) = true
        override fun getValueAt(row: Int, col: Int): Any = rows[row][col]
        override fun setValueAt(value: Any?, row: Int, col: Int) {
            rows[row][col] = value?.toString() ?: ""
            fireTableCellUpdated(row, col)
        }
    }
}
