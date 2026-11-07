package io.github.ryoaonetsuki.mira.voice

import okhttp3.Request

/**
 * Cloudflare Worker Live Relay Client for Mira AI (ROUND 15).
 * Proxies BidiGenerateContent WebSocket sessions via the deployed Worker:
 * Endpoint: wss://mira-gemini-live-relay.salman-dev.workers.dev/live
 * Auth header: x-mira-gemini-key: <api_key> (never logged, forwarded upstream as x-goog-api-key)
 */
object RelayLiveClient {
    const val RELAY_WS_URL = "wss://mira-gemini-live-relay.salman-dev.workers.dev/live"
    const val HEADER_MIRA_KEY = "x-mira-gemini-key"
    const val HEADER_MIRA_KEY_LEGACY = "x-mira-key"

    fun buildRelayRequest(apiKey: String): Request {
        return Request.Builder()
            .url(RELAY_WS_URL)
            .addHeader(HEADER_MIRA_KEY, apiKey)
            .build()
    }

    fun buildDirectRequest(directUrl: String, apiKey: String): Request {
        return Request.Builder()
            .url(directUrl)
            .addHeader("x-goog-api-key", apiKey)
            .build()
    }
}
