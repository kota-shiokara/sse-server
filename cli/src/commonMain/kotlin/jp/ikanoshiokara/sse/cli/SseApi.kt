package jp.ikanoshiokara.sse.cli

import jp.ikanoshiokara.sse.api.DisconnectResponse
import jp.ikanoshiokara.sse.api.ErrorResponse
import jp.ikanoshiokara.sse.api.EventRequest
import jp.ikanoshiokara.sse.api.PublishResponse
import jp.ikanoshiokara.sse.api.SERVER_HEADER
import jp.ikanoshiokara.sse.api.SERVER_HEADER_VALUE
import jp.ikanoshiokara.sse.api.ScenarioRequest
import jp.ikanoshiokara.sse.api.ScenarioResponse
import jp.ikanoshiokara.sse.api.StatsResponse
import com.github.ajalt.clikt.core.CliktError
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import kotlinx.io.IOException
import kotlinx.serialization.json.Json

/** サーバーと同じ既定 (encodeDefaults) で組み立てる。 */
internal val json = Json { encodeDefaults = true }

private fun cioClient() = HttpClient(CIO) {
    engine {
        // watch は無期限に読み続けるので、リクエスト全体のタイムアウトは掛けない。
        // 「サーバーが居ない」は接続タイムアウトで判定する。
        requestTimeout = 0
        endpoint.connectTimeout = 5_000
    }
}

/**
 * WebAPI の薄いクライアント。
 *
 * CLI からサーバーに触る経路はここだけで、共有しているのは
 * shared モジュールの DTO (契約) のみ。
 */
class SseApi private constructor(
    private val base: String,
    private val client: HttpClient,
) : AutoCloseable {

    constructor(base: String) : this(base, cioClient())

    /** テスト用。任意のエンジン (MockEngine など) で組む。 */
    internal constructor(base: String, engine: HttpClientEngine) : this(base, HttpClient(engine))

    suspend fun publish(request: EventRequest): PublishResponse =
        decode(postJson("/publish", json.encodeToString(request)))

    suspend fun scenario(request: ScenarioRequest): ScenarioResponse =
        decode(postJson("/scenario", json.encodeToString(request)))

    suspend fun disconnect(): DisconnectResponse = decode(postJson("/disconnect", "{}"))

    suspend fun stats(): StatsResponse = decode(getText("/stats"))

    /** SSE ストリームを購読し、受け取った行をそのまま [onLine] に渡す。 */
    suspend fun watch(onLine: (String) -> Unit) = reachable {
        client.prepareGet("$base/events") {
            header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
        }.execute { response ->
            if (!response.status.isSuccess()) failFor(response)
            val channel = response.bodyAsChannel()
            while (true) {
                onLine(channel.readLine() ?: break)
            }
        }
    }

    override fun close() = client.close()

    private suspend fun postJson(path: String, body: String): String = reachable {
        bodyOrFail(
            client.post("$base$path") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        )
    }

    private suspend fun getText(path: String): String = reachable {
        bodyOrFail(client.get("$base$path"))
    }

    private suspend fun bodyOrFail(response: HttpResponse): String {
        val text = response.bodyAsText()
        if (response.status.isSuccess()) return text
        failFor(response, text)
    }

    /**
     * エラーレスポンスを CLI のメッセージに翻訳して終了する。
     *
     * 404 のときは、そのポートで応答しているのが本当に sse-server なのかを
     * 先に確かめる。別のサーバーが同じポートを使っていると、接続自体は成功して
     * 見当のつかない 404 が返ってくるため。
     */
    private suspend fun failFor(response: HttpResponse, body: String? = null): Nothing {
        if (response.status == HttpStatusCode.NotFound) failIfNotOurServer()

        val text = body ?: response.bodyAsText()
        val detail = runCatching { json.decodeFromString<ErrorResponse>(text).error }
            .getOrNull()
            ?: text.ifBlank { response.status.description }
        throw CliktError("HTTP ${response.status.value}: $detail")
    }

    /**
     * [base] で応答しているのが sse-server でなければ、そう伝えて終了する。
     *
     * `/health` が 200 を返すだけでは判定できない (他のサーバーもよく返す) ので、
     * sse-server が全レスポンスに付けている識別ヘッダで見分ける。
     */
    private suspend fun failIfNotOurServer() {
        val isOurs = runCatching {
            client.get("$base/health").headers[SERVER_HEADER] == SERVER_HEADER_VALUE
        }.getOrDefault(false)
        if (isOurs) return

        val port = base.substringAfterLast(':', "").takeWhile { it.isDigit() }.ifEmpty { "<port>" }
        throw CliktError(
            """
            $base で応答しているのは sse-server ではありません。

            sse-server が起動していないか、そのポートを別のプロセスが使っています。
            確認と対処:
              # コンテナの状態を見る
              docker compose ps
              # そのポートを誰が使っているか見る
              lsof -nP -iTCP:$port -sTCP:LISTEN
              # 別のポートで起動して、そちらを叩く
              SSE_PORT=8099 docker compose up -d
              ssectl stats --base=http://localhost:8099
            """.trimIndent()
        )
    }

    /** 接続そのものに失敗したら、原因が分かる形にして終了する。 */
    private suspend fun <T> reachable(block: suspend () -> T): T =
        try {
            block()
        } catch (e: IOException) {
            throw CliktError(
                "$base へ接続できません。サーバーが起動しているか確認してください " +
                    "(docker compose up -d)。\n  原因: ${e.message ?: e.toString()}"
            )
        }

    private inline fun <reified T> decode(body: String): T =
        runCatching { json.decodeFromString<T>(body) }
            .getOrElse { throw CliktError("レスポンスを解釈できません: $body") }
}
