package mc.xalitoria.crates.paper.editor

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration

/**
 * Shared HTTP client for outbound web editor requests (relay and allowlisted skin fetches).
 * Redirects are never followed automatically so allowlisted hosts cannot redirect the server to internal addresses.
 */
internal object Http {
    private val CONNECT = Duration.ofSeconds(8)
    private val direct = HttpClient.newBuilder().connectTimeout(CONNECT).build()
    private val REDIRECTS = setOf(301, 302, 303, 307, 308)

    fun <T> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> =
        direct.send(request, handler)

    fun newWebSocket(): WebSocket.Builder = direct.newWebSocketBuilder().connectTimeout(CONNECT)

    /**
     * Fetches [address] with an upper byte limit of [maxBytes], verifying [allowed] before the initial request and
     * before every redirect hop (up to [maxRedirects]).
     */
    fun getChecked(
        address: URI,
        timeout: Duration,
        maxBytes: Int,
        maxRedirects: Int = 3,
        allowed: (URI) -> Boolean,
    ): ByteArray? {
        var current = address
        for (hop in 0..maxRedirects) {
            if (!allowed(current)) return null
            val request = HttpRequest.newBuilder(current).timeout(timeout).GET().build()
            val response = send(request, HttpResponse.BodyHandlers.ofInputStream())
            val status = response.statusCode()
            if (status in REDIRECTS) {
                response.body().use { /* discard redirect body */ }
                if (hop == maxRedirects) return null
                val location = response.headers().firstValue("location").orElse(null) ?: return null
                current = runCatching { current.resolve(location) }.getOrNull() ?: return null
                continue
            }
            response.body().use { stream ->
                if (status != 200) return null
                val bytes = stream.readNBytes(maxBytes + 1)
                return bytes.takeIf { it.size <= maxBytes }
            }
        }
        return null
    }
}
