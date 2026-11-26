package com.goa.domain.scan

/** Opaque handle of a directory in a tree (the document id for a SAF tree). */
data class DirRef(val id: String)

data class TreeEntry(
    /** Stable id of the entry inside its tree (the document id for SAF); used to build source keys. */
    val id: String,
    /** Uri that can be opened later, to install from it. */
    val uri: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val lastModified: Long
)

/** Lists one directory at a time. The SAF implementation is [SafTreeLister]; tests use an in-memory tree. */
interface TreeLister {
    fun root(): DirRef
    fun list(dir: DirRef): List<TreeEntry>
}
