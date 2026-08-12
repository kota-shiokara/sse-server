package jp.ikanoshiokara.sseclient.data

import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** 実際の `text/event-stream` を読ませて、SSE のパース結果を確認する。 */
class OkHttpSseRepositoryTest {

  private lateinit var server: MockWebServer

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun stream_parsesServerSentEvents() = runTest {
    server.enqueue(
      MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
          """
          event: open
          data: connected

          : keep-alive

          event: message
          data: hello sse
          id: 1

          """
            .trimIndent()
            .plus("\n")
        )
    )

    val updates =
      OkHttpSseRepository()
        .stream(server.url("/events").toString())
        .take(3) // Open + イベント 2 件 (コメント行は無視される)
        .toList()

    assertEquals(SseUpdate.Open, updates[0])
    assertEquals("connected", (updates[1] as SseUpdate.Event).data)
    assertEquals("open", (updates[1] as SseUpdate.Event).type)
    assertEquals("hello sse", (updates[2] as SseUpdate.Event).data)
    assertEquals("message", (updates[2] as SseUpdate.Event).type)
    assertEquals("1", (updates[2] as SseUpdate.Event).id)

    assertEquals("text/event-stream", server.takeRequest().getHeader("Accept"))
  }
}
