package com.goa.data.folders

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** A folder the player added. [treeUri] is the persisted Storage Access Framework tree Uri. */
data class AddedFolder(
    val id: String,
    val treeUri: String,
    val name: String,
    val lastScanMs: Long = 0L,
    val lastCount: Int = 0,
    /** The system dropped our access (the player revoked it, or the storage went away): the folder needs picking again. */
    val needsRegrant: Boolean = false
)

/** Minimal key-value storage so the folder list is testable without Android (SharedPreferences backs it in the app). */
interface TextStore {
    fun read(): String?
    fun write(value: String)
}

/** The added folders, kept as one JSON array. */
class FolderStore(private val storage: TextStore) {

    fun list(): List<AddedFolder> = runCatching {
        val arr = JSONArray(storage.read() ?: return emptyList())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            AddedFolder(
                id = o.getString("id"), treeUri = o.getString("treeUri"), name = o.optString("name", "Folder"),
                lastScanMs = o.optLong("lastScanMs", 0L), lastCount = o.optInt("lastCount", 0), needsRegrant = o.optBoolean("needsRegrant", false)
            )
        }
    }.getOrDefault(emptyList())

    fun save(folders: List<AddedFolder>) {
        storage.write(JSONArray().apply {
            folders.forEach { f ->
                put(JSONObject().put("id", f.id).put("treeUri", f.treeUri).put("name", f.name)
                    .put("lastScanMs", f.lastScanMs).put("lastCount", f.lastCount).put("needsRegrant", f.needsRegrant))
            }
        }.toString())
    }

    fun update(id: String, change: (AddedFolder) -> AddedFolder) = save(list().map { if (it.id == id) change(it) else it })

    companion object {
        /** Short stable id of a tree Uri. */
        fun idFor(treeUri: String): String =
            MessageDigest.getInstance("SHA-1").digest(treeUri.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

        /** The outcome of trying to add [candidate] to [existing]: nothing to do, replace some, or add. */
        sealed interface AddDecision {
            data class Duplicate(val of: AddedFolder) : AddDecision
            /** Already covered by a larger folder that was added earlier. */
            data class InsideExisting(val parent: AddedFolder) : AddDecision
            /** Adding it makes these narrower folders redundant. */
            data class ReplacesNested(val nested: List<AddedFolder>) : AddDecision
            data object Add : AddDecision
        }

        fun decide(existing: List<AddedFolder>, candidateTreeUri: String): AddDecision {
            existing.firstOrNull { it.treeUri == candidateTreeUri }?.let { return AddDecision.Duplicate(it) }
            val candidate = TreePath.of(candidateTreeUri)
            existing.firstOrNull { TreePath.of(it.treeUri).contains(candidate) }?.let { return AddDecision.InsideExisting(it) }
            val nested = existing.filter { candidate.contains(TreePath.of(it.treeUri)) }
            return if (nested.isEmpty()) AddDecision.Add else AddDecision.ReplacesNested(nested)
        }
    }
}

/** The authority and document id of a SAF tree Uri ("content://<authority>/tree/<docId>"), for nesting checks. */
internal data class TreePath(val authority: String, val docId: String) {
    /** True if [other] is the same folder or lies inside this one (same storage provider, id continues with a slash). */
    fun contains(other: TreePath): Boolean =
        authority == other.authority && (docId == other.docId || other.docId.startsWith(docId.trimEnd('/') + "/"))

    companion object {
        fun of(treeUri: String): TreePath {
            val rest = treeUri.substringAfter("content://", "")
            val authority = rest.substringBefore('/')
            val docId = java.net.URLDecoder.decode(rest.substringAfter("/tree/", ""), "UTF-8")
            return TreePath(authority, docId.substringBefore("/document/"))
        }
    }
}
