package com.example.sse

import com.example.sse.api.SERVER_HEADER
import com.example.sse.api.SERVER_HEADER_VALUE
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApplicationTest {

    @Test
    fun `health returns OK`() = testApplication {
        application { module() }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("OK", response.bodyAsText())
    }

    @Test
    fun `stats reports zero on a fresh server`() = testApplication {
        application { module() }
        val response = client.get("/stats")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"subscribers":0,"lastEventId":0}""", response.bodyAsText())
    }

    @Test
    fun `publish accepts a minimal event`() = testApplication {
        application { module() }
        val response = client.post("/publish") {
            contentType(ContentType.Application.Json)
            setBody("""{"data":"hello"}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("""{"scheduled":1,"subscribers":0}""", response.bodyAsText())
    }

    @Test
    fun `publish reports the scheduled count for repeats`() = testApplication {
        application { module() }
        val response = client.post("/publish") {
            contentType(ContentType.Application.Json)
            setBody("""{"data":"tick","repeat":5,"intervalMs":10}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("""{"scheduled":5,"subscribers":0}""", response.bodyAsText())
    }

    @Test
    fun `publish rejects an event with nothing to send`() = testApplication {
        application { module() }
        val response = client.post("/publish") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            """{"error":"one of data, comment or retry is required"}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `publish rejects a broken body with 400`() = testApplication {
        application { module() }
        val response = client.post("/publish") {
            contentType(ContentType.Application.Json)
            setBody("{not json")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `scenario is accepted and reports its step count`() = testApplication {
        application { module() }
        val response = client.post("/scenario") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"name":"demo","steps":[
                  {"data":"a"},
                  {"delayMs":10,"data":"b"},
                  {"delayMs":10,"disconnect":true}
                ]}
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("""{"name":"demo","steps":3,"subscribers":0}""", response.bodyAsText())
    }

    @Test
    fun `scenario rejects an empty step list`() = testApplication {
        application { module() }
        val response = client.post("/scenario") {
            contentType(ContentType.Application.Json)
            setBody("""{"steps":[]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("""{"error":"steps is empty"}""", response.bodyAsText())
    }

    @Test
    fun `scenario reports which step is invalid`() = testApplication {
        application { module() }
        val response = client.post("/scenario") {
            contentType(ContentType.Application.Json)
            setBody("""{"steps":[{"data":"ok"},{"repeat":0,"data":"bad"}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("""{"error":"steps[1]: repeat must be >= 1"}""", response.bodyAsText())
    }

    @Test
    fun `disconnect reports zero when nobody is connected`() = testApplication {
        application { module() }
        val response = client.post("/disconnect")
        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("""{"disconnected":0}""", response.bodyAsText())
    }

    @Test
    fun `broadcast still accepts plain text`() = testApplication {
        application { module() }
        val response = client.post("/broadcast") { setBody("hello") }
        assertEquals(HttpStatusCode.Accepted, response.status)
        assertTrue(response.bodyAsText().startsWith("published id=1"))
    }

    @Test
    fun `broadcast rejects a blank body`() = testApplication {
        application { module() }
        val response = client.post("/broadcast") { setBody("   ") }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("body is empty", response.bodyAsText())
    }

    @Test
    fun `every response carries the identity header`() = testApplication {
        application { module() }
        // CLI がポートの取り違えを見分けるのに使うので、404 にも付いていること
        for (path in listOf("/health", "/stats", "/does-not-exist")) {
            val response = client.get(path)
            assertEquals(SERVER_HEADER_VALUE, response.headers[SERVER_HEADER], "path=$path")
        }
    }

    @Test
    fun `cors allows any origin by default`() = testApplication {
        application { module() }
        // /events は無限ストリームなのでここでは叩かない。CORS は
        // ルーティング前のプラグインなので、どのパスで確かめても同じ。
        val response = client.get("/stats") {
            header(HttpHeaders.Origin, "http://localhost:3000")
        }
        assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals(SERVER_HEADER, response.headers[HttpHeaders.AccessControlExposeHeaders])
    }

    @Test
    fun `cors can be narrowed to specific origins`() = testApplication {
        application { module("http://localhost:3000,https://app.example.com") }

        val allowed = client.get("/stats") {
            header(HttpHeaders.Origin, "http://localhost:3000")
        }
        assertEquals(
            "http://localhost:3000",
            allowed.headers[HttpHeaders.AccessControlAllowOrigin],
        )

        // 既定ポートのオリジンはポートを省略して送られてくる
        val allowedDefaultPort = client.get("/stats") {
            header(HttpHeaders.Origin, "https://app.example.com")
        }
        assertEquals(
            "https://app.example.com",
            allowedDefaultPort.headers[HttpHeaders.AccessControlAllowOrigin],
        )

        val rejected = client.get("/stats") {
            header(HttpHeaders.Origin, "http://evil.example.com")
        }
        assertEquals(HttpStatusCode.Forbidden, rejected.status)
    }

    @Test
    fun `cors can be turned off entirely`() = testApplication {
        application { module(allowOrigins = "") }
        val response = client.get("/stats") {
            header(HttpHeaders.Origin, "http://localhost:3000")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
    }
}
