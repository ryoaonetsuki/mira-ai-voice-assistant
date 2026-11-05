package io.github.ryoaonetsuki.mira.backend

data class RemoteBanner(
    val id: String,
    val title: String,
    val message: String,
    val actionUrl: String? = null
)

data class RemoteMacroDef(
    val id: String,
    val title: String,
    val description: String,
    val actionType: String,
    val payload: String
)

interface RemoteConfig {
    suspend fun fetchBanner(): RemoteBanner?
    suspend fun isKillSwitchActive(): Boolean
    suspend fun isMaintenanceMode(): Boolean
    suspend fun checkForcedUpdate(): String?
    suspend fun fetchRemoteMacros(): List<RemoteMacroDef>
}
