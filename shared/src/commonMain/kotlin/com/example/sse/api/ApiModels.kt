package com.example.sse.api

import kotlinx.serialization.Serializable

/**
 * サーバーが全レスポンスに付ける識別ヘッダ。
 *
 * ポートを別のプロセスが使っている場合に、CLI 側が「応答しているのは
 * sse-server ではない」と判定できるようにするためのもの。
 * WebAPI の一部なので契約としてここに置く。
 */
const val SERVER_HEADER = "X-Sse-Mock"
const val SERVER_HEADER_VALUE = "sse-server"

/**
 * 送信したい 1 ステップの指定。`POST /publish` のボディと、
 * `POST /scenario` の各ステップの両方で使う。
 *
 * フィールドを省略した場合と空文字を渡した場合で意味が違うものがある:
 * - [event] 省略 → `event: message`、`""` → event 行を送らない
 * - [id] 省略 → 自動採番、`""` → id 行を送らない
 */
@Serializable
data class EventRequest(
    val event: String? = "message",
    val data: String? = null,
    val id: String? = null,
    val retry: Long? = null,
    val comment: String? = null,
    /** 送信回数。 */
    val repeat: Int = 1,
    /** 2 回目以降の送信間隔 (ms)。 */
    val intervalMs: Long = 0,
    /** 送信を始めるまでの待ち時間 (ms)。 */
    val delayMs: Long = 0,
    /** true なら送信ではなく全セッションの切断を行う。 */
    val disconnect: Boolean = false,
) {
    /** 問題があればエラーメッセージ、無ければ null。 */
    fun validate(): String? = when {
        repeat < 1 -> "repeat must be >= 1"
        intervalMs < 0 || delayMs < 0 -> "intervalMs and delayMs must be >= 0"
        disconnect -> null
        data == null && comment == null && retry == null ->
            "one of data, comment or retry is required"
        else -> null
    }
}

@Serializable
data class ScenarioRequest(val name: String? = null, val steps: List<EventRequest>)

@Serializable
data class PublishResponse(val scheduled: Int, val subscribers: Int)

@Serializable
data class ScenarioResponse(val name: String?, val steps: Int, val subscribers: Int)

@Serializable
data class DisconnectResponse(val disconnected: Int)

@Serializable
data class StatsResponse(val subscribers: Int, val lastEventId: Long)

@Serializable
data class ErrorResponse(val error: String)
