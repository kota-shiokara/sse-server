package com.example.sse

import com.example.sse.api.DisconnectResponse
import com.example.sse.api.ErrorResponse
import com.example.sse.api.EventRequest
import com.example.sse.api.PublishResponse
import com.example.sse.api.SERVER_HEADER
import com.example.sse.api.SERVER_HEADER_VALUE
import com.example.sse.api.ScenarioRequest
import com.example.sse.api.ScenarioResponse
import com.example.sse.api.StatsResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.send
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val allowOrigins = System.getenv("SSE_ALLOW_ORIGINS") ?: "*"
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module(allowOrigins) }
        .start(wait = true)
}

/**
 * @param allowOrigins CORS で許可するオリジン。`*` なら全許可、
 *   `http://localhost:3000,https://example.com` のようにカンマ区切りで指定、
 *   空文字なら CORS を入れない。
 *
 *   ブラウザの `EventSource` で別オリジンのアプリからこのモックを購読する
 *   のが主な用途なので、既定は全許可にしてある。
 */
fun Application.module(allowOrigins: String = "*") {
    val broadcaster = Broadcaster()
    // 遅延・連続送信・シナリオはリクエストより長生きするので、
    // call のスコープではなくアプリケーションのスコープで動かす。
    val appScope: CoroutineScope = this

    install(SSE)
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        // ボディの JSON が壊れている場合などを 400 として返す
        exception<BadRequestException> { call, cause ->
            val message = cause.cause?.message ?: cause.message ?: "bad request"
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(message))
        }
    }

    if (allowOrigins.isNotBlank()) {
        install(CORS) {
            if (allowOrigins.trim() == "*") {
                anyHost()
            } else {
                allowOrigins.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                    val url = Url(it)
                    // Origin ヘッダは既定ポートを省略して送られてくるので、
                    // 指定に明示的なポートが無ければこちらも付けない。
                    val explicitPort = ':' in it.substringAfter("://", "")
                    val host = if (explicitPort) "${url.host}:${url.port}" else url.host
                    allowHost(host, schemes = listOf(url.protocol.name))
                }
            }
            allowMethod(HttpMethod.Post)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.LastEventID)
            // 応答しているのが sse-server か、ブラウザ側からも確かめられるように
            exposeHeader(SERVER_HEADER)
        }
    }

    // 全レスポンスに識別ヘッダを付ける。ポートを別のプロセスが使っている場合に、
    // CLI 側が「応答しているのは sse-server ではない」と判定できるようにするため。
    install(
        createApplicationPlugin("ServerIdentity") {
            onCall { call -> call.response.header(SERVER_HEADER, SERVER_HEADER_VALUE) }
        }
    )

    routing {
        // 送信コンソール (src/main/resources/static/index.html)
        staticResources("/", "static")

        get("/health") { call.respondText("OK") }

        get("/stats") {
            call.respond(StatsResponse(broadcaster.subscriberCount, broadcaster.lastEventId))
        }

        // SSE ストリーム本体。接続したクライアントは publish されたフレームを受け取る。
        sse("/events") {
            // プロキシに切られないよう定期的にコメント行を流す
            heartbeat {
                period = 15.seconds
                event = ServerSentEvent(comments = "keep-alive")
            }

            send(ServerSentEvent(data = "connected", event = "open"))

            // Close が来たら takeWhile が完了し、ハンドラを抜けて接続が閉じる。
            // クライアント切断時は collect ごとキャンセルされる。
            broadcaster.signals
                .takeWhile { it is StreamSignal.Frame }
                .collect { send((it as StreamSignal.Frame).event) }
        }

        // 単純な配信。body がそのまま data になる。
        post("/broadcast") {
            val data = call.receiveText()
            if (data.isBlank()) {
                call.respondText("body is empty", status = HttpStatusCode.BadRequest)
                return@post
            }
            val id = broadcaster.publishText(data)
            call.respondText(
                "published id=$id subscribers=${broadcaster.subscriberCount}",
                status = HttpStatusCode.Accepted,
            )
        }

        // event 名・id・retry・コメント・連続送信・遅延を指定できる配信。
        post("/publish") {
            val request = call.receive<EventRequest>()
            request.validate()?.let { error ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error))
                return@post
            }

            val subscribers = broadcaster.subscriberCount
            if (request.delayMs == 0L && request.repeat == 1) {
                broadcaster.execute(request)
            } else {
                // 遅延や連続送信はレスポンスを待たせずバックグラウンドで進める
                appScope.launch { broadcaster.execute(request) }
            }

            val scheduled = if (request.disconnect) 1 else request.repeat
            call.respond(HttpStatusCode.Accepted, PublishResponse(scheduled, subscribers))
        }

        // 複数ステップをまとめて再生する。
        post("/scenario") {
            val request = call.receive<ScenarioRequest>()
            if (request.steps.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("steps is empty"))
                return@post
            }
            request.steps.forEachIndexed { index, step ->
                step.validate()?.let { error ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("steps[$index]: $error"))
                    return@post
                }
            }

            val subscribers = broadcaster.subscriberCount
            appScope.launch { request.steps.forEach { broadcaster.execute(it) } }
            call.respond(
                HttpStatusCode.Accepted,
                ScenarioResponse(request.name, request.steps.size, subscribers),
            )
        }

        // 接続中のストリームをサーバー側から切断する。
        post("/disconnect") {
            call.respond(HttpStatusCode.Accepted, DisconnectResponse(broadcaster.disconnectAll()))
        }
    }
}

/** 1 ステップ分の指定を実行する。 */
private suspend fun Broadcaster.execute(request: EventRequest) {
    if (request.delayMs > 0) delay(request.delayMs)

    if (request.disconnect) {
        disconnectAll()
        return
    }

    repeat(request.repeat) { index ->
        if (index > 0 && request.intervalMs > 0) delay(request.intervalMs)
        publish(request.toServerSentEvent(::nextId))
    }
}
