package com.goa.data.repository

import com.goa.data.api.GogAuthClient
import com.goa.data.api.SecureSessionStorage
import com.goa.data.api.UserSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AuthRepository(
    private val authClient: GogAuthClient,
    private val sessionStorage: SecureSessionStorage
) {
    private val _userSession = MutableStateFlow<UserSession?>(sessionStorage.getSession())
    val userSession: StateFlow<UserSession?> = _userSession.asStateFlow()

    private val refreshMutex = Mutex()

    fun isLoggedIn(): Boolean {
        return _userSession.value != null
    }

    suspend fun handleAuthCode(code: String): Result<UserSession> {
        return authClient.exchangeCodeForToken(code).mapCatching { tokenResp ->
            val now = System.currentTimeMillis()
            val expiresAt = now + (tokenResp.expiresIn * 1000L)

            // Try to fetch user data for username/avatar
            val userData = authClient.fetchUserData(tokenResp.accessToken).getOrNull()

            val session = UserSession(
                accessToken = tokenResp.accessToken,
                refreshToken = tokenResp.refreshToken,
                expiresAtTimestamp = expiresAt,
                userId = tokenResp.userId,
                username = userData?.username ?: "GOG User",
                avatarUrl = userData?.avatar
            )

            sessionStorage.saveSession(session)
            _userSession.value = session
            session
        }
    }

    suspend fun getValidAccessToken(): String? {
        val current = _userSession.value ?: sessionStorage.getSession() ?: return null

        val now = System.currentTimeMillis()
        if (now < current.expiresAtTimestamp - 60_000L) {
            return current.accessToken
        }

        // Token expired or expiring soon, refresh with lock
        return refreshMutex.withLock {
            val freshCheck = _userSession.value ?: sessionStorage.getSession() ?: return null
            if (now < freshCheck.expiresAtTimestamp - 60_000L) {
                return freshCheck.accessToken
            }

            val refreshResult = authClient.refreshToken(freshCheck.refreshToken)
            if (refreshResult.isSuccess) {
                val tokenResp = refreshResult.getOrThrow()
                val newExpiresAt = System.currentTimeMillis() + (tokenResp.expiresIn * 1000L)
                val updatedSession = freshCheck.copy(
                    accessToken = tokenResp.accessToken,
                    refreshToken = tokenResp.refreshToken,
                    expiresAtTimestamp = newExpiresAt
                )
                sessionStorage.saveSession(updatedSession)
                _userSession.value = updatedSession
                updatedSession.accessToken
            } else {
                null
            }
        }
    }

    fun logout() {
        sessionStorage.clearSession()
        _userSession.value = null
    }
}

