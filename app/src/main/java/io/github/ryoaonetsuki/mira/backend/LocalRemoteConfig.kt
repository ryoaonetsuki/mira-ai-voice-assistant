package io.github.ryoaonetsuki.mira.backend

class LocalRemoteConfig : RemoteConfig {
    override suspend fun fetchBanner(): RemoteBanner? = null

    override suspend fun isKillSwitchActive(): Boolean = false

    override suspend fun isMaintenanceMode(): Boolean = false

    override suspend fun checkForcedUpdate(): String? = null

    override suspend fun fetchRemoteMacros(): List<RemoteMacroDef> {
        // Remote macros will be fetched from backend when attached.
        // On-device built-in macros are executed via ExtensionManager.
        return emptyList()
    }
}
