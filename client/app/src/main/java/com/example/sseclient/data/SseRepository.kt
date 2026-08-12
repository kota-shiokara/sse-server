package com.example.sseclient.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/** SSE ストリームから届く更新。 */
sealed interface SseUpdate {
  /** 接続が確立した。 */
  data object Open : SseUpdate

  /** イベントを 1 件受信した。 */
  data class Event(val id: String?, val type: String?, val data: String) : SseUpdate
}

interface SseRepository {
  /**
   * [url] の SSE ストリームへ接続し、届いた更新を流す。
   *
   * 接続が切れた場合は例外で終了する。再接続は呼び出し側の責務。
   */
  fun stream(url: String): Flow<SseUpdate>
}

class OkHttpSseRepository(
  private val client: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      // ストリームは開いたままなので読み取りタイムアウトは無効にする
      .readTimeout(0, TimeUnit.MILLISECONDS)
      .build()
) : SseRepository {

  override fun stream(url: String): Flow<SseUpdate> = callbackFlow {
    val request = Request.Builder().url(url).header("Accept", "text/event-stream").build()

    val listener =
      object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
          trySend(SseUpdate.Open)
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
          trySend(SseUpdate.Event(id = id, type = type, data = data))
        }

        override fun onClosed(eventSource: EventSource) {
          // サーバー側から切られた。上位で再接続させたいので例外扱いにする。
          close(IOException("stream closed by server"))
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
          close(t ?: IOException("connection failed (HTTP ${response?.code})"))
        }
      }

    val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
    awaitClose { eventSource.cancel() }
  }

  private companion object {
    const val CONNECT_TIMEOUT_SECONDS = 10L
  }
}
