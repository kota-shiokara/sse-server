package jp.ikanoshiokara.sse

import io.ktor.sse.ServerSentEvent
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** 接続中の SSE セッションへ送る指示。 */
sealed interface StreamSignal {
    /** このフレームを送出する。 */
    data class Frame(val event: ServerSentEvent) : StreamSignal

    /** ストリームを終了する（クライアントから見ると切断）。 */
    data object Close : StreamSignal
}

/**
 * 接続中の全 SSE セッションへ指示を配信するためのハブ。
 *
 * SharedFlow を 1 本持ち、各セッションがそれを collect する。
 * 遅いクライアントがいてもサーバー全体が詰まらないよう、
 * バッファが溢れたら古いものから捨てる。
 */
class Broadcaster {
    private val lastId = AtomicLong(0)

    private val _signals = MutableSharedFlow<StreamSignal>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val signals: SharedFlow<StreamSignal> = _signals

    /** 接続中のセッション数。 */
    val subscriberCount: Int
        get() = _signals.subscriptionCount.value

    /** 直近に自動採番したイベント ID。まだ採番していなければ 0。 */
    val lastEventId: Long
        get() = lastId.get()

    /** 自動採番の次の ID を払い出す。 */
    fun nextId(): String = lastId.incrementAndGet().toString()

    /** 組み立て済みのフレームを全セッションへ送る。 */
    fun publish(event: ServerSentEvent) {
        _signals.tryEmit(StreamSignal.Frame(event))
    }

    /** data だけを持つ標準的なイベントを送り、採番した ID を返す。 */
    fun publishText(data: String, event: String = "message"): String {
        val id = nextId()
        publish(ServerSentEvent(data = data, event = event, id = id))
        return id
    }

    /** 接続中の全セッションを切断する。切断対象だったセッション数を返す。 */
    fun disconnectAll(): Int {
        val count = subscriberCount
        _signals.tryEmit(StreamSignal.Close)
        return count
    }
}
