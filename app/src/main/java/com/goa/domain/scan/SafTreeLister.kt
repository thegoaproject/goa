package com.goa.domain.scan

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/**
 * Lists a Storage Access Framework tree with ONE query per directory, asking only for the five columns the scan needs.
 * DocumentFile.listFiles() does a query per file and attribute, which takes minutes on a folder of thousands of archives.
 */
class SafTreeLister(private val resolver: ContentResolver, private val treeUri: Uri) : TreeLister {

    override fun root() = DirRef(DocumentsContract.getTreeDocumentId(treeUri))

    override fun list(dir: DirRef): List<TreeEntry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dir.id)
        val out = ArrayList<TreeEntry>()
        val cursor = try {
            resolver.query(children, COLUMNS, null, null, null)
        } catch (e: Exception) {
            null            // a directory that vanished or is unreadable is skipped, not fatal to the whole scan
        } ?: return out
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                out += TreeEntry(
                    id = id,
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(),
                    name = name,
                    isDir = mime == Document.MIME_TYPE_DIR,
                    size = if (c.isNull(3)) 0L else c.getLong(3),
                    lastModified = if (c.isNull(4)) 0L else c.getLong(4)
                )
            }
        }
        return out
    }

    private companion object {
        val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
        )
    }
}
