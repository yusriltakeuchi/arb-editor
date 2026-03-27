package org.jetbrains.plugins.template.arb

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * Persists the last 5 ARB folders opened by the user, together with
 * summary stats (total keys, language count, missing translations)
 * so the welcome screen can show useful information at a glance.
 */
@Service(Service.Level.PROJECT)
@State(name = "ArbRecentFolders", storages = [Storage("arbEditorRecent.xml")])
class RecentFolderService : PersistentStateComponent<RecentFolderService.State> {

    data class FolderEntry(
        var path: String = "",
        var totalKeys: Int = 0,
        var languageCount: Int = 0,
        var missingCount: Int = 0,
        var lastOpened: Long = 0L
    )

    data class State(
        var entries: MutableList<FolderEntry> = mutableListOf()
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    /** Returns up to 5 recent entries, most-recently-opened first. */
    fun getRecentEntries(): List<FolderEntry> =
        myState.entries.sortedByDescending { it.lastOpened }.take(MAX_ENTRIES)

    /** Adds or updates an entry for [path] with the given stats. */
    fun recordFolder(path: String, totalKeys: Int, languageCount: Int, missingCount: Int) {
        myState.entries.removeAll { it.path == path }
        myState.entries.add(
            0,
            FolderEntry(
                path = path,
                totalKeys = totalKeys,
                languageCount = languageCount,
                missingCount = missingCount,
                lastOpened = System.currentTimeMillis()
            )
        )
        while (myState.entries.size > MAX_ENTRIES) {
            myState.entries.removeLastOrNull()
        }
    }

    companion object {
        const val MAX_ENTRIES = 5

        fun getInstance(project: Project): RecentFolderService =
            project.getService(RecentFolderService::class.java)
    }
}

