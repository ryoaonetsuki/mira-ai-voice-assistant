package io.github.ryoaonetsuki.mira.backend

enum class LicenceGateOutcome {
    PASS,
    BLOCK_NO_KEY,
    BLOCK_INVALID
}

/**
 * Resolves whether the app should pass or block based on licence configuration.
 * Fail-open: network/server errors never brick the app.
 */
fun resolveLicenceGate(
    licenceEnabled: Boolean,
    storedKey: String?,
    validation: Result<Boolean>?
): LicenceGateOutcome {
    if (!licenceEnabled) return LicenceGateOutcome.PASS
    if (storedKey.isNullOrBlank()) return LicenceGateOutcome.BLOCK_NO_KEY
    if (validation == null) return LicenceGateOutcome.PASS
    if (validation.isFailure) return LicenceGateOutcome.PASS
    return if (validation.getOrNull() == true) {
        LicenceGateOutcome.PASS
    } else {
        LicenceGateOutcome.BLOCK_INVALID
    }
}
