// mira-gemini-live-relay Cloudflare Worker
// Mirroring the Supabase gemini-live-relay Edge Function
// Upstream WebSocket proxy to Google Gemini Live API
// NEVER logs API keys or payload frame content.

const GOOGLE_LIVE_WS_URL =
  "https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";

addEventListener("fetch", (event) => {
  event.respondWith(handleRequest(event.request));
});

async function handleRequest(request) {
  const url = new URL(request.url);

  // Health probe: GET ?action=health
  if (url.searchParams.get("action") === "health") {
    return new Response(
      JSON.stringify({ service: "gemini-live-relay", version: "1.0.0", ok: true }),
      { headers: { "Content-Type": "application/json" }, status: 200 }
    );
  }

  // WebSocket upgrade validation
  const upgradeHeader = request.headers.get("Upgrade");
  if (!upgradeHeader || upgradeHeader.toLowerCase() !== "websocket") {
    return new Response("WebSocket upgrade required", { status: 426 });
  }

  // Extract BYOK key from x-mira-key header
  const apiKey = (request.headers.get("x-mira-key") || "").trim();
  if (!apiKey) {
    return new Response(JSON.stringify({ error: "Missing x-mira-key header" }), {
      headers: { "Content-Type": "application/json" },
      status: 401,
    });
  }

  // Connect upstream to Google Live
  const upstreamUrl = `${GOOGLE_LIVE_WS_URL}?key=${encodeURIComponent(apiKey)}`;
  let upstreamResponse;
  try {
    upstreamResponse = await fetch(upstreamUrl, {
      headers: { Upgrade: "websocket" },
    });
  } catch (err) {
    return new Response(`Upstream connect failed: ${err.message}`, { status: 502 });
  }

  if (upstreamResponse.status !== 101 || !upstreamResponse.webSocket) {
    return new Response(
      `Upstream handshake rejected (status ${upstreamResponse.status})`,
      { status: upstreamResponse.status === 101 ? 502 : upstreamResponse.status }
    );
  }

  const upstreamSocket = upstreamResponse.webSocket;
  upstreamSocket.accept();

  // Create Client WebSocket pair
  const [client, server] = Object.values(new WebSocketPair());
  server.accept();

  let closed = false;
  const closeBoth = (code, reason) => {
    if (closed) return;
    closed = true;
    try { upstreamSocket.close(code, reason); } catch (_) {}
    try { server.close(code, reason); } catch (_) {}
  };

  upstreamSocket.addEventListener("message", (event) => {
    try { server.send(event.data); } catch (_) {}
  });
  upstreamSocket.addEventListener("close", (e) => closeBoth(e.code || 1000, e.reason || "Upstream closed"));
  upstreamSocket.addEventListener("error", () => closeBoth(1011, "Upstream error"));

  server.addEventListener("message", (event) => {
    try { upstreamSocket.send(event.data); } catch (_) {}
  });
  server.addEventListener("close", (e) => closeBoth(e.code || 1000, e.reason || "Client closed"));
  server.addEventListener("error", () => closeBoth(1011, "Client error"));

  return new Response(null, { status: 101, webSocket: client });
}
