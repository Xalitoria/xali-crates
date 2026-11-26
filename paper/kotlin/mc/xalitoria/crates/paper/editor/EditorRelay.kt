package mc.xalitoria.crates.paper.editor

import mc.xalitoria.crates.paper.XaliCrates
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.nio.file.Files
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Connects the XaliCrates web editor through an outbound relay (compatible with `menu/relay`).
 * Uses a 12-char base64url code in the URL fragment (`#<code>`), expanding to an opaque 22-char session ID and
 * 256-bit AES-GCM key via [RelayCrypto.derive].
 */
internal class EditorRelay(
    private val plugin: XaliCrates,
    private val api: EditorApi,
    private val now: () -> Long = System::currentTimeMillis,
) {
    class Share(
        val owner: String,
        val id: String,
        val secret: String,
        val key: SecretKeySpec,
        val expires: Long,
        val readOnly: Boolean = false,
        val shared: Boolean = true,
        val autoReload: Boolean = false,
        val initialCrate: String? = null,
        val ownerIp: String? = null,
        val ownerUuid: UUID? = null,
    ) {
        @Volatile var running = true
        @Volatile var ws: WebSocket? = null
        val wsSendLock = Any()
        val stopSignal = CountDownLatch(1)
        val seen: MutableMap<String, Long> = java.util.Collections.synchronizedMap(LinkedHashMap())
    }

    sealed interface Started {
        data class Ok(val link: String, val share: Share) : Started
        data class Failed(val reason: String) : Started
    }

    private val shares = ConcurrentHashMap<String, Share>()
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "XaliCrates-relay").also { it.isDaemon = true } }

    fun active(): List<Share> = shares.values.filter { it.running }

    fun start(
        owner: String,
        crate: String?,
        readOnly: Boolean = false,
        shared: Boolean = true,
        autoReload: Boolean = false,
        ownerIp: String? = null,
        ownerUuid: UUID? = null,
    ): Started {
        val settings = plugin.config.data.editor
        val relay = settings.relay.url.trim().trimEnd('/')
        if (relay.isEmpty()) return Started.Failed("editor.relay.url is not set in config.yml")
        val relayUri = runCatching { URI(relay) }.getOrNull()
        if (relayUri == null || (relayUri.scheme != "https" && relayUri.scheme != "http") || relayUri.host.isNullOrBlank()) {
            return Started.Failed("editor.relay.url must be an http:// or https:// address")
        }
        val current = now()
        shares.values.filter { !it.running || it.expires <= current }.forEach { end(it, tell = false) }
        if (shares.size >= MAX_SHARES) {
            return Started.Failed("Too many active relay sessions ($MAX_SHARES); run /xcrates web stop or /xcrates share stop")
        }
        val minutes = settings.sessionMinutes.coerceAtLeast(1)
        var lastError = "Could not open a relay session"

        for (attempt in 1..2) {
            val code = RelayCrypto.newCode()
            val derived = RelayCrypto.derive(code)
            val created = try {
                val payload = Wire.write(RelayCreateRequest(ttl = minutes * 60, id = derived.sessionId))
                val req = HttpRequest.newBuilder(URI("$relay/s"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .also { if (settings.relay.token.isNotBlank()) it.header("Authorization", "Bearer ${settings.relay.token}") }
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build()
                val res = Http.send(req, HttpResponse.BodyHandlers.ofString())
                if (res.statusCode() == 409 && attempt == 1) continue
                if (res.statusCode() != 201) {
                    return Started.Failed("Relay answered ${res.statusCode()}: ${res.body().take(120)}")
                }
                Wire.read<RelaySession>(res.body()) ?: return Started.Failed("Relay returned an invalid session response")
            } catch (e: Exception) {
                lastError = e.message ?: e::class.simpleName.orEmpty()
                return Started.Failed(lastError)
            }

            val share = Share(
                owner = owner,
                id = created.id,
                secret = created.secret,
                key = derived.key,
                expires = now() + minutes * 60_000L,
                readOnly = readOnly,
                shared = shared,
                autoReload = autoReload,
                initialCrate = crate,
                ownerIp = EditorGuard.normalizeIp(ownerIp),
                ownerUuid = ownerUuid,
            )
            shares[share.id] = share
            if (created.ws) {
                workers.execute { runWebSocket(relay, share) }
            } else {
                workers.execute { poll(relay, share) }
            }
            return Started.Ok(shortLink(settings.site, relay, code), share)
        }
        return Started.Failed(lastError)
    }

    fun stop(owner: String? = null, shared: Boolean? = null): Int {
        val ending = shares.values.filter { (owner == null || it.owner == owner) && (shared == null || it.shared == shared) }
        for (share in ending) end(share, tell = true)
        return ending.size
    }

    private fun end(share: Share, tell: Boolean) {
        share.running = false
        share.stopSignal.countDown()
        shares.remove(share.id)
        share.ws?.let { socket ->
            share.ws = null
            runCatching { synchronized(share.wsSendLock) { socket.sendClose(WebSocket.NORMAL_CLOSURE, "ended").get(2, TimeUnit.SECONDS) } }
        }
        if (!tell) return
        val relay = plugin.config.data.editor.relay.url.trim().trimEnd('/')
        runCatching {
            Http.send(
                HttpRequest.newBuilder(URI("$relay/s/${share.id}"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer ${share.secret}")
                    .DELETE()
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            )
        }
    }

    private fun runWebSocket(relay: String, share: Share) {
        val endpoint = wsUri(relay, share.id)
        var failures = 0
        while (share.running && now() < share.expires) {
            val closed = CountDownLatch(1)
            val listener = object : WebSocket.Listener {
                private val buffer = StringBuilder()
                private var oversized = false

                override fun onOpen(webSocket: WebSocket) {
                    failures = 0
                    webSocket.request(1)
                }

                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    if (!oversized) {
                        if (buffer.length + data.length > MAX_WS_FRAME) {
                            oversized = true
                            buffer.setLength(0)
                        } else {
                            buffer.append(data)
                        }
                    }
                    if (last) {
                        val drop = oversized
                        oversized = false
                        val text = if (drop) "" else buffer.toString()
                        buffer.setLength(0)
                        if (!drop && text != "pong") {
                            Wire.read<RelayCall>(text)?.let { call ->
                                val id = call.id?.takeIf { CALL_ID.matches(it) }
                                if (id != null) workers.execute { dispatchWsCall(relay, share, id, call, webSocket) }
                            }
                        }
                    }
                    webSocket.request(1)
                    return null
                }

                override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String?): CompletionStage<*> {
                    closed.countDown()
                    return CompletableFuture.completedFuture(null)
                }

                override fun onError(webSocket: WebSocket, error: Throwable) {
                    closed.countDown()
                }
            }

            val socket = try {
                Http.newWebSocket()
                    .header("Authorization", "Bearer ${share.secret}")
                    .buildAsync(endpoint, listener)
                    .get(15, TimeUnit.SECONDS)
            } catch (e: Exception) {
                val handshake = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<WebSocketHandshakeException>().firstOrNull()
                val status = handshake?.response?.statusCode()
                if (status == 404 || status == 401) break
                if (status == 426 || status == 501) {
                    poll(relay, share)
                    return
                }
                if (++failures >= 10) break
                val delayMs = minOf(10_000L, 1_000L shl (failures - 1).coerceAtMost(3))
                if (share.stopSignal.await(delayMs, TimeUnit.MILLISECONDS)) break
                continue
            }

            share.ws = socket
            while (share.running && now() < share.expires) {
                if (closed.await(WS_PING_SECONDS, TimeUnit.SECONDS)) break
                if (!share.running || now() >= share.expires) break
                val ok = runCatching {
                    synchronized(share.wsSendLock) { socket.sendText("ping", true).get(10, TimeUnit.SECONDS) }
                }.isSuccess
                if (!ok) break
            }
            if (share.ws === socket) share.ws = null
            runCatching { socket.abort() }
            if (share.running && now() < share.expires) {
                if (++failures >= 10) break
                if (share.stopSignal.await(minOf(10_000L, 1_000L * failures), TimeUnit.MILLISECONDS)) break
            }
        }
        if (shares[share.id] === share) end(share, tell = share.running)
        share.running = false
    }

    private fun dispatchWsCall(relay: String, share: Share, callId: String, call: RelayCall, ws: WebSocket) {
        if (!CALL_ID.matches(callId)) return
        var clientIp = call.ip
        val data = if (call.fetch) {
            runCatching {
                val req = HttpRequest.newBuilder(URI("$relay/s/${share.id}/call/$callId"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer ${share.secret}")
                    .GET()
                    .build()
                val res = Http.send(req, HttpResponse.BodyHandlers.ofInputStream())
                res.body().use { stream ->
                    if (res.statusCode() != 200) return@runCatching null
                    if (clientIp.isNullOrBlank()) clientIp = res.headers().firstValue("x-relay-client-ip").orElse(null)
                    val bytes = stream.readNBytes(MAX_RELAY_BODY + 1)
                    bytes.takeIf { it.size <= MAX_RELAY_BODY }
                }
            }.getOrNull() ?: return
        } else {
            call.data?.let { runCatching { Base64.getUrlDecoder().decode(it) }.getOrNull() } ?: return
        }
        answer(relay, share, callId, data, ws, clientIp)
    }

    private fun poll(relay: String, share: Share) {
        var failures = 0
        while (share.running && now() < share.expires) {
            try {
                val req = HttpRequest.newBuilder(URI("$relay/s/${share.id}/poll?wait=25"))
                    .timeout(Duration.ofSeconds(40))
                    .header("Authorization", "Bearer ${share.secret}")
                    .GET()
                    .build()
                val res = Http.send(req, HttpResponse.BodyHandlers.ofString())
                when (res.statusCode()) {
                    200 -> {
                        failures = 0
                        for (call in Wire.read<List<RelayCall>>(res.body()).orEmpty()) {
                            val id = call.id?.takeIf { CALL_ID.matches(it) } ?: continue
                            val data = call.data?.let { runCatching { Base64.getUrlDecoder().decode(it) }.getOrNull() } ?: continue
                            workers.execute { answer(relay, share, id, data, null, call.ip) }
                        }
                    }
                    204 -> failures = 0
                    404 -> break
                    else -> {
                        failures++
                        if (share.stopSignal.await(3000, TimeUnit.MILLISECONDS)) break
                    }
                }
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {
                if (++failures >= 20) break
                if (share.stopSignal.await(3000, TimeUnit.MILLISECONDS)) break
            }
        }
        if (shares[share.id] === share) end(share, tell = false)
        share.running = false
    }

    private fun answer(relay: String, share: Share, callId: String, data: ByteArray, ws: WebSocket?, clientIp: String? = null) {
        if (!CALL_ID.matches(callId)) return
        val reply = handle(share, data, clientIp) ?: return
        val sealed = RelayCrypto.seal(share.key, share.id, "a", Wire.write(reply).toByteArray(Charsets.UTF_8))
        if (ws != null && sealed.size <= WS_ANSWER_MAX) {
            val frame = Wire.write(RelayWsAnswer(callId, Base64.getUrlEncoder().withoutPadding().encodeToString(sealed)))
            val sent = runCatching {
                synchronized(share.wsSendLock) { ws.sendText(frame, true).get(15, TimeUnit.SECONDS) }
            }.isSuccess
            if (sent) return
        }
        runCatching {
            val req = HttpRequest.newBuilder(URI("$relay/s/${share.id}/answer/$callId"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer ${share.secret}")
                .POST(HttpRequest.BodyPublishers.ofByteArray(sealed))
                .build()
            Http.send(req, HttpResponse.BodyHandlers.discarding())
        }
    }

    internal fun handle(share: Share, data: ByteArray, clientIp: String? = null): RelayReply? {
        val plain = RelayCrypto.open(share.key, share.id, "q", data) ?: return null
        val request = Wire.read<RelayRequest>(plain.decodeToString()) ?: return null
        val rid = request.rid?.takeIf { it.length in 1..64 } ?: return null
        val time = request.t ?: return null
        val current = now()
        if (abs(current - time) > FRESH) return null
        synchronized(share.seen) {
            val iter = share.seen.entries.iterator()
            while (iter.hasNext()) {
                if (current - iter.next().value > FRESH) iter.remove() else break
            }
            if (share.seen.containsKey(rid)) return null
            share.seen[rid] = current
            while (share.seen.size > MAX_SEEN) share.seen.remove(share.seen.keys.first())
        }
        val method = request.method
        val path = request.path
        if (method !in METHODS || path == null || !path.startsWith("/")) return failure(rid, 400, "Bad request")
        val bodyBytes = request.body.orEmpty().toByteArray(Charsets.UTF_8)
        if (bodyBytes.size > EditorApi.MAX_BODY) return failure(rid, 413, "Request body too large")
        val from = if (share.shared) clean(request.from) else share.owner
        val query = path.substringAfter('?', "").ifEmpty { null }
        val call = EditorApi.Call(method!!, path.substringBefore('?'), query, bodyBytes, request.range)
        val reply = try {
            api.handle(
                call,
                EditorApi.Caller(
                    owner = share.owner,
                    shared = share.shared,
                    from = from,
                    readOnly = share.readOnly,
                    autoReload = share.autoReload,
                    initialCrate = share.initialCrate,
                    ownerIp = share.ownerIp,
                    ownerUuid = share.ownerUuid,
                    clientIp = EditorGuard.normalizeIp(clientIp),
                ),
            )
        } catch (e: Exception) {
            plugin.logger.warning("Web editor failed on relay request $path: ${e.message}")
            return failure(rid, 500, "The server could not answer")
        }
        val bytes = reply.file?.let { if (Files.size(it) <= MAX_ANSWER) Files.readAllBytes(it) else null }
        val tooBig = (reply.file != null && bytes == null) || reply.body.size > MAX_ANSWER
        if (tooBig) return failure(rid, 413, "Response exceeds relay limit; request a byte Range")
        return RelayReply(rid, reply.status, reply.type, Base64.getEncoder().encodeToString(bytes ?: reply.body))
    }

    private fun failure(rid: String, status: Int, message: String) = RelayReply(
        rid = rid,
        status = status,
        type = JSON,
        body = Base64.getEncoder().encodeToString(Wire.write(ErrorReply(message)).toByteArray(Charsets.UTF_8)),
    )

    companion object {
        private const val FRESH = 2 * 60_000L
        private const val MAX_SEEN = 4096
        private const val MAX_SHARES = 20
        private const val MAX_WS_FRAME = 1024 * 1024
        private const val MAX_RELAY_BODY = EditorApi.MAX_BODY + 256 * 1024
        private const val MAX_ANSWER = 5 * 1024 * 1024
        private const val WS_ANSWER_MAX = 512 * 1024
        private const val WS_PING_SECONDS = 55L
        private const val JSON = "application/json; charset=utf-8"
        private val METHODS = setOf("GET", "PUT", "POST", "DELETE")
        val CALL_ID = Regex("[A-Za-z0-9_-]{1,64}")

        fun wsUri(relay: String, sessionId: String): URI {
            val base = relay.trimEnd('/')
            val wsBase = when {
                base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
                base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
                else -> base
            }
            return URI("$wsBase/s/$sessionId/ws")
        }

        fun clean(name: String?): String = name.orEmpty()
            .filter { !it.isISOControl() && it != '§' && it != '&' }
            .trim()
            .take(32)
            .ifEmpty { "guest" }

        fun shortLink(site: String, relay: String, code: String): String {
            val cleanSite = site.trimEnd('/')
            val cleanRelay = relay.trimEnd('/')
            return if (cleanRelay == "$cleanSite/relay") {
                "$cleanSite/#$code"
            } else {
                "$cleanSite/#$code&relay=${URLEncoder.encode(cleanRelay, Charsets.UTF_8)}"
            }
        }
    }
}
