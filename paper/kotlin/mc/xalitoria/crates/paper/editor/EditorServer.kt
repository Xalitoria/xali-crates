package mc.xalitoria.crates.paper.editor

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import mc.xalitoria.crates.paper.XaliCrates
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.io.path.fileSize

/**
 * Direct JDK HTTP endpoint for the web editor when `editor.enabled` is true in `config.yml`.
 */
internal class EditorServer(
    private val plugin: XaliCrates,
    val sessions: EditorSessions,
    val pending: PendingEdits = PendingEdits(),
) {
    val files = EditorFiles(plugin)
    val api = EditorApi(plugin, files, pending)
    private var server: HttpServer? = null

    val running: Boolean get() = server != null

    fun start(): String? {
        val settings = plugin.config.data.editor
        return try {
            val http = HttpServer.create(InetSocketAddress(settings.host, settings.port), 0)
            http.createContext("/v1") { exchange -> handle(exchange) }
            http.executor = Executors.newFixedThreadPool(2) { task -> Thread(task, "XaliCrates-editor").also { it.isDaemon = true } }
            http.start()
            server = http
            null
        } catch (e: Exception) {
            e.message ?: e::class.simpleName.orEmpty()
        }
    }

    fun stop() {
        server?.stop(0)
        server = null
        sessions.clear()
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val origin = exchange.requestHeaders.getFirst("Origin")
            exchange.responseHeaders.add("Access-Control-Expose-Headers", "Content-Range")
            if (origin != null && !allowed(origin)) {
                return reply(exchange, 403, Wire.write(ErrorReply("Origin is not allowed to call this editor endpoint")))
            }
            if (origin != null) {
                exchange.responseHeaders.add("Access-Control-Allow-Origin", origin)
                exchange.responseHeaders.add("Vary", "Origin")
            }
            if (exchange.requestMethod == "OPTIONS") {
                exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, PUT, POST, DELETE, OPTIONS")
                exchange.responseHeaders.add("Access-Control-Allow-Headers", "Authorization, Content-Type, Range, X-Editor-From")
                if (exchange.requestHeaders.getFirst("Access-Control-Request-Private-Network").equals("true", ignoreCase = true)) {
                    exchange.responseHeaders.add("Access-Control-Allow-Private-Network", "true")
                }
                exchange.responseHeaders.add("Access-Control-Max-Age", "600")
                return reply(exchange, 204, null)
            }

            val auth = exchange.requestHeaders.getFirst("Authorization")
            val token = auth?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
            val session = sessions.session(token) ?: return reply(exchange, 401, Wire.write(ErrorReply("The editor link has expired")))
            val bytes = exchange.requestBody.readNBytes(EditorApi.MAX_BODY + 1)
            if (bytes.size > EditorApi.MAX_BODY) return reply(exchange, 413, Wire.write(ErrorReply("Request body too large")))

            val path = exchange.requestURI.path.removePrefix("/v1")
            val from = if (session.shared) EditorRelay.clean(exchange.requestHeaders.getFirst("X-Editor-From") ?: session.owner) else session.owner
            val clientIp = EditorGuard.resolveClientIp(
                remoteIp = exchange.remoteAddress?.address?.hostAddress,
                cfConnectingIp = exchange.requestHeaders.getFirst("CF-Connecting-IP"),
                forwardedFor = exchange.requestHeaders.getFirst("X-Forwarded-For"),
                trustProxy = plugin.config.data.editor.trustProxy,
            )
            val caller = EditorApi.Caller(
                owner = session.owner,
                shared = session.shared,
                from = from,
                readOnly = session.readOnly,
                autoReload = session.autoReload,
                initialCrate = session.initialCrate,
                ownerIp = session.ownerIp,
                ownerUuid = session.ownerUuid,
                clientIp = clientIp,
            )
            val result = api.handle(
                EditorApi.Call(
                    method = exchange.requestMethod,
                    path = path,
                    query = exchange.requestURI.rawQuery,
                    body = bytes,
                    range = exchange.requestHeaders.getFirst("Range"),
                ),
                caller,
            )
            send(exchange, result)
        } catch (e: Exception) {
            plugin.logger.warning("Web editor HTTP request failed on ${exchange.requestURI}: ${e.message}")
            runCatching { reply(exchange, 500, Wire.write(ErrorReply("The server could not answer"))) }
        } finally {
            exchange.close()
        }
    }

    private fun send(exchange: HttpExchange, result: EditorApi.Reply) {
        exchange.responseHeaders.add("Content-Type", result.type)
        result.cache?.let { exchange.responseHeaders.add("Cache-Control", it) }
        for ([name, value] in result.headers) exchange.responseHeaders.add(name, value)
        if (result.file != null) {
            exchange.sendResponseHeaders(result.status, result.file.fileSize())
            Files.copy(result.file, exchange.responseBody)
        } else if (result.status == 204) {
            exchange.sendResponseHeaders(204, -1)
        } else {
            exchange.sendResponseHeaders(result.status, result.body.size.toLong())
            exchange.responseBody.write(result.body)
        }
    }

    private fun reply(exchange: HttpExchange, status: Int, body: String?) {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1)
            return
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    /** The configured editor site, plus localhost only when listening on a loopback address. */
    private fun allowed(origin: String): Boolean {
        val uri = runCatching { URI(origin) }.getOrNull() ?: return false
        val editorCfg = plugin.config.data.editor
        val site = runCatching { URI(editorCfg.site) }.getOrNull()
        if (site != null && uri.scheme == site.scheme && uri.host == site.host && uri.port == site.port) return true
        return EditorGuard.isLoopback(editorCfg.host) && uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1")
    }
}
