package com.example.sse

import com.example.sse.api.EventRequest
import io.ktor.sse.ServerSentEvent

/**
 * [EventRequest] を SSE フレームへ変換する。
 *
 * data が無い場合はコメント / retry のみのフレームとして扱う。
 * data が空のイベントは仕様上クライアントで破棄されるため、
 * event 名と id は付けない。
 *
 * 契約 (shared) ではなくサーバー側の関心なので、ここに置く。
 */
fun EventRequest.toServerSentEvent(nextId: () -> String): ServerSentEvent {
    if (data == null) {
        return ServerSentEvent(retry = retry, comments = comment)
    }
    val requestedId = id
    return ServerSentEvent(
        data = data,
        event = event?.takeIf { it.isNotEmpty() },
        id = when {
            requestedId == null -> nextId()
            requestedId.isEmpty() -> null
            else -> requestedId
        },
        retry = retry,
        comments = comment,
    )
}
