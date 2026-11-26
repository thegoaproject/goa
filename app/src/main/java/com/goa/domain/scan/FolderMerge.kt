package com.goa.domain.scan

/**
 * A watched folder added around folders that were already watched: their games move to it instead of being deleted and
 * found again. Games keep their ids (play history, install, chosen version), installed games aren't shown twice, and
 * the bigger folder's scan finds them under their new keys and only refreshes them.
 *
 * A game's source key is "<folderId>|g:<groupKey>", and the group key comes from the game's title, not its path, so
 * the same game has the same key under the bigger folder.
 */
object FolderMerge {
    /** One game found in a folder: its id, source key and whether it's still just a candidate (not installed). */
    data class Row(val id: Long, val sourceKey: String, val notInstalled: Boolean)

    sealed interface Change {
        val id: Long
        /** Give the game the bigger folder's key and folder. */
        data class Rekey(override val id: Long, val sourceKey: String) : Change
        /** The same game was also in another of the merged folders and that one is kept: drop this candidate. */
        data class Delete(override val id: Long) : Change
        /** Another row already has the key, but this one is installed: only its folder changes. */
        data class MoveOnly(override val id: Long) : Change
    }

    /**
     * What to do with the [rows] of each folder in [from] (folder id to its rows) when [toFolderId] replaces them.
     * [takenKeys] are keys the bigger folder's games already hold. Installed games are never deleted.
     */
    fun plan(from: Map<String, List<Row>>, toFolderId: String, takenKeys: Set<String> = emptySet()): List<Change> {
        val taken = takenKeys.toMutableSet()
        // Installed games claim their key first, so a candidate never pushes one aside
        val rows = from.flatMap { (folderId, list) -> list.map { folderId to it } }
            .sortedWith(compareBy({ it.second.notInstalled }, { it.second.id }))
        return rows.map { (folderId, row) ->
            val key = rekey(row.sourceKey, folderId, toFolderId)
            when {
                key == null -> Change.MoveOnly(row.id)
                taken.add(key) -> Change.Rekey(row.id, key)
                row.notInstalled -> Change.Delete(row.id)
                else -> Change.MoveOnly(row.id)
            }
        }
    }

    /** "<from>|g:<group>" to "<to>|g:<group>"; null when the key isn't one of [fromFolderId]'s. */
    fun rekey(sourceKey: String, fromFolderId: String, toFolderId: String): String? {
        val prefix = "$fromFolderId|"
        return if (sourceKey.startsWith(prefix)) "$toFolderId|" + sourceKey.removePrefix(prefix) else null
    }
}
