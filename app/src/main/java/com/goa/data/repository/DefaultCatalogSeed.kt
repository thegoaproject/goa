package com.goa.data.repository

import com.goa.data.db.GameEntity
import com.goa.data.db.InstallStatus
import com.goa.data.db.InstallerChunkEntity

/** Artwork links are GOG store images (catalog.gog.com coverVertical / coverHorizontal), checked 10 Nov 2026. */
object DefaultCatalogSeed {

    val games: List<GameEntity> = listOf(
        GameEntity(
            id = 9000000006L,
            title = "Beneath a Steel Sky",
            slug = "beneath_a_steel_sky",
            boxArtUrl = "https://images.gog-statics.com/c544abe374da14b2dba4c394d1f5bbdb03ce977559b8c563b896ec088f63bf2f.jpg",
            bannerUrl = "https://images.gog-statics.com/8c9562087850f4f0bc5cbb073e2207e75cb016d7b1632800db3e36c8e85d79d2.png",
            description = "The critically acclaimed cyberpunk point-and-click adventure with comic book art by Dave Gibbons (Watchmen). Follow Robert Foster and his witty sentient robot Joey through dystopian Union City. Released as freeware by Revolution Software.",
            releaseYear = 1994,
            isDosNative = true,
            downloadSize = 75_000_000L,
            installStatus = InstallStatus.NOT_INSTALLED
        ),
        GameEntity(
            id = 9000000007L,
            title = "Flight of the Amazon Queen",
            slug = "flight_of_the_amazon_queen",
            boxArtUrl = "https://images.gog-statics.com/b0ca36c4583e27fd68b964055bfd9a72fbb8ef7c40253a3bb0aba94a93a822d2.jpg",
            bannerUrl = "https://images.gog-statics.com/23f2b3ea51d94d412516374660aa231561f5d25bd99947c5b49e8f255119a185.png",
            description = "A classic 1940s-style pulp adventure starring Joe King, pilot for hire, who crashes his airplane into the Amazon basin and must thwart a mad scientist. Released as freeware by John Passfield and Steve Stamatiadis.",
            releaseYear = 1995,
            isDosNative = true,
            downloadSize = 40_000_000L,
            installStatus = InstallStatus.NOT_INSTALLED
        ),
        GameEntity(
            id = 9000000009L,
            title = "One Must Fall: 2097",
            slug = "one_must_fall_2097",
            boxArtUrl = "", // not sold on GOG, so no store art; the library draws a title tile
            bannerUrl = null,
            description = "The premier PC tournament robot fighting game of the 90s. Build and upgrade human-assisted robots (HARs), challenge battle arenas with interactive hazards, and rock out to Kenny Chou's fast tracker soundtrack. Released as freeware.",
            releaseYear = 1994,
            isDosNative = true,
            downloadSize = 15_000_000L,
            installStatus = InstallStatus.NOT_INSTALLED
        ),
        GameEntity(
            id = 9000000013L,
            title = "Lure of the Temptress",
            slug = "lure_of_the_temptress",
            boxArtUrl = "https://images.gog-statics.com/c1a9c3b861df301b1248dd5017d80a8b510fd16e3d98f7f9db60d7efde857411.jpg",
            bannerUrl = "https://images.gog-statics.com/c142bc01845e073ea337341303cee07a8e9e980434cd1d148bf1f43e8634c734.png",
            description = "Revolution Software's first adventure. Diermot, a peasant swept up in a failed rebellion, must free the town of Turnvale from the sorceress Selena and her Skorl army. Its villagers go about their own lives as you play. Released as freeware by Revolution Software.",
            releaseYear = 1992,
            isDosNative = true,
            downloadSize = 12_000_000L,
            installStatus = InstallStatus.NOT_INSTALLED
        ),
        GameEntity(
            id = 9000000015L,
            title = "Xargon",
            slug = "xargon",
            boxArtUrl = "",
            bannerUrl = null,
            description = "A colourful action platformer from Epic MegaGames. Malvineous Havershim wakes up in a strange world and must fight his way through three episodes of monsters, traps and secrets to reach the evil Xargon. The full three-episode version, released as freeware by its author Allen Pilgrim.",
            releaseYear = 1994,
            isDosNative = true,
            downloadSize = 4_000_000L,
            installStatus = InstallStatus.NOT_INSTALLED
        )
    )

    // One chunk per mirrored archive: the R2 copy when a CDN base URL is configured, otherwise the original site
    val chunks: List<InstallerChunkEntity> = FreewareCdn.archives.map { (gameId, archive) ->
        InstallerChunkEntity(
            id = "seed_chunk_${gameId}_0",
            gameId = gameId,
            fileName = archive.fileName,
            downloadUrl = FreewareCdn.downloadUrl(archive),
            totalBytes = archive.sizeBytes,
            chunkIndex = 0
        )
    }
}
