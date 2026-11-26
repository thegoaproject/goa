package com.goa.data.drive

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** A file or folder in Drive, with the fields GOA asks for. */
@Serializable
data class DriveFile(
    val id: String,
    val name: String = "",
    val mimeType: String? = null,
    val parents: List<String> = emptyList(),
    val appProperties: Map<String, String> = emptyMap(),
    val size: String? = null,
    val modifiedTime: String? = null,
    val createdTime: String? = null,
    val sha256Checksum: String? = null
)

@Serializable
private data class DriveFileList(val nextPageToken: String? = null, val files: List<DriveFile> = emptyList())

/**
 * Google Drive v3 over REST (research R3), only the calls in contracts/drive-layout.md. Every call carries a Bearer
 * token from [token]; `token(true)` must return a fresh one (the old one was refused). Failures surface as
 * [DriveException] with the player-facing [DriveError].
 */
class DriveClient(
    private val http: HttpClient,
    private val token: suspend (refresh: Boolean) -> String,
    private val pause: suspend (Long) -> Unit = { delay(it) }
) {
    /** Every file and folder GOA created that is not in the bin, all pages. */
    suspend fun listGoaItems(): List<DriveFile> {
        val kinds = listOf(DriveLayout.KIND_ROOT, DriveLayout.KIND_GAME, DriveLayout.KIND_SAVE_STATE, DriveLayout.KIND_NOTE)
        val query = kinds.joinToString(" or ", prefix = "(", postfix = ")") {
            "appProperties has { key='${DriveLayout.KIND}' and value='$it' }"
        } + " and trashed = false"
        val all = mutableListOf<DriveFile>()
        var page: String? = null
        do {
            val list = call<DriveFileList>(HttpMethod.Get, "$API/files") {
                parameter("q", query)
                parameter("spaces", "drive")
                parameter("fields", "nextPageToken,files($FILE_FIELDS)")
                parameter("pageSize", 1000)
                page?.let { parameter("pageToken", it) }
            }
            all += list.files
            page = list.nextPageToken
        } while (page != null)
        return all
    }

    suspend fun createFolder(name: String, parent: String?, appProperties: Map<String, String>): DriveFile =
        call(HttpMethod.Post, "$API/files") {
            parameter("fields", FILE_FIELDS)
            jsonBody(metadata(name, DriveLayout.FOLDER_MIME, parent, appProperties))
        }

    /** Moves a file or folder from [oldParent] into [newParent]. */
    suspend fun move(fileId: String, newParent: String, oldParent: String): DriveFile =
        call(HttpMethod.Patch, "$API/files/$fileId") {
            parameter("addParents", newParent)
            parameter("removeParents", oldParent)
            parameter("fields", FILE_FIELDS)
            jsonBody(buildJsonObject {})
        }

    /** Uploads [source] as a new file in [parent]. */
    suspend fun uploadNew(
        name: String, mime: String, parent: String, appProperties: Map<String, String>, source: UploadSource
    ): DriveFile {
        val session = startUpload(HttpMethod.Post, "$UPLOAD/files", mime, source.length) {
            jsonBody(metadata(name, mime, parent, appProperties))
        }
        return sendBytes(session, mime, source)
    }

    /** Replaces the content and properties of file [fileId] (a new revision; the id stays). */
    suspend fun uploadReplace(
        fileId: String, mime: String, appProperties: Map<String, String>, source: UploadSource
    ): DriveFile {
        val session = startUpload(HttpMethod.Patch, "$UPLOAD/files/$fileId", mime, source.length) {
            jsonBody(buildJsonObject { put("appProperties", properties(appProperties)) })
        }
        return sendBytes(session, mime, source)
    }

    /** Streams file [fileId]'s content into [to]. */
    suspend fun download(fileId: String, to: File) {
        withRetries { refresh ->
            val bearer = token(refresh)
            http.prepareRequest {
                method = HttpMethod.Get
                url("$API/files/$fileId")
                parameter("alt", "media")
                header("Authorization", "Bearer $bearer")
            }.execute { response ->
                if (!response.status.isSuccess()) return@execute failure(response)
                val channel = response.bodyAsChannel()
                to.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = channel.readAvailable(buffer, 0, buffer.size)
                        if (n < 0) break
                        if (n > 0) out.write(buffer, 0, n)
                    }
                }
                Outcome.Done(Unit)
            }
        }
    }

    // ---- Resumable upload ----

    private suspend fun startUpload(
        method: HttpMethod, url: String, mime: String, length: Long, configure: HttpRequestBuilder.() -> Unit
    ): String = withRetries { refresh ->
        val response = http.request {
            this.method = method
            url(url)
            parameter("uploadType", "resumable")
            parameter("fields", FILE_FIELDS)
            header("Authorization", "Bearer ${token(refresh)}")
            header("X-Upload-Content-Type", mime)
            header("X-Upload-Content-Length", length.toString())
            configure(this)
        }
        if (!response.status.isSuccess()) failure(response)
        else response.headers["Location"]?.let { Outcome.Done(it) } ?: Outcome.Fail(DriveError.Other("no upload address"))
    }

    /**
     * Sends the bytes to an upload session. A dropped connection asks the session how much arrived
     * (`Content-Range: bytes * /size`) and sends the rest, up to [TRIES] times. An upload that never finishes leaves
     * the Drive file as it was.
     */
    private suspend fun sendBytes(session: String, mime: String, source: UploadSource): DriveFile {
        var offset = 0L
        var drops = 0
        while (true) {
            try {
                val response = http.request {
                    method = HttpMethod.Put
                    url(session)
                    if (offset > 0) header("Content-Range", "bytes $offset-${source.length - 1}/${source.length}")
                    setBody(source.body(offset, ContentType.parse(mime)))
                }
                when {
                    response.status.isSuccess() -> return decode(response.bodyAsText())
                    response.status.value == 308 -> offset = nextOffset(response)
                    response.status.value == 404 || response.status.value == 410 ->
                        throw DriveException(DriveError.NotResponding) // the session expired
                    else -> when (val outcome = failure<DriveFile>(response)) {
                        is Outcome.Fail -> throw DriveException(outcome.error)
                        else -> {
                            if (++drops >= TRIES) throw DriveException(DriveError.NotResponding)
                            pause(backoff(drops))
                            offset = askOffset(session, source.length)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (++drops >= TRIES) throw DriveException(DriveError.NoConnection, e)
                pause(backoff(drops))
                offset = runCatching { askOffset(session, source.length) }.getOrElse { offset }
            }
        }
    }

    private suspend fun askOffset(session: String, length: Long): Long {
        val response = http.request {
            method = HttpMethod.Put
            url(session)
            header("Content-Range", "bytes */$length")
            setBody(ByteArrayContent(ByteArray(0), null))
        }
        return if (response.status.value == 308) nextOffset(response) else 0L
    }

    /** `Range: bytes=0-N` means N+1 bytes arrived; no Range header means none did. */
    private fun nextOffset(response: HttpResponse): Long =
        response.headers["Range"]?.substringAfterLast('-')?.toLongOrNull()?.plus(1) ?: 0L

    // ---- Requests and errors ----

    private sealed interface Outcome<out T> {
        data class Done<T>(val value: T) : Outcome<T>
        data class Fail(val error: DriveError) : Outcome<Nothing>
        /** Try again after a pause (rate limit, server error). */
        data object Retry : Outcome<Nothing>
        /** The token was refused: try once more with a fresh one. */
        data object Refresh : Outcome<Nothing>
    }

    private suspend inline fun <reified T> call(
        method: HttpMethod, url: String, crossinline configure: HttpRequestBuilder.() -> Unit
    ): T = withRetries { refresh ->
        val response = http.request {
            this.method = method
            url(url)
            header("Authorization", "Bearer ${token(refresh)}")
            configure(this)
        }
        if (response.status.isSuccess()) Outcome.Done(decode<T>(response.bodyAsText())) else failure(response)
    }

    /** Runs [attempt] with the error table's retries: one token refresh on 401, [TRIES] tries with backoff. */
    private suspend fun <T> withRetries(attempt: suspend (refresh: Boolean) -> Outcome<T>): T {
        var refreshed = false
        var tries = 0
        var refresh = false
        while (true) {
            val outcome = try {
                attempt(refresh)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveException) {
                throw e
            } catch (e: IOException) {
                throw DriveException(DriveError.NoConnection, e)
            }
            refresh = false
            when (outcome) {
                is Outcome.Done -> return outcome.value
                is Outcome.Fail -> throw DriveException(outcome.error)
                Outcome.Refresh -> {
                    if (refreshed) throw DriveException(DriveError.AccessRemoved)
                    refreshed = true
                    refresh = true
                }
                Outcome.Retry -> {
                    if (++tries >= TRIES) throw DriveException(DriveError.NotResponding)
                    pause(backoff(tries))
                }
            }
        }
    }

    private suspend fun <T> failure(response: HttpResponse): Outcome<T> {
        val code = response.status.value
        val reason = runCatching {
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]?.jsonObject
                ?.get("errors")?.jsonArray?.firstOrNull()?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return when {
            code == 401 -> Outcome.Refresh
            code == 403 && reason == "storageQuotaExceeded" -> Outcome.Fail(DriveError.DriveFull)
            code == 403 && (reason == "rateLimitExceeded" || reason == "userRateLimitExceeded") -> Outcome.Retry
            code == 429 || code >= 500 -> Outcome.Retry
            code == 404 -> Outcome.Fail(DriveError.NotFound)
            code == 403 -> Outcome.Fail(DriveError.AccessRemoved)
            else -> Outcome.Fail(DriveError.Other("error $code"))
        }
    }

    private fun HttpStatusCode.isSuccess() = value in 200..299

    private fun HttpRequestBuilder.jsonBody(json: JsonObject) =
        setBody(TextContent(json.toString(), ContentType.Application.Json))

    private fun metadata(name: String, mime: String, parent: String?, appProperties: Map<String, String>) =
        buildJsonObject {
            put("name", name)
            put("mimeType", mime)
            if (parent != null) put("parents", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(parent))))
            put("appProperties", properties(appProperties))
        }

    private fun properties(map: Map<String, String>) = JsonObject(map.mapValues { JsonPrimitive(it.value) })

    private inline fun <reified T> decode(text: String): T = JSON.decodeFromString(text)

    companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val FILE_FIELDS = "id,name,mimeType,parents,appProperties,size,modifiedTime,createdTime,sha256Checksum"
        const val TRIES = 3
        private val JSON = Json { ignoreUnknownKeys = true }

        /** 1 s, 2 s, 4 s… */
        fun backoff(attempt: Int): Long = 1000L shl (attempt - 1).coerceIn(0, 5)
    }
}

/** What an upload sends: a file on disk read from an offset, or a few bytes (the note). */
sealed class UploadSource {
    abstract val length: Long
    abstract fun body(offset: Long, type: ContentType): OutgoingContent

    class FromFile(private val file: File) : UploadSource() {
        override val length: Long = file.length()
        override fun body(offset: Long, type: ContentType): OutgoingContent = object : OutgoingContent.WriteChannelContent() {
            override val contentLength: Long = length - offset
            override val contentType: ContentType = type
            override suspend fun writeTo(channel: ByteWriteChannel) {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(offset)
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = raf.read(buffer)
                        if (n < 0) break
                        channel.writeFully(buffer, 0, n)
                    }
                }
            }
        }
    }

    class FromBytes(private val bytes: ByteArray) : UploadSource() {
        override val length: Long = bytes.size.toLong()
        override fun body(offset: Long, type: ContentType): OutgoingContent =
            ByteArrayContent(bytes.copyOfRange(offset.toInt(), bytes.size), type)
    }
}

private class ByteArrayContent(private val bytes: ByteArray, private val type: ContentType?) : OutgoingContent.ByteArrayContent() {
    override val contentLength: Long = bytes.size.toLong()
    override val contentType: ContentType? get() = type
    override fun bytes(): ByteArray = bytes
}
