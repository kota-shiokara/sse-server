package com.example.sse

import com.example.sse.api.EventRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class BroadcasterTest {

    @Test
    fun `publishText auto increments id`() = runTest {
        val broadcaster = Broadcaster()
        assertEquals(0, broadcaster.lastEventId)
        assertEquals("1", broadcaster.publishText("a"))
        assertEquals("2", broadcaster.publishText("b"))
        assertEquals(2, broadcaster.lastEventId)
    }

    @Test
    fun `subscribers receive published frames`() = runTest {
        val broadcaster = Broadcaster()
        val received = mutableListOf<StreamSignal>()

        val job = launch { received += broadcaster.signals.take(2).toList() }
        yield() // 購読が始まるのを待つ

        broadcaster.publishText("first")
        broadcaster.publish(EventRequest(data = "second").toServerSentEvent(broadcaster::nextId))
        job.join()

        val frames = received.map { (it as StreamSignal.Frame).event }
        assertEquals(listOf("first", "second"), frames.map { it.data })
        assertEquals(listOf("1", "2"), frames.map { it.id })
    }

    @Test
    fun `disconnectAll emits close and reports subscriber count`() = runTest {
        val broadcaster = Broadcaster()
        val received = mutableListOf<StreamSignal>()

        val job = launch { received += broadcaster.signals.take(1).toList() }
        yield()

        assertEquals(1, broadcaster.disconnectAll())
        job.join()

        assertEquals(StreamSignal.Close, received.single())
    }

    @Test
    fun `disconnectAll with no subscribers reports zero`() = runTest {
        assertEquals(0, Broadcaster().disconnectAll())
    }

    @Test
    fun `comment only request produces no event name or id`() {
        val broadcaster = Broadcaster()
        val frame = EventRequest(comment = "ping").toServerSentEvent(broadcaster::nextId)

        assertNull(frame.data)
        assertNull(frame.event)
        assertNull(frame.id)
        assertEquals("ping", frame.comments)
        // コメントだけのフレームでは ID を消費しない
        assertEquals(0, broadcaster.lastEventId)
    }

    @Test
    fun `explicit id is used as is and empty id is omitted`() {
        val broadcaster = Broadcaster()

        assertEquals("custom", EventRequest(data = "x", id = "custom").toServerSentEvent(broadcaster::nextId).id)
        assertNull(EventRequest(data = "x", id = "").toServerSentEvent(broadcaster::nextId).id)
        assertEquals("1", EventRequest(data = "x").toServerSentEvent(broadcaster::nextId).id)
    }

    @Test
    fun `empty event name is omitted`() {
        val broadcaster = Broadcaster()

        assertEquals("message", EventRequest(data = "x").toServerSentEvent(broadcaster::nextId).event)
        assertEquals("custom", EventRequest(data = "x", event = "custom").toServerSentEvent(broadcaster::nextId).event)
        assertNull(EventRequest(data = "x", event = "").toServerSentEvent(broadcaster::nextId).event)
    }
}
