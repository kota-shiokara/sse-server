package jp.ikanoshiokara.sse.cli

import jp.ikanoshiokara.sse.api.EventRequest
import jp.ikanoshiokara.sse.api.SERVER_HEADER
import jp.ikanoshiokara.sse.api.SERVER_HEADER_VALUE
import com.github.ajalt.clikt.core.CliktError
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * CLI とサーバーは別モジュールなので、実際に線を流れる形が
 * サーバーの期待とずれていないかをここで固定する。
 */
class SseApiTest {

    @Test
    fun `publish posts the event as json to slash publish`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respondJson("""{"scheduled":3,"subscribers":1}""")
        }

        val result = SseApi(BASE, engine).use {
            it.publish(EventRequest(data = "hello", repeat = 3, intervalMs = 200))
        }

        assertEquals(3, result.scheduled)
        assertEquals(1, result.subscribers)

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("$BASE/publish", request.url.toString())
        assertEquals(
            ContentType.Application.Json,
            request.body.contentType?.withoutParameters(),
        )
        // 既定値も含めて送る (サーバーは ignoreUnknownKeys = false で受ける)
        assertEquals(
            """{"event":"message","data":"hello","id":null,"retry":null,"comment":null,""" +
                """"repeat":3,"intervalMs":200,"delayMs":0,"disconnect":false}""",
            (request.body as TextContent).text,
        )
    }

    @Test
    fun `stats decodes the response`() = runTest {
        val engine = MockEngine { respondJson("""{"subscribers":2,"lastEventId":42}""") }

        val stats = SseApi(BASE, engine).use { it.stats() }

        assertEquals(2, stats.subscribers)
        assertEquals(42, stats.lastEventId)
    }

    @Test
    fun `a server error is surfaced with its message`() = runTest {
        val engine = MockEngine {
            respond(
                """{"error":"repeat must be >= 1"}""",
                HttpStatusCode.BadRequest,
                headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    SERVER_HEADER to listOf(SERVER_HEADER_VALUE),
                ),
            )
        }

        val error = assertFailsWith<CliktError> {
            SseApi(BASE, engine).use { it.publish(EventRequest(data = "x")) }
        }
        assertEquals("HTTP 400: repeat must be >= 1", error.message)
    }

    @Test
    fun `a 404 without the identity header is reported as a port mix-up`() = runTest {
        // ポートを別のサーバーに取られている状況
        val engine = MockEngine { respond("Not Found", HttpStatusCode.NotFound) }

        val error = assertFailsWith<CliktError> { SseApi(BASE, engine).use { it.stats() } }
        assertTrue(
            "sse-server ではありません" in error.message.orEmpty(),
            "unexpected message: ${error.message}",
        )
    }

    @Test
    fun `a 404 with the identity header stays an http error`() = runTest {
        // sse-server は応答しているが、そのパスが無い状況
        val engine = MockEngine {
            respond(
                "Not Found",
                HttpStatusCode.NotFound,
                headersOf(SERVER_HEADER, SERVER_HEADER_VALUE),
            )
        }

        val error = assertFailsWith<CliktError> { SseApi(BASE, engine).use { it.stats() } }
        assertEquals("HTTP 404: Not Found", error.message)
    }

    @Test
    fun `a broken response body is reported as such`() = runTest {
        val engine = MockEngine { respondJson("not json at all") }

        val error = assertFailsWith<CliktError> { SseApi(BASE, engine).use { it.stats() } }
        assertTrue(
            "レスポンスを解釈できません" in error.message.orEmpty(),
            "unexpected message: ${error.message}",
        )
    }

    private companion object {
        const val BASE = "http://localhost:8080"

        fun MockRequestHandleScope.respondJson(body: String) = respond(
            body,
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }
}
