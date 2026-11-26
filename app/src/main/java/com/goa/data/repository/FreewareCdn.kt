package com.goa.data.repository

import com.goa.BuildConfig

/**
 * Freeware archives mirrored in the "goa" R2 bucket. Each is the original archive byte-for-byte (several licences
 * require files unmodified), so the hash pins both the R2 copy and the original source it falls back to.
 * Bucket layout and licence notes live in the bucket's manifest.json.
 */
object FreewareCdn {

    data class Archive(val key: String, val sourceUrl: String, val sizeBytes: Long, val sha256: String) {
        val fileName: String get() = key.substringAfterLast('/')
    }

    val archives: Map<Long, Archive> = mapOf(
        9000000006L to Archive(
            key = "games/beneath_a_steel_sky/bass-cd-1.2.zip",
            sourceUrl = "https://downloads.scummvm.org/frs/extras/Beneath%20a%20Steel%20Sky/bass-cd-1.2.zip",
            sizeBytes = 69_377_781L,
            sha256 = "53209b9400eab6fd7fa71518b2f357c8de75cfeaa5ba57024575ab79cc974593"
        ),
        9000000007L to Archive(
            key = "games/flight_of_the_amazon_queen/FOTAQ_Floppy.zip",
            sourceUrl = "https://downloads.scummvm.org/frs/extras/Flight%20of%20the%20Amazon%20Queen/FOTAQ_Floppy.zip",
            sizeBytes = 7_109_570L,
            sha256 = "2e59de85f708cdb32bf85c85b394ac091c05f7647e856b71f5b3ae73fde761e0"
        ),
        9000000009L to Archive(
            key = "games/one_must_fall_2097/omf21cd.zip",
            sourceUrl = "https://www.omf2097.com/pub/files/omf/omf21cd.zip",
            sizeBytes = 6_844_108L,
            sha256 = "567bee1b6a71477da6777a8dbdd32bd78833bcdd88242e3df0579c38db889dff"
        ),
        9000000013L to Archive(
            key = "games/lure_of_the_temptress/lure-1.1.zip",
            sourceUrl = "https://downloads.scummvm.org/frs/extras/Lure%20of%20the%20Temptress/lure-1.1.zip",
            sizeBytes = 5_678_861L,
            sha256 = "f3178245a1483da1168c3a11e70b65d33c389f1f5df63d4f3a356886c1890108"
        ),
        9000000015L to Archive(
            key = "games/xargon/Xargon_Commercial.zip",
            sourceUrl = "https://www.classicdosgames.com/files/games/epic/Xargon_Commercial.zip",
            sizeBytes = 1_389_169L,
            sha256 = "16211df28b6b12822d6fc46e3f915ad0e55de07c656f72160e344f32a37f9c18"
        )
    )

    /**
     * Seed games dropped from the catalog. No licence lets GOA redistribute GTA 1, London 1969, GTA 2, Arena or Daggerfall;
     * the Commander Keen, DOOM and Wolfenstein 3D shareware terms don't cover a commercial app on Google Play and their
     * owner still sells them (removed 11 Nov 2026); Tyrian 2.1 never ran properly. Ken's Labyrinth ("not sold for profit in
     * any way") and God of Thunder (only an informal freeware note) were dropped on 12 Nov 2026 as too uncertain.
     */
    val removedGameIds: List<Long> = listOf(
        9000000001L, 9000000002L, 9000000003L, 9000000004L, 9000000005L,
        9000000008L, 9000000010L, 9000000011L, 9000000012L, 9000000014L, 9000000016L
    )

    private val baseUrl: String = BuildConfig.FREEWARE_CDN_BASE_URL.trimEnd('/')
    private val clientKey: String = BuildConfig.FREEWARE_CDN_CLIENT_KEY

    // The CDN Worker rejects requests without the client header, so a build lacking the key uses the original sites
    private val cdnEnabled: Boolean = baseUrl.isNotEmpty() && clientKey.isNotEmpty()

    fun downloadUrl(archive: Archive): String = if (cdnEnabled) "$baseUrl/${archive.key}" else archive.sourceUrl

    /** Headers to send with a download request: the client header for CDN URLs, nothing for anything else. */
    fun requestHeaders(url: String): Map<String, String> =
        if (cdnEnabled && url.startsWith("$baseUrl/")) mapOf("X-GOA-Client" to clientKey) else emptyMap()

    /** Expected SHA-256 for a downloaded file, matched by name; null for anything not mirrored (GOG installers). */
    fun expectedSha256(fileName: String): String? =
        archives.values.firstOrNull { it.fileName.equals(fileName, ignoreCase = true) }?.sha256
}
