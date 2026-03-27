package org.jetbrains.plugins.template.arb

import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.regex.PatternSyntaxException
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.RowFilter
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.TableModelEvent
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableRowSorter

class ArbEditorPanel(private val project: Project) : JPanel(BorderLayout(0, 4)) {

    // ── State ─────────────────────────────────────────────────────────────────

    private var currentDir: File? = null
    private var arbFiles: List<ArbFile> = emptyList()
    private var model: ArbTableModel = ArbTableModel.empty()
    private var isSaving = false

    /** Keys whose metadata detail row is currently expanded (▴ visible). */
    private val expandedKeys = mutableSetOf<String>()

    // ── UI ────────────────────────────────────────────────────────────────────

    private val table       = JBTable()
    private val searchField = SearchTextField()
    private var rowSorter: TableRowSorter<ArbTableModel>? = null
    private val statusLabel = JBLabel(" ")

    /** Welcome / recent-folders panel, shown when no folder is loaded. */
    private val welcomePanel = JPanel(BorderLayout())

    /** Scroll pane wrapping the table, shown after a folder is loaded. */
    private val tableScrollPane = JBScrollPane(table)

    private val folderBtn = JButton("Select ARB Folder", AllIcons.Nodes.Folder).apply {
        toolTipText = "Choose a folder containing .arb files"
        addActionListener { selectFolder() }
    }

    init { buildUI() }

    // ── Build UI ──────────────────────────────────────────────────────────────

    private fun buildUI() {
        border = BorderFactory.createEmptyBorder(4, 4, 4, 4)

        // ── Add popup: "Add Key" | "Add Language" ─────────────────────────────
        val addGroup = DefaultActionGroup().apply {
            templatePresentation.text = "Add"
            templatePresentation.icon = AllIcons.General.Add
            isPopup = true
            add(object : AnAction("Add Key", "Add a new translation key to all ARB files",
                AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) = addKey()
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = currentDir != null }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            add(object : AnAction("Add Language", "Create a new .arb language file",
                AllIcons.Actions.AddFile) {
                override fun actionPerformed(e: AnActionEvent) = addLanguage()
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = currentDir != null }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
        }

        // ── Main toolbar ──────────────────────────────────────────────────────
        val toolbarGroup = DefaultActionGroup().apply {
            add(addGroup)
            add(object : AnAction("Delete Key", "Delete the selected key from all ARB files",
                AllIcons.General.Remove) {
                override fun actionPerformed(e: AnActionEvent) = deleteSelectedKey()
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = table.selectedRow >= 0 }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            addSeparator()
            add(object : AnAction("Refresh", "Re-read all ARB files from disk",
                AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = reload()
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = currentDir != null }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            addSeparator()
            add(object : AnAction("Sort A→Z", "Sort translation keys alphabetically",
                AllIcons.ObjectBrowser.Sorted) {
                override fun actionPerformed(e: AnActionEvent) = sortKeysAlphabetically()
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = model.rowCount > 0 }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            add(object : AnAction("Export CSV", "Export all translations to a CSV file",
                AllIcons.Actions.Upload) {
                override fun actionPerformed(e: AnActionEvent) = exportToCsv()
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = model.rowCount > 0 }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            add(object : AnAction("Fill from Reference",
                "Copy a reference language's values into every blank cell of other languages",
                AllIcons.Actions.Forward) {
                override fun actionPerformed(e: AnActionEvent) = fillEmptyFromReference()
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = model.languages.size >= 2
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
        }

        val toolbar = ActionManager.getInstance()
            .createActionToolbar("ArbEditorToolbar", toolbarGroup, true)
            .also { it.targetComponent = this }

        // ── Search ────────────────────────────────────────────────────────────
        searchField.apply {
            textEditor.emptyText.setText("Filter keys…")
            preferredSize = Dimension(220, preferredSize.height)
            textEditor.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent)  = applyFilter()
                override fun removeUpdate(e: DocumentEvent)  = applyFilter()
                override fun changedUpdate(e: DocumentEvent) = applyFilter()
            })
        }

        // ── Top bar ───────────────────────────────────────────────────────────
        val topPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(folderBtn)
            add(toolbar.component)
            add(searchField)
            add(JButton(AllIcons.Actions.Help).apply {
                toolTipText = "About ARB Editor"
                isBorderPainted = false
                isContentAreaFilled = false
                preferredSize = Dimension(28, 28)
                addActionListener { showAboutDialog() }
            })
        }

        // ── Table ─────────────────────────────────────────────────────────────
        table.apply {
            setShowGrid(true)
            autoResizeMode = JTable.AUTO_RESIZE_OFF
            tableHeader.reorderingAllowed = false
            rowHeight = 28
            setDefaultEditor(Any::class.java, DefaultCellEditor(JTextField()))
        }

        add(topPanel,            BorderLayout.NORTH)
        add(welcomePanel,        BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).also { it.add(statusLabel) },
            BorderLayout.SOUTH)

        // Show welcome / recent folders on initial load
        refreshWelcomePanel()
    }

    // ── Welcome / Recent Folders Panel ────────────────────────────────────────

    /** Rebuilds the welcome panel with the latest recent-folder entries. */
    private fun refreshWelcomePanel() {
        welcomePanel.removeAll()

        val center = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            gridx = 0; gridy = 0
            anchor = GridBagConstraints.CENTER
            insets = JBUI.emptyInsets()
        }

        // ── Title ──
        center.add(JLabel("ARB Editor").apply {
            font = font.deriveFont(java.awt.Font.BOLD, 18f)
            horizontalAlignment = SwingConstants.CENTER
        }, gbc)

        gbc.gridy++
        gbc.insets = JBUI.insets(4, 0, 16, 0)
        center.add(JLabel("Select a folder or open a recent project").apply {
            foreground = JBColor.GRAY
            horizontalAlignment = SwingConstants.CENTER
        }, gbc)

        // ── Recent entries ──
        val recentService = RecentFolderService.getInstance(project)
        val entries = recentService.getRecentEntries()

        if (entries.isNotEmpty()) {
            gbc.gridy++
            gbc.insets = JBUI.insetsBottom(8)
            gbc.anchor = GridBagConstraints.WEST
            center.add(JLabel("Recent Folders").apply {
                font = font.deriveFont(java.awt.Font.BOLD, 13f)
            }, gbc)

            val dateFmt = SimpleDateFormat("dd MMM yyyy, HH:mm")

            for (entry in entries) {
                gbc.gridy++
                gbc.insets = JBUI.insetsBottom(4)
                gbc.fill = GridBagConstraints.HORIZONTAL
                gbc.weightx = 1.0

                val folderName = File(entry.path).name
                val folderPath = entry.path
                val dateStr = dateFmt.format(Date(entry.lastOpened))
                val existsOnDisk = File(entry.path).isDirectory

                val cardBg = if (existsOnDisk)
                    JBColor(Color(245, 247, 255), Color(50, 52, 62))
                else
                    JBColor(Color(250, 240, 240), Color(60, 48, 48))

                val cardHoverBg = JBColor(Color(232, 236, 255), Color(58, 62, 75))

                val card = JPanel(BorderLayout(8, 2)).apply {
                    isOpaque = true
                    background = cardBg
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(
                            JBColor(Color(220, 224, 240), Color(65, 68, 80)), 1, true
                        ),
                        BorderFactory.createEmptyBorder(8, 12, 8, 12)
                    )
                    cursor = if (existsOnDisk)
                        Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    else
                        Cursor.getDefaultCursor()

                    // Left: folder icon + name + path
                    val leftPanel = JPanel().apply {
                        isOpaque = false
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        add(JLabel(folderName, AllIcons.Nodes.Folder, SwingConstants.LEFT).apply {
                            font = font.deriveFont(java.awt.Font.BOLD, 13f)
                            if (!existsOnDisk) foreground = JBColor.GRAY
                        })
                        add(Box.createVerticalStrut(2))
                        add(JLabel(folderPath).apply {
                            font = font.deriveFont(11f)
                            foreground = JBColor.GRAY
                        })
                    }

                    // Right: stats + date
                    val statsText = if (existsOnDisk) {
                        buildString {
                            append("${entry.totalKeys} keys · ${entry.languageCount} lang")
                            if (entry.missingCount > 0)
                                append(" · ${entry.missingCount} missing")
                            else
                                append(" · all present")
                        }
                    } else {
                        "Folder not found"
                    }

                    val statsFg = if (!existsOnDisk)
                        JBColor(Color(180, 80, 80), Color(200, 100, 100))
                    else if (entry.missingCount > 0)
                        JBColor(Color(180, 130, 50), Color(220, 180, 80))
                    else
                        JBColor(Color(60, 140, 80), Color(100, 190, 120))

                    val rightPanel = JPanel().apply {
                        isOpaque = false
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        add(JLabel(statsText).apply {
                            font = font.deriveFont(11f)
                            foreground = statsFg
                            horizontalAlignment = SwingConstants.RIGHT
                            alignmentX = RIGHT_ALIGNMENT
                        })
                        add(Box.createVerticalStrut(2))
                        add(JLabel(dateStr).apply {
                            font = font.deriveFont(10f)
                            foreground = JBColor.GRAY
                            horizontalAlignment = SwingConstants.RIGHT
                            alignmentX = RIGHT_ALIGNMENT
                        })
                    }

                    add(leftPanel, BorderLayout.CENTER)
                    add(rightPanel, BorderLayout.EAST)

                    if (existsOnDisk) {
                        val folderFile = File(entry.path)
                        addMouseListener(object : MouseAdapter() {
                            override fun mouseClicked(e: MouseEvent) {
                                openFolder(folderFile)
                            }
                            override fun mouseEntered(e: MouseEvent) {
                                (e.source as JPanel).background = cardHoverBg
                            }
                            override fun mouseExited(e: MouseEvent) {
                                (e.source as JPanel).background = cardBg
                            }
                        })
                    }
                }
                center.add(card, gbc)
            }

            gbc.fill = GridBagConstraints.NONE
            gbc.weightx = 0.0
        }

        // ── "Open ARB Folder" button at the bottom ──
        gbc.gridy++
        gbc.insets = JBUI.insetsTop(16)
        gbc.anchor = GridBagConstraints.CENTER
        gbc.fill = GridBagConstraints.NONE
        center.add(JButton("Open ARB Folder…", AllIcons.Nodes.Folder).apply {
            addActionListener { selectFolder() }
        }, gbc)

        welcomePanel.add(center, BorderLayout.CENTER)
        welcomePanel.revalidate()
        welcomePanel.repaint()
    }

    /** Switch from welcome panel to table view. */
    private fun showTableView() {
        remove(welcomePanel)
        add(tableScrollPane, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    // ── Folder ────────────────────────────────────────────────────────────────

    private fun selectFolder() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
            title       = "Select ARB Folder"
            description = "Choose the directory that contains your .arb translation files"
        }
        FileChooser.chooseFile(descriptor, project, null)?.let { vf ->
            openFolder(File(vf.path))
        }
    }

    /** Opens a folder by path — used both from the chooser and from recent entries. */
    private fun openFolder(dir: File) {
        currentDir = dir
        updateFolderButton()
        reload()
        showTableView()
    }

    private fun updateFolderButton() {
        val dir = currentDir
        if (dir == null) {
            folderBtn.text        = "Select ARB Folder"
            folderBtn.toolTipText = "Choose a folder containing .arb files"
        } else {
            folderBtn.text        = dir.name
            folderBtn.toolTipText = "${dir.absolutePath}\n\nClick to change folder"
        }
    }

    // ── Load / Reload ─────────────────────────────────────────────────────────

    private fun reload() {
        val dir = currentDir ?: return
        expandedKeys.clear()
        val (newModel, newFiles) = ArbService.loadFolder(dir)
        model    = newModel
        arbFiles = newFiles
        bindTableModel()
        updateStatus()
        recordRecentFolder()
    }

    /** Records the current folder + stats into the recent-folders service. */
    private fun recordRecentFolder() {
        val dir = currentDir ?: return
        val missing = (0 until model.rowCount).sumOf { row ->
            (0 until model.languages.size).count { lang ->
                model.getTranslation(row, lang).isBlank()
            }
        }
        RecentFolderService.getInstance(project).recordFolder(
            path = dir.absolutePath,
            totalKeys = model.rowCount,
            languageCount = model.languages.size,
            missingCount = missing
        )
    }

    private fun bindTableModel() {
        table.model = model

        // KEY column — inner class renderer, gear pinned to the EAST
        table.columnModel.getColumn(0).apply {
            preferredWidth  = 280
            cellRenderer    = KeyColumnRenderer()
        }
        // Language columns — header with ✕ button, cells with missing-translation highlight
        for (col in 1 until table.columnCount) {
            table.columnModel.getColumn(col).apply {
                preferredWidth = 200
                cellRenderer   = TranslationCellRenderer()
                // Each column gets its OWN renderer instance (separate rubber-stamp panel)
                headerRenderer = LanguageHeaderRenderer()
            }
        }

        rowSorter = TableRowSorter(model).also { table.rowSorter = it }

        // Auto-save on translated-cell edits
        model.addTableModelListener { e ->
            if (!isSaving
                && e.type == TableModelEvent.UPDATE
                && e.firstRow >= 0
                && e.column > 0)
            { saveAll(); updateStatus() }
        }

        // Gear click → metadata dialog (remove stale listener first)
        table.mouseListeners.filterIsInstance<KeyColumnMouseListener>()
            .forEach { table.removeMouseListener(it) }
        table.addMouseListener(KeyColumnMouseListener())

        // Right-click context menu
        table.mouseListeners.filterIsInstance<TableContextMenuListener>()
            .forEach { table.removeMouseListener(it) }
        table.addMouseListener(TableContextMenuListener())

        // Header ✕ click → delete language
        table.tableHeader.mouseListeners.filterIsInstance<TableHeaderMouseListener>()
            .forEach { table.tableHeader.removeMouseListener(it) }
        table.tableHeader.addMouseListener(TableHeaderMouseListener())

        applyFilter()
        updateRowHeights()
    }

    // ── Row heights (expanded / collapsed) ────────────────────────────────────

    /** Recalculates per-row heights: expanded rows show metadata details. */
    private fun updateRowHeights() {
        for (viewRow in 0 until table.rowCount) {
            val modelRow = table.convertRowIndexToModel(viewRow)
            val key = model.allKeys.getOrNull(modelRow) ?: continue
            table.setRowHeight(viewRow, if (expandedKeys.contains(key)) 96 else 28)
        }
    }

    // ── Filter ────────────────────────────────────────────────────────────────

    private fun applyFilter() {
        val text = searchField.text.trim()
        rowSorter?.rowFilter = when {
            text.isEmpty() -> null
            else -> try {
                RowFilter.regexFilter("(?i)${java.util.regex.Pattern.quote(text)}", 0)
            } catch (_: PatternSyntaxException) { null }
        }
        updateRowHeights()
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    private fun saveAll() {
        if (arbFiles.isEmpty()) return
        isSaving = true
        try   { ArbService.saveAll(model, arbFiles) }
        finally { isSaving = false }
    }

    // ── Add Key ───────────────────────────────────────────────────────────────

    private fun addKey() {
        val key = Messages.showInputDialog(
            project, "Enter new translation key:", "Add Key",
            Messages.getQuestionIcon()
        )?.trim() ?: return
        when {
            key.isBlank() ->
                Messages.showErrorDialog(project, "Key must not be empty.", "Invalid Key")
            key.startsWith("@") ->
                Messages.showErrorDialog(project,
                    "Keys starting with '@' are reserved for ARB metadata.", "Invalid Key")
            model.allKeys.contains(key) ->
                Messages.showErrorDialog(project, "Key '$key' already exists.", "Duplicate Key")
            else -> {
                model.addKey(key)
                saveAll(); updateStatus()
                updateRowHeights()
                val lastView = table.convertRowIndexToView(model.rowCount - 1)
                if (lastView >= 0) table.scrollRectToVisible(table.getCellRect(lastView, 0, true))
            }
        }
    }

    // ── Add Language ──────────────────────────────────────────────────────────

    private fun addLanguage() {
        val dir = currentDir ?: return
        val langCode = Messages.showInputDialog(
            project,
            "Enter language code for the new ARB file\n(examples: de, ja, pt-BR, zh-Hans):",
            "Add Language", Messages.getQuestionIcon()
        )?.trim() ?: return
        when {
            langCode.isBlank() ->
                Messages.showErrorDialog(project, "Language code must not be empty.", "Invalid Language")
            model.languages.contains(langCode) ->
                Messages.showErrorDialog(project,
                    "Language '$langCode' already exists.", "Duplicate Language")
            else -> {
                val target = File(dir, "$langCode.arb")
                if (target.exists()) {
                    val load = Messages.showYesNoDialog(project,
                        "'$langCode.arb' already exists.\nLoad it into the editor?",
                        "File Already Exists", Messages.getWarningIcon())
                    if (load != Messages.YES) return
                } else {
                    try { ArbService.createLanguageFile(dir, langCode, model.allKeys) }
                    catch (ex: Exception) {
                        Messages.showErrorDialog(project,
                            "Could not create '$langCode.arb':\n${ex.message}", "Error")
                        return
                    }
                }
                reload()
            }
        }
    }

    // ── Delete Language ───────────────────────────────────────────────────────

    private fun deleteLanguage(langCode: String) {
        val dir = currentDir ?: return
        val confirm = Messages.showYesNoDialog(
            project,
            "Delete language '$langCode'?\n\nThis will permanently remove '$langCode.arb' from disk.",
            "Delete Language", Messages.getWarningIcon()
        )
        if (confirm != Messages.YES) return
        try {
            File(dir, "$langCode.arb").delete()
        } catch (ex: Exception) {
            Messages.showErrorDialog(project,
                "Could not delete '$langCode.arb': ${ex.message}", "Error")
            return
        }
        reload()
    }

    // ── Delete Key ────────────────────────────────────────────────────────────

    private fun deleteSelectedKey() {
        val viewRow  = table.selectedRow.takeIf { it >= 0 } ?: return
        val modelRow = table.convertRowIndexToModel(viewRow)
        val key      = model.allKeys[modelRow]
        val confirm  = Messages.showYesNoDialog(
            project,
            "Delete key '$key' from all ${arbFiles.size} ARB file(s)?",
            "Delete Key", Messages.getWarningIcon()
        )
        if (confirm != Messages.YES) return
        expandedKeys.remove(key)
        model.deleteKey(modelRow); saveAll(); updateStatus()
        updateRowHeights()
    }

    // ── Metadata dialog ───────────────────────────────────────────────────────

    private fun showMetadataDialog(modelRow: Int) {
        if (modelRow < 0 || modelRow >= model.allKeys.size) return
        val key = model.allKeys[modelRow]

        // 1. Read existing @key annotation (may be null)
        val existingMeta = arbFiles
            .mapNotNull { it.metadata["@$key"] }
            .firstOrNull()
            ?.let { if (it.isJsonObject) it.asJsonObject else null }

        // 2. Bug fix #3 — auto-detect {varName} tokens across every translation value
        //    for this key and pre-populate placeholder entries the user hasn't added yet.
        val detectedVars = mutableSetOf<String>()
        model.languages.indices.forEach { langIdx ->
            PLACEHOLDER_VAR_REGEX.findAll(model.getTranslation(modelRow, langIdx))
                .forEach { detectedVars.add(it.groupValues[1]) }
        }

        // 3. Build a seed: deep-copy existing meta and inject any newly detected vars
        val seedMeta: JsonObject? = when {
            detectedVars.isEmpty() -> existingMeta
            else -> {
                val base = existingMeta?.deepCopy()?.asJsonObject ?: JsonObject()
                val phs  = base.getAsJsonObject("placeholders")
                    ?: JsonObject().also { base.add("placeholders", it) }
                detectedVars.forEach { v -> if (!phs.has(v)) phs.add(v, JsonObject()) }
                base
            }
        }

        val dialog = KeyMetadataDialog(project, key, seedMeta)
        if (!dialog.showAndGet()) return

        val newMeta = dialog.getMetadata()
        arbFiles.forEach { f ->
            if (newMeta == null) f.metadata.remove("@$key")
            else                 f.metadata["@$key"] = newMeta
        }
        saveAll()
        // Auto-expand the key if metadata was just added
        if (newMeta != null) expandedKeys.add(key) else expandedKeys.remove(key)
        updateRowHeights()
        table.repaint()
    }

    // ── Sort ──────────────────────────────────────────────────────────────────

    private fun sortKeysAlphabetically() {
        if (model.rowCount == 0) return
        model.sortKeysAlphabetically()
        isSaving = true
        try { ArbService.saveAll(model, arbFiles) } finally { isSaving = false }
        applyFilter()
    }

    // ── Export CSV ────────────────────────────────────────────────────────────

    private fun exportToCsv() {
        if (model.rowCount == 0) {
            Messages.showInfoMessage(project,
                "No data to export. Load an ARB folder first.", "Export CSV")
            return
        }
        val descriptor = FileSaverDescriptor("Export Translations", "Save as CSV", "csv")
        val wrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(null as com.intellij.openapi.vfs.VirtualFile?, "translations.csv") ?: return
        try {
            ArbService.exportToCsv(model, wrapper.file)
            Messages.showInfoMessage(project,
                "Exported ${model.rowCount} keys to '${wrapper.file.name}'.", "Export Done")
        } catch (ex: Exception) {
            Messages.showErrorDialog(project, "Export failed: ${ex.message}", "Export Error")
        }
    }

    // ── Fill from reference (redesigned) ──────────────────────────────────────

    /**
     * Lets the user pick a reference language, then copies every non-blank
     * reference value into the blank cells of other languages.
     * Use-case: after adding new keys the reference text serves as a
     * placeholder so translators know what to translate.
     */
    private fun fillEmptyFromReference() {
        if (model.languages.size < 2) return

        // 1. Let user choose reference language
        val options = model.languages.toTypedArray()
        val choice  = Messages.showChooseDialog(
            project,
            "Pick the reference language.\n\n" +
            "Every blank cell in the other languages will be filled\n" +
            "with the reference text as a placeholder so translators\n" +
            "can see what needs to be translated.",
            "Fill from Reference",
            Messages.getQuestionIcon(),
            options,
            options[0]
        )
        if (choice < 0) return  // cancelled

        // 2. Count how many blank cells would be filled
        val blankCount = (0 until model.rowCount).sumOf { row ->
            model.languages.indices.count { langIdx ->
                langIdx != choice
                    && model.getTranslation(row, langIdx).isBlank()
                    && model.getTranslation(row, choice).isNotBlank()
            }
        }

        if (blankCount == 0) {
            Messages.showInfoMessage(project,
                "No blank cells found in other languages.\n" +
                "All translations are already filled.",
                "Nothing to Fill")
            return
        }

        // 3. Confirm before applying
        val confirm = Messages.showYesNoDialog(
            project,
            "This will copy '${model.languages[choice]}' text into $blankCount blank cell(s)\n" +
            "across the other ${model.languages.size - 1} language(s).\n\n" +
            "You can then replace the placeholder text with the\n" +
            "proper translation for each language.\n\n" +
            "Proceed?",
            "Fill from Reference",
            Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) return

        // 4. Apply
        val filled = model.fillEmptyFromReference(choice)
        isSaving = true
        try { if (filled > 0) ArbService.saveAll(model, arbFiles) } finally { isSaving = false }
        updateStatus()
        Messages.showInfoMessage(project,
            "Done — filled $filled cell(s) using '${model.languages[choice]}' as reference.",
            "Fill from Reference")
    }

    // ── About ──────────────────────────────────────────────────────────────

    private fun showAboutDialog() {
        Messages.showInfoMessage(
            project,
            "ARB Editor v1.0.0\n" +
            "Author: Yusril Rapsanjani\n" +
            "Released: 2026\n\n" +
            "A synchronized ARB translation editor.\n" +
            "Edit all language translations in one table.",
            "About ARB Editor"
        )
    }

    // ── Status ────────────────────────────────────────────────────────────────

    private fun updateStatus() {
        if (arbFiles.isEmpty()) { statusLabel.text = " "; return }
        val missing = (0 until model.rowCount).sumOf { row ->
            (0 until model.languages.size).count { lang ->
                model.getTranslation(row, lang).isBlank()
            }
        }
        statusLabel.text = when (missing) {
            0    -> "${model.rowCount} keys · ${model.languages.size} languages · ✓ all translations present"
            else -> "${model.rowCount} keys · ${model.languages.size} languages · ⚠ $missing missing translation(s)"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mouse listeners
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Combined mouse listener for the KEY column:
     *  • Left zone (first 20px)  → ▾/▴ expand/collapse toggle
     *  • Right zone (last 24px)  → ⚙ gear → metadata dialog
     */
    private inner class KeyColumnMouseListener : MouseAdapter() {
        private val gearHitWidth   = AllIcons.General.Settings.iconWidth + 14
        private val toggleHitWidth = 20

        override fun mouseClicked(e: MouseEvent) {
            if (e.clickCount != 1) return
            val viewCol = table.columnAtPoint(e.point)
            val viewRow = table.rowAtPoint(e.point)
            if (viewCol != 0 || viewRow < 0) return
            val cellRect = table.getCellRect(viewRow, 0, true)
            val modelRow = table.convertRowIndexToModel(viewRow)

            // Right edge → gear → metadata dialog
            if (e.x >= cellRect.x + cellRect.width - gearHitWidth) {
                showMetadataDialog(modelRow)
                return
            }

            // Left edge → ▾/▴ toggle → expand/collapse metadata detail
            if (e.x <= cellRect.x + toggleHitWidth) {
                val key = model.allKeys.getOrNull(modelRow) ?: return
                val hasMeta = arbFiles.any { it.metadata.containsKey("@$key") }
                if (!hasMeta) return   // nothing to expand
                if (expandedKeys.contains(key)) expandedKeys.remove(key)
                else expandedKeys.add(key)
                updateRowHeights()
                table.repaint()
            }
        }
    }

    /** Right-click context menu on any table row. */
    private inner class TableContextMenuListener : MouseAdapter() {
        override fun mousePressed(e: MouseEvent)  { if (e.isPopupTrigger) show(e) }
        override fun mouseReleased(e: MouseEvent) { if (e.isPopupTrigger) show(e) }

        private fun show(e: MouseEvent) {
            val viewRow = table.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
            table.setRowSelectionInterval(viewRow, viewRow)
            val modelRow = table.convertRowIndexToModel(viewRow)
            val key      = model.allKeys.getOrNull(modelRow) ?: return

            JPopupMenu().apply {
                add(JMenuItem("Edit Metadata for '$key'…", AllIcons.General.Settings)
                    .also { it.addActionListener { showMetadataDialog(modelRow) } })
                addSeparator()
                add(JMenuItem("Copy Flutter Code").also { item ->
                    item.addActionListener {
                        copyToClipboard("AppLocalizations.of(context)!.$key")
                    }
                })
                add(JMenuItem("Copy Key Name").also { item ->
                    item.addActionListener { copyToClipboard(key) }
                })
            }.show(table, e.x, e.y)
        }

        private fun copyToClipboard(text: String) =
            Toolkit.getDefaultToolkit().systemClipboard
                .setContents(StringSelection(text), null)
    }

    /** Detects clicks on the ✕ icon in language column headers. */
    private inner class TableHeaderMouseListener : MouseAdapter() {
        private val hitZoneWidth = AllIcons.General.Remove.iconWidth + 10

        override fun mouseClicked(e: MouseEvent) {
            if (e.button != MouseEvent.BUTTON1) return
            val col = table.tableHeader.columnAtPoint(e.point)
            if (col <= 0) return
            val rect = table.tableHeader.getHeaderRect(col)
            if (e.x >= rect.x + rect.width - hitZoneWidth) {
                deleteLanguage(model.languages.getOrNull(col - 1) ?: return)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cell / header renderers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * KEY column renderer with three zones:
     *  WEST   → ▾/▴ expand toggle (only shown for keys that have metadata)
     *  CENTER → key name (bold when metadata exists) + expanded detail text
     *  EAST   → ⚙ gear icon (always)
     *
     * When a key is expanded (▴), the row is 96px tall and the CENTER label
     * shows description / type / placeholders below the key name in a clean,
     * well-spaced layout with readable font sizes.
     */
    private inner class KeyColumnRenderer : TableCellRenderer {
        private val bg       = JBColor(Color(245, 245, 252), Color(50, 50, 60))
        private val metaBg   = JBColor(Color(235, 240, 255), Color(45, 48, 58))
        private val metaFg   = JBColor(Color(100, 110, 140), Color(150, 160, 190))
        private val gearIcon = AllIcons.General.Settings

        // Muted color for metadata detail lines (light / dark)
        private val detailColor = JBColor(Color(80, 95, 140), Color(150, 165, 200))

        private val panel = JPanel(BorderLayout(2, 0)).apply { isOpaque = true }
        private val toggleLabel = JLabel().apply {
            isOpaque = false
            horizontalAlignment = SwingConstants.CENTER
            border = BorderFactory.createEmptyBorder(0, 2, 0, 0)
            preferredSize = Dimension(20, 20)
        }
        private val keyLabel = JLabel().apply {
            isOpaque = false
            verticalAlignment = SwingConstants.TOP
            border = BorderFactory.createEmptyBorder(3, 4, 2, 0)
        }
        private val gearLabel = JLabel(gearIcon).apply {
            isOpaque = false
            verticalAlignment = SwingConstants.TOP
            border = BorderFactory.createEmptyBorder(3, 2, 0, 6)
        }

        init {
            panel.add(toggleLabel, BorderLayout.WEST)
            panel.add(keyLabel, BorderLayout.CENTER)
            panel.add(gearLabel, BorderLayout.EAST)
        }

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val key      = value?.toString() ?: ""
            val modelRow = table.convertRowIndexToModel(row)
            val realKey  = model.allKeys.getOrNull(modelRow) ?: key
            val hasMeta  = arbFiles.any { it.metadata.containsKey("@$realKey") }
            val isExpanded = expandedKeys.contains(realKey)

            // ── Toggle icon (▾ collapsed, ▴ expanded) ──
            toggleLabel.text = when {
                !hasMeta   -> " "
                isExpanded -> "▴"
                else       -> "▾"
            }
            toggleLabel.foreground = metaFg
            toggleLabel.font = table.font.deriveFont(java.awt.Font.BOLD, 14f)
            toggleLabel.verticalAlignment =
                if (isExpanded) SwingConstants.TOP else SwingConstants.CENTER

            // ── Key text + inline metadata detail (Swing-safe HTML only) ──
            val colorHex = String.format("#%06x", detailColor.rgb and 0xFFFFFF)

            val html = buildString {
                append("<html>")
                if (hasMeta) append("<b>$key</b>") else append(key)

                if (isExpanded && hasMeta) {
                    val meta = arbFiles
                        .mapNotNull { it.metadata["@$realKey"] }
                        .firstOrNull()
                        ?.let { if (it.isJsonObject) it.asJsonObject else null }
                    if (meta != null) {
                        meta.get("description")?.asString?.let { desc ->
                            append("<br><font color='$colorHex'>description: $desc</font>")
                        }
                        meta.get("type")?.asString?.let { type ->
                            append("<br><font color='$colorHex'>type: <i>$type</i></font>")
                        }
                        meta.getAsJsonObject("placeholders")?.keySet()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { phs ->
                                append("<br><font color='$colorHex'>placeholders: ${phs.joinToString(", ")}</font>")
                            }
                    }
                }
                append("</html>")
            }
            keyLabel.text       = html
            keyLabel.font       = table.font
            keyLabel.foreground = if (isSelected) table.selectionForeground else table.foreground

            // ── Tooltip ──
            val tip = buildMetadataTooltip(realKey)
            panel.toolTipText     = tip
            gearLabel.toolTipText = tip

            // ── Background ──
            panel.background = when {
                isSelected -> table.selectionBackground
                isExpanded -> metaBg
                else       -> bg
            }
            return panel
        }

        private fun buildMetadataTooltip(key: String): String {
            val meta = arbFiles
                .mapNotNull { it.metadata["@$key"] }
                .firstOrNull()
                ?.let { if (it.isJsonObject) it.asJsonObject else null }
                ?: return "<html>Click ⚙ to add metadata</html>"
            return buildString {
                append("<html><b>@$key</b>")
                meta.get("description")?.asString?.let { append("<br>description: $it") }
                meta.get("type")?.asString?.let { append("<br>type: $it") }
                meta.getAsJsonObject("placeholders")?.keySet()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { append("<br>placeholders: ${it.joinToString(", ")}") }
                append("<br><small>▾ Click arrow to expand · ⚙ Click gear to edit</small></html>")
            }
        }
    }

    /** Language column header — language name + ✕ icon pinned right. */
    private class LanguageHeaderRenderer : TableCellRenderer {
        private val deleteIcon = AllIcons.General.Remove
        private val panel    = JPanel(BorderLayout(2, 0)).apply { isOpaque = true }
        private val nameLabel = JLabel().apply {
            isOpaque = false
            horizontalAlignment = SwingConstants.CENTER
            border = BorderFactory.createEmptyBorder(0, 4, 0, 0)
        }
        private val delLabel = JLabel(deleteIcon).apply {
            isOpaque = false
            border   = BorderFactory.createEmptyBorder(0, 2, 0, 4)
            toolTipText = "Click to delete this language"
        }

        init { panel.add(nameLabel, BorderLayout.CENTER); panel.add(delLabel, BorderLayout.EAST) }

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            nameLabel.text    = value?.toString() ?: ""
            nameLabel.font    = table.tableHeader.font
            panel.background  = UIManager.getColor("TableHeader.background")
                ?: table.tableHeader.background
            panel.border      = UIManager.getBorder("TableHeader.cellBorder")
            panel.toolTipText = "Click ✕ to delete this language column"
            return panel
        }
    }

    /** Translation cells — missing values highlighted in soft red. */
    private class TranslationCellRenderer : TableCellRenderer {
        private val delegate  = DefaultTableCellRenderer()
        private val missingBg = JBColor(Color(255, 225, 225), Color(85, 30, 30))

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val comp = delegate.getTableCellRendererComponent(
                table, value, isSelected, hasFocus, row, column)
            if (!isSelected)
                comp.background =
                    if (value?.toString().isNullOrBlank()) missingBg else table.background
            return comp
        }
    }
}

private val PLACEHOLDER_VAR_REGEX = Regex("""\{(\w+)\}""")
