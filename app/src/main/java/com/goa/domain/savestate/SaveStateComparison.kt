package com.goa.domain.savestate

/**
 * How a game's save state on the phone compares with its copy in Google Drive, and what export and import do about
 * it (specs/009-drive-save-states/data-model.md, "Comparison" and "ConflictChoice"). Pure: no files, no network.
 */
object SaveStateComparison {

    /** One side of the comparison: which bytes, and when they were saved. */
    data class Side(val sha256: String, val savedAt: Long)

    enum class Status { ONLY_PHONE, ONLY_DRIVE, SAME, PHONE_NEWER, DRIVE_NEWER, NOT_INSTALLED, AMBIGUOUS }

    /**
     * [installed] is false when the game key matches no install on this phone, [ambiguous] when it matches several.
     * Equal save times with different bytes count as the phone's being newer: the phone's copy is the one in hand.
     */
    fun status(phone: Side?, drive: Side?, installed: Boolean = true, ambiguous: Boolean = false): Status? = when {
        drive != null && !installed -> Status.NOT_INSTALLED
        // Which install's save state to compare with is the player's choice; compare again once they pick
        drive != null && ambiguous -> Status.AMBIGUOUS
        phone == null && drive == null -> null
        drive == null -> Status.ONLY_PHONE
        phone == null -> Status.ONLY_DRIVE
        phone.sha256 == drive.sha256 -> Status.SAME
        phone.savedAt >= drive.savedAt -> Status.PHONE_NEWER
        else -> Status.DRIVE_NEWER
    }

    enum class ExportAction { UPLOAD, SKIP, ASK }

    /** What export does for a game with this status (FR-009, FR-010); null when there is nothing on the phone. */
    fun exportAction(status: Status): ExportAction? = when (status) {
        Status.ONLY_PHONE, Status.PHONE_NEWER -> ExportAction.UPLOAD
        Status.SAME -> ExportAction.SKIP
        Status.DRIVE_NEWER -> ExportAction.ASK
        Status.ONLY_DRIVE, Status.NOT_INSTALLED, Status.AMBIGUOUS -> null
    }

    /** After a choice on export: KEEP_PHONE uploads (replacing Drive's); USE_DRIVE and SKIP leave both. */
    fun exportAfterChoice(choice: ConflictChoice): ExportAction =
        if (choice == ConflictChoice.KEEP_PHONE) ExportAction.UPLOAD else ExportAction.SKIP

    enum class ImportAction { DOWNLOAD, NOTHING, ASK, ASK_INSTALL, REPORT_NOT_INSTALLED }

    /** What import does for a game with this status (FR-011..FR-015). */
    fun importAction(status: Status): ImportAction = when (status) {
        Status.ONLY_DRIVE -> ImportAction.DOWNLOAD
        Status.SAME, Status.ONLY_PHONE -> ImportAction.NOTHING
        Status.PHONE_NEWER, Status.DRIVE_NEWER -> ImportAction.ASK
        Status.AMBIGUOUS -> ImportAction.ASK_INSTALL
        Status.NOT_INSTALLED -> ImportAction.REPORT_NOT_INSTALLED
    }

    /** After a choice on import or download: only USE_DRIVE changes the phone; nothing ever changes Drive. */
    fun importAfterChoice(choice: ConflictChoice): ImportAction =
        if (choice == ConflictChoice.USE_DRIVE) ImportAction.DOWNLOAD else ImportAction.NOTHING

    /** The choice "Keep the newer" makes, and the card the conflict screen pre-selects. */
    fun newer(phone: Side, drive: Side): ConflictChoice =
        if (drive.savedAt > phone.savedAt) ConflictChoice.USE_DRIVE else ConflictChoice.KEEP_PHONE
}

enum class ConflictChoice { KEEP_PHONE, USE_DRIVE, SKIP }

/** A game whose phone and Drive save states both exist and differ. */
data class Conflict<K>(val key: K, val phone: SaveStateComparison.Side, val drive: SaveStateComparison.Side)

/**
 * The conflicts of one run, asked one at a time ("1 of 3"), with "Do this for the remaining N" and "Keep the newer
 * for all" (FR-013).
 */
class ConflictQueue<K>(private val conflicts: List<Conflict<K>>) {
    private val decided = LinkedHashMap<K, ConflictChoice>()

    val total: Int get() = conflicts.size
    val done: Boolean get() = decided.size >= conflicts.size
    /** The conflict being asked about, or null when all are settled. */
    val current: Conflict<K>? get() = conflicts.getOrNull(decided.size)
    /** 1-based position of [current], for "1 of 3". */
    val position: Int get() = decided.size + 1
    /** Conflicts after [current]: what "Do this for the remaining N" covers. */
    val remainingAfterCurrent: Int get() = (conflicts.size - decided.size - 1).coerceAtLeast(0)
    val choices: Map<K, ConflictChoice> get() = decided

    fun answer(choice: ConflictChoice, applyToRemaining: Boolean = false) {
        val now = current ?: return
        decided[now.key] = choice
        if (applyToRemaining) while (!done) decided[current!!.key] = choice
    }

    /** Settles this and every remaining conflict by the later save time. */
    fun keepNewerForAll() {
        while (!done) {
            val c = current!!
            decided[c.key] = SaveStateComparison.newer(c.phone, c.drive)
        }
    }
}
