package com.ryanvidal.barfriend.core.data

import com.ryanvidal.barfriend.core.model.Bar
import com.ryanvidal.barfriend.core.model.ServerInfo

sealed interface ServerCheckResult {
    data class Compatible(val serverInfo: ServerInfo) : ServerCheckResult
    data class UnsupportedVersion(val actual: String, val minimum: String) : ServerCheckResult
    data class PasswordLoginDisabled(val serverInfo: ServerInfo) : ServerCheckResult
    data class Error(val message: String) : ServerCheckResult
}

interface AuthRepository {
    suspend fun checkServer(serverUrl: String): ServerCheckResult
    suspend fun signIn(serverUrl: String, email: String, password: String): Result<Bar>
    suspend fun signOut(): Result<Unit>
}
