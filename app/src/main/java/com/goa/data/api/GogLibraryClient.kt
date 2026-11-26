package com.goa.data.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun String?.toSecureGogImageUrl(): String? {
    if (this.isNullOrBlank()) return null
    var url = this.trim()
    if (url.startsWith("//")) {
        url = "https:$url"
    } else if (url.startsWith("/")) {
        url = "https://embed.gog.com$url"
    }
    val lower = url.lowercase()
    if (!lower.endsWith(".jpg") && !lower.endsWith(".jpeg") && !lower.endsWith(".png") && !lower.endsWith(".webp")) {
        url = "$url.jpg"
    }
    return url
}

@Serializable
data class GogOwnedGamesResponse(
    val owned: List<Long> = emptyList()
)

@Serializable
data class GogDownloadEntry(
    val id: String = "",
    val name: String = "",
    val size: String = "",
    val downlink: String = "",
    val version: String = ""
)

@Serializable
data class GogExtraEntry(
    val id: String = "",
    val name: String = "",
    val type: String = "",
    val size: String = "",
    val downlink: String = ""
)

@Serializable
data class GogGameDetailsRaw(
    val title: String = "",
    val backgroundImage: String? = null,
    val cdKey: String? = null,
    val downloads: JsonElement? = null,
    val extras: JsonElement? = null
)

data class ParsedGameDetails(
    val title: String,
    val backgroundImage: String?,
    val downloads: List<GogDownloadEntry>,
    val extras: List<GogExtraEntry>
)

@Serializable
data class GogV2ProductEmbedded(
    val id: Long? = null,
    val title: String? = null,
    val globalReleaseDate: String? = null
)

@Serializable
data class GogV2Embedded(
    val product: GogV2ProductEmbedded? = null,
    val tags: List<JsonObject> = emptyList(),
    val properties: List<JsonObject> = emptyList()
)

@Serializable
data class GogV2GameResponse(
    val isUsingDosBox: Boolean? = null,
    val technologies: List<JsonObject> = emptyList(),
    @SerialName("_embedded") val embedded: GogV2Embedded? = null
)

@Serializable
data class GogProductDetailsResponse(
    val id: Long? = null,
    val title: String? = null,
    val slug: String? = null,
    val description: JsonObject? = null,
    val images: JsonObject? = null,
    val tags: List<String> = emptyList(),
    val features: List<JsonObject> = emptyList(),
    val downloads: JsonElement? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("original_release_date") val originalReleaseDate: String? = null,
    @SerialName("global_release_date") val globalReleaseDate: String? = null
)

class GogLibraryClient(
    private val httpClient: HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }
) {
    companion object {
        const val BASE_EMBED_URL = "https://embed.gog.com"
        const val BASE_API_URL = "https://api.gog.com"
    }

    suspend fun fetchOwnedGames(accessToken: String): Result<List<Long>> = runCatching {
        val response = httpClient.get("$BASE_EMBED_URL/user/data/games") {
            header("Authorization", "Bearer $accessToken")
        }.body<GogOwnedGamesResponse>()
        response.owned
    }

    suspend fun fetchGameDetails(accessToken: String, gameId: Long): Result<ParsedGameDetails> = runCatching {
        val raw = httpClient.get("$BASE_EMBED_URL/account/gameDetails/$gameId.json") {
            header("Authorization", "Bearer $accessToken")
        }.body<GogGameDetailsRaw>()

        val downloads = mutableListOf<GogDownloadEntry>()

        // GOG format: downloads: [ [ "English", { "windows": [ { "id": "...", "name": "...", "manualUrl": "...", "downlink": "..." } ] } ] ]
        raw.downloads?.let { downloadsJson ->
            if (downloadsJson is kotlinx.serialization.json.JsonArray) {
                for (langItem in downloadsJson) {
                    if (langItem is kotlinx.serialization.json.JsonArray && langItem.size >= 2) {
                        val osMap = langItem[1].jsonObject
                        val windowsList = osMap["windows"]?.jsonArray
                        if (windowsList != null) {
                            for (chunk in windowsList) {
                                val obj = chunk.jsonObject
                                val rawDownlink = obj["manualUrl"]?.jsonPrimitive?.content
                                    ?: obj["downlink"]?.jsonPrimitive?.content
                                    ?: ""
                                val formattedDownlink = if (rawDownlink.startsWith("/")) {
                                    "$BASE_EMBED_URL$rawDownlink"
                                } else {
                                    rawDownlink
                                }
                                downloads.add(
                                    GogDownloadEntry(
                                        id = obj["id"]?.jsonPrimitive?.content ?: "",
                                        name = obj["name"]?.jsonPrimitive?.content ?: "",
                                        size = obj["size"]?.jsonPrimitive?.content ?: "",
                                        downlink = formattedDownlink,
                                        version = obj["version"]?.jsonPrimitive?.content ?: ""
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        val extras = mutableListOf<GogExtraEntry>()
        raw.extras?.let { extrasJson ->
            if (extrasJson is kotlinx.serialization.json.JsonArray) {
                for (item in extrasJson) {
                    val obj = item.jsonObject
                    val rawDownlink = obj["manualUrl"]?.jsonPrimitive?.content
                        ?: obj["downlink"]?.jsonPrimitive?.content
                        ?: ""
                    val formattedDownlink = if (rawDownlink.startsWith("/")) {
                        "$BASE_EMBED_URL$rawDownlink"
                    } else {
                        rawDownlink
                    }
                    extras.add(
                        GogExtraEntry(
                            id = obj["id"]?.jsonPrimitive?.content ?: "",
                            name = obj["name"]?.jsonPrimitive?.content ?: "",
                            type = obj["type"]?.jsonPrimitive?.content ?: "",
                            size = obj["size"]?.jsonPrimitive?.content ?: "",
                            downlink = formattedDownlink
                        )
                    )
                }
            }
        }

        ParsedGameDetails(
            title = raw.title,
            backgroundImage = raw.backgroundImage.toSecureGogImageUrl(),
            downloads = downloads,
            extras = extras
        )
    }

    suspend fun fetchProductDetails(accessToken: String? = null, gameId: Long): Result<GogProductDetailsResponse> = runCatching {
        httpClient.get("$BASE_API_URL/products/$gameId?expand=description,downloads") {
            if (!accessToken.isNullOrBlank()) {
                header("Authorization", "Bearer $accessToken")
            }
        }.body()
    }

    suspend fun fetchV2GameDetails(gameId: Long): Result<GogV2GameResponse> = runCatching {
        httpClient.get("$BASE_API_URL/v2/games/$gameId").body()
    }

    suspend fun resolveDownlink(accessToken: String, downlinkUrl: String): Result<String> = runCatching {
        val response: HttpResponse = httpClient.get(downlinkUrl) {
            header("Authorization", "Bearer $accessToken")
        }
        if (response.status == HttpStatusCode.Found ||
            response.status == HttpStatusCode.MovedPermanently ||
            response.status == HttpStatusCode.SeeOther ||
            response.status == HttpStatusCode.TemporaryRedirect
        ) {
            response.headers["Location"] ?: downlinkUrl
        } else if (response.status == HttpStatusCode.OK) {
            val text = response.bodyAsText()
            try {
                val json = Json.parseToJsonElement(text).jsonObject
                json["downlink"]?.jsonPrimitive?.content ?: downlinkUrl
            } catch (e: Exception) {
                downlinkUrl
            }
        } else {
            downlinkUrl
        }
    }
}


