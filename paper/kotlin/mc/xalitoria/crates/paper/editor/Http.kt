package mc.xalitoria.crates.paper.editor

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration

/** Shared HTTP client for outbound web editor relay calls. */
internal object Http {
    private val CONNECT = Duration.ofSeconds(8)
    private val client = HttpClient.newBuilder().connectTimeout(CONNECT).build()

    fun <T> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> =
        client.send(request, handler)

    fun newWebSocket(): WebSocket.Builder = client.newWebSocketBuilder().connectTimeout(CONNECT)
}
