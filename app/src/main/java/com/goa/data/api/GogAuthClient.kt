package com.goa.data.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GogTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("scope") val scope: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("user_id") val userId: String
)

@Serializable
data class GogUserDataResponse(
    @SerialName("username") val username: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("avatar") val avatar: String? = null,
    @SerialName("isLoggedIn") val isLoggedIn: Boolean = false
)

class GogAuthClient(
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
        const val CLIENT_ID = "46899977096215655"
        const val CLIENT_SECRET = "9d85c43b1482497dbbce61f6e4aa173a433796eeae2ca8c5f6129f2dc4de46d9"
        const val REDIRECT_URI = "https://embed.gog.com/on_login_success?origin=client"
        const val AUTH_URL = "https://auth.gog.com/auth?client_id=$CLIENT_ID&layout=galaxy&redirect_uri=https%3A%2F%2Fembed.gog.com%2Fon_login_success%3Forigin%3Dclient&response_type=code"
        const val TOKEN_URL = "https://auth.gog.com/token"
        const val USER_DATA_URL = "https://embed.gog.com/userData.json"
    }

    suspend fun exchangeCodeForToken(code: String): Result<GogTokenResponse> = runCatching {
        httpClient.submitForm(
            url = TOKEN_URL,
            formParameters = Parameters.build {
                append("grant_type", "authorization_code")
                append("client_id", CLIENT_ID)
                append("client_secret", CLIENT_SECRET)
                append("code", code)
                append("redirect_uri", REDIRECT_URI)
            }
        ).body()
    }

    suspend fun refreshToken(refreshToken: String): Result<GogTokenResponse> = runCatching {
        httpClient.submitForm(
            url = TOKEN_URL,
            formParameters = Parameters.build {
                append("grant_type", "refresh_token")
                append("client_id", CLIENT_ID)
                append("client_secret", CLIENT_SECRET)
                append("refresh_token", refreshToken)
            }
        ).body()
    }

    suspend fun fetchUserData(accessToken: String): Result<GogUserDataResponse> = runCatching {
        httpClient.get(USER_DATA_URL) {
            header("Authorization", "Bearer $accessToken")
        }.body()
    }
}

