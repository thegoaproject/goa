package com.goa.domain.scan

import com.goa.data.db.SourceKind
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

/** One archive, installer or DOS game folder found by a scan. */
data class ScanCandidate(
    val sourceKey: String,        // "<folderId>|<entry id>"
    val uri: String,
    val kind: String,             // SourceKind.*
    val displayName: String,
    val size: Long,
    val lastModified: Long,
    val parsed: FilenameTitleParser.Parsed
)

data class ScanProgress(val dirsVisited: Int, val candidatesFound: Int)

/** One game as the library shows it: the best file plus every other version found for the same title. */
data class ScannedGame(
    val groupKey: String,
    val title: String,
    val year: Int?,
    val slug: String,
    val primary: ScanCandidate,
    val variants: List<ScanCandidate>   // includes the primary
)

data class ScanResult(val games: List<ScannedGame>, val dirsVisited: Int, val candidateFiles: Int)

/**
 * Finds install candidates in a tree: .7z/.zip archives, .exe installers, and DOS game folders (which are taken whole
 * and not searched further). Works on any [TreeLister], so the rules are testable without a device.
 */
class FolderScanner(
    private val lister: TreeLister,
    private val folderId: String,
    private val maxDepth: Int = 12,
    private val clock: () -> Long = System::nanoTime
) {
    suspend fun scan(onProgress: (ScanProgress) -> Unit = {}): ScanResult {
        val found = ArrayList<ScanCandidate>()
        val visited = HashSet<String>()
        val stack = ArrayDeque<Pair<DirRef, Int>>()
        stack.addLast(lister.root() to 0)
        var dirs = 0
        var lastReport = 0L

        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()              // a removed folder or a new scan cancels this one
            val (dir, depth) = stack.removeLast()
            if (!visited.add(dir.id)) continue
            val entries = lister.list(dir)
            dirs++

            val verdict = classifyDirectory(entries)
            if (verdict.isGameFolder && depth > 0) {
                // The folder itself is the candidate; its archives and subfolders belong to the game, not to the scan.
                // (The scan root is never a game: picking it means "look inside".)
                val self = dirEntryFor(dir) ?: continue
                found += candidate(self.id, self.uri, SourceKind.FOLDER, self.name, folderSize(dir), self.lastModified, folder = true)
            } else {
                val parent = dirNames[dir.id]
                for (e in verdict.files) {
                    // "SETUP.EXE" says nothing about the game: name it after the folder it sits in
                    val generic = e.kind == SourceKind.INSTALLER && GENERIC_INSTALLER.matches(e.entry.name.substringBeforeLast('.'))
                    // An installer with its .bin parts is one install: the uris are kept together, exe first
                    found += candidate(
                        e.entry.id, (listOf(e.entry.uri) + e.partUris).joinToString(INSTALLER_URI_SEPARATOR), e.kind, e.entry.name,
                        e.entry.size + e.partsSize, e.entry.lastModified, folder = false,
                        parseName = if (generic && parent != null) parent.name else null
                    )
                }
                if (depth < maxDepth) for (d in verdict.subdirs) {
                    currentCoroutineContext().ensureActive()
                    dirNames[d.id] = d
                    stack.addLast(DirRef(d.id) to depth + 1)
                }
            }

            val now = clock()
            if (now - lastReport > REPORT_EVERY_NS) {
                lastReport = now
                onProgress(ScanProgress(dirs, found.size))
            }
        }
        onProgress(ScanProgress(dirs, found.size))
        return ScanResult(GameGrouper.group(found), dirs, found.size)
    }

    // Subdirectory entries seen so far, so a directory that turns out to be a game can be named without a second query
    private val dirNames = HashMap<String, TreeEntry>()
    private fun dirEntryFor(dir: DirRef): TreeEntry? = dirNames[dir.id]

    private fun candidate(id: String, uri: String, kind: String, name: String, size: Long, modified: Long, folder: Boolean, parseName: String? = null) =
        ScanCandidate(
            "$folderId|$id", uri, kind, name, size, modified,
            if (parseName != null) FilenameTitleParser.parse(parseName, stripExtension = false)
            else FilenameTitleParser.parse(name, stripExtension = !folder)
        )

    /** Total size of the files under [dir], for the Install button and the space check (bounded so a huge tree can't stall). */
    private suspend fun folderSize(dir: DirRef): Long {
        var total = 0L
        var budget = MAX_SIZE_ENTRIES
        val stack = ArrayDeque<DirRef>()
        stack.addLast(dir)
        val seen = HashSet<String>()
        while (stack.isNotEmpty() && budget > 0) {
            currentCoroutineContext().ensureActive()
            val d = stack.removeLast()
            if (!seen.add(d.id)) continue
            for (e in lister.list(d)) {
                budget--
                if (e.isDir) stack.addLast(DirRef(e.id)) else total += e.size
            }
        }
        return total
    }

    private companion object {
        val GENERIC_INSTALLER = Regex("""(?i)(setup|install|installer|inst)\d*""")
        const val REPORT_EVERY_NS = 100_000_000L     // about ten progress updates per second
        const val MAX_SIZE_ENTRIES = 50_000
    }
}

/** What a directory listing means for the scan. */
data class DirectoryVerdict(
    val isGameFolder: Boolean,
    val files: List<FileCandidate>,
    val subdirs: List<TreeEntry>
)

/** [partUris]/[partsSize]: the .bin parts of a GOG-style installer (setup_x.exe + setup_x-1.bin...), which install together. */
data class FileCandidate(val entry: TreeEntry, val kind: String, val partUris: List<String> = emptyList(), val partsSize: Long = 0L)

/** Separates the uris of an installer and its parts inside one candidate's source uri. */
const val INSTALLER_URI_SEPARATOR = "\n"

private val ARCHIVE_EXT = setOf("7z", "zip")
private val DOS_EXE_EXT = setOf("exe", "com", "bat")
private val DOC_EXT = setOf("txt", "nfo", "pdf", "url", "diz", "htm", "html", "doc", "rtf", "md", "lnk", "jpg", "jpeg", "png", "gif", "bmp", "ico", "db")
private val INSTALLER_NAME = Regex("""(?i)(^|[_\-. ])(setup|install|installer|inst)([_\-. ]|\d|$)""")
private val IGNORED_DIRS = setOf("\$recycle.bin", "system volume information", "android", "lost.dir")

private fun ext(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

fun classifyDirectory(entries: List<TreeEntry>): DirectoryVerdict {
    val files = entries.filter { !it.isDir && it.size > 0 }
    val subdirs = entries.filter { it.isDir && !it.name.startsWith(".") && it.name.lowercase(Locale.ROOT) !in IGNORED_DIRS }

    val exes = files.filter { ext(it.name) in DOS_EXE_EXT }
    val installerExes = exes.filter { ext(it.name) == "exe" && INSTALLER_NAME.containsMatchIn(it.name.substringBeforeLast('.')) }
    val gameExes = exes - installerExes.toSet()

    // GOG-style installers keep their data in "<setup name>-1.bin", "-2.bin"...: those belong to the installer
    val partsOf = installerExes.associateWith { exe ->
        val stem = exe.name.substringBeforeLast('.').lowercase(Locale.ROOT) + "-"
        files.filter { ext(it.name) == "bin" && it.name.lowercase(Locale.ROOT).startsWith(stem) }
    }
    val partNames = partsOf.values.flatten().toSet()
    val dataFiles = files.filter { ext(it.name) !in DOS_EXE_EXT && ext(it.name) !in DOC_EXT && ext(it.name) !in ARCHIVE_EXT && it !in partNames }

    // A DOS game folder has something to run (a game exe, or an installer that unpacks the game, like Apogee's INSTALL.EXE)
    // and its data beside it. A folder of archives, one lone setup.exe, or a GOG installer with its parts is not one.
    val isGameFolder = (gameExes.isNotEmpty() || installerExes.isNotEmpty()) && dataFiles.isNotEmpty()

    val candidates = ArrayList<FileCandidate>()
    for (f in files) when {
        ext(f.name) in ARCHIVE_EXT -> candidates += FileCandidate(f, SourceKind.ARCHIVE)
        f in installerExes -> {
            val parts = partsOf.getValue(f)
            candidates += FileCandidate(f, SourceKind.INSTALLER, parts.map { it.uri }, parts.sumOf { it.size })
        }
    }
    return DirectoryVerdict(isGameFolder, candidates, subdirs)
}

/** Groups candidates that are the same game and picks the best one to install. */
object GameGrouper {
    fun group(candidates: List<ScanCandidate>): List<ScannedGame> =
        candidates.groupBy { it.parsed.groupKey }
            .filterKeys { it.isNotBlank() }
            .map { (key, list) ->
                val ranked = list.sortedWith(RANK)
                val primary = ranked.first()
                ScannedGame(
                    groupKey = key,
                    title = primary.parsed.title,
                    year = primary.parsed.year ?: ranked.firstNotNullOfOrNull { it.parsed.year },
                    slug = primary.parsed.slug,
                    primary = primary,
                    variants = ranked
                )
            }
            .sortedBy { it.title.lowercase(Locale.ROOT) }

    /** Best first: ready-to-run beats installer, an unrevised dump beats a revision, then larger, newer, and name for stability. */
    private val RANK = compareBy<ScanCandidate>(
        { if (it.kind == SourceKind.INSTALLER) 1 else 0 },
        { if (it.parsed.isRev) 1 else 0 },
        { -it.size },
        { -it.lastModified },
        { it.displayName }
    )
}
