package io.github.ryoaonetsuki.mira.backend

interface PushRegistrar {
    suspend fun registerDeviceToken(token: String): Result<Unit>
    suspend fun unregisterDeviceToken(): Result<Unit>
}

class StubPushRegistrar : PushRegistrar {
    override suspend fun registerDeviceToken(token: String): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun unregisterDeviceToken(): Result<Unit> {
        return Result.success(Unit)
    }
}
