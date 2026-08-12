package com.example.sse.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

class EventRequestTest {

    @Test
    fun `validate rejects incomplete requests`() {
        assertEquals("repeat must be >= 1", EventRequest(data = "x", repeat = 0).validate())
        assertEquals(
            "intervalMs and delayMs must be >= 0",
            EventRequest(data = "x", delayMs = -1).validate(),
        )
        assertEquals(
            "one of data, comment or retry is required",
            EventRequest().validate(),
        )
    }

    @Test
    fun `validate accepts anything with something to send`() {
        assertNull(EventRequest(data = "x").validate())
        assertNull(EventRequest(comment = "x").validate())
        assertNull(EventRequest(retry = 1000).validate())
        assertNull(EventRequest(disconnect = true).validate())
    }

    @Test
    fun `omitted fields fall back to the documented defaults`() {
        val parsed = Json.decodeFromString<EventRequest>("""{"data":"hello"}""")

        assertEquals("message", parsed.event)
        assertNull(parsed.id)
        assertEquals(1, parsed.repeat)
        assertEquals(0, parsed.intervalMs)
        assertEquals(0, parsed.delayMs)
    }

    @Test
    fun `an unknown field is a hard error rather than being ignored`() {
        // サーバーは既定の Json 設定 (ignoreUnknownKeys = false) で受けるので、
        // CLI がタイポしたキーを送ったら 400 になる。その前提を固定する。
        val result = runCatching {
            Json.decodeFromString<EventRequest>("""{"data":"x","delay":100}""")
        }
        assertEquals(true, result.isFailure)
    }
}
