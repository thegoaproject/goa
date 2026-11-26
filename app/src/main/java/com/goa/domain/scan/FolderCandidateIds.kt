package com.goa.domain.scan

/**
 * Stable ids for games found in a scanned folder, inside the "own files" range (see [GameSource]).
 *
 * The id comes from a hash of the candidate's source key, so scanning the same folder twice gives the same ids and
 * nothing is duplicated. Two different keys that land on one id are separated by probing to the next free id.
 */
object FolderCandidateIds {
    private const val BASE = GameSource.MY_FOLDERS_START
    private const val SPAN = GameSource.MY_FOLDERS_END - GameSource.MY_FOLDERS_START

    /** 64-bit FNV-1a over the UTF-8 bytes of [key]. */
    fun fnv1a64(key: String): Long {
        var h = -3750763034362895579L          // 0xcbf29ce484222325
        for (b in key.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xff)
            h *= 1099511628211L                // 0x100000001b3
        }
        return h
    }

    /** The id a key would get if nothing else were in the way. */
    fun preferred(key: String): Long = BASE + Math.floorMod(fnv1a64(key), SPAN)

    /** The id after [id], wrapping from the end of the range back to its start. */
    internal fun next(id: Long): Long = BASE + (id - BASE + 1) % SPAN

    /**
     * The id for [key]: [existingId] when the game is already stored (ids never change), else the preferred id,
     * probing forward (wrapping inside the range) while [isTaken] says another game holds it.
     */
    suspend fun allocate(key: String, existingId: Long?, isTaken: suspend (Long) -> Boolean): Long {
        if (existingId != null) return existingId
        var id = preferred(key)
        var attempts = 0L
        while (isTaken(id)) {
            id = next(id)
            check(++attempts < SPAN) { "No free candidate id left" }
        }
        return id
    }
}
