package jp.ikanoshiokara.sseclient.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.ikanoshiokara.sseclient.data.SseRepository
import jp.ikanoshiokara.sseclient.data.SseUpdate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** エミュレーターから見たホストマシンの SSE サーバー。 */
const val DEFAULT_SSE_URL = "http://10.0.2.2:8080/events"

sealed interface ConnectionStatus {
  data object Disconnected : ConnectionStatus

  data object Connecting : ConnectionStatus

  data object Connected : ConnectionStatus

  data class Reconnecting(val reason: String) : ConnectionStatus
}

/** 画面に表示する受信済みイベント。[seq] はリストのキー用の通し番号。 */
data class ReceivedEvent(
  val seq: Long,
  val receivedAt: String,
  val id: String?,
  val type: String?,
  val data: String,
)

data class MainScreenUiState(
  val url: String = DEFAULT_SSE_URL,
  val status: ConnectionStatus = ConnectionStatus.Disconnected,
  val events: List<ReceivedEvent> = emptyList(),
) {
  val isActive: Boolean
    get() = status != ConnectionStatus.Disconnected
}

class MainScreenViewModel(private val repository: SseRepository) : ViewModel() {

  private val _uiState = MutableStateFlow(MainScreenUiState())
  val uiState: StateFlow<MainScreenUiState> = _uiState.asStateFlow()

  private var connection: Job? = null
  private var seq = 0L

  fun onUrlChange(url: String) {
    _uiState.update { it.copy(url = url) }
  }

  fun onToggleConnection() {
    if (_uiState.value.isActive) disconnect() else connect()
  }

  fun onClearEvents() {
    _uiState.update { it.copy(events = emptyList()) }
  }

  private fun connect() {
    val url = _uiState.value.url.trim()
    connection =
      viewModelScope.launch {
        repository
          .stream(url)
          .onStart { setStatus(ConnectionStatus.Connecting) }
          .retryWhen { cause, _ ->
            // 切断・失敗したら一定間隔で無限に張り直す
            setStatus(ConnectionStatus.Reconnecting(cause.message ?: cause::class.java.simpleName))
            delay(RETRY_DELAY_MILLIS)
            true
          }
          .collect { update ->
            when (update) {
              SseUpdate.Open -> setStatus(ConnectionStatus.Connected)
              is SseUpdate.Event -> addEvent(update)
            }
          }
      }
  }

  private fun disconnect() {
    connection?.cancel()
    connection = null
    setStatus(ConnectionStatus.Disconnected)
  }

  private fun setStatus(status: ConnectionStatus) {
    _uiState.update { it.copy(status = status) }
  }

  private fun addEvent(update: SseUpdate.Event) {
    val received =
      ReceivedEvent(
        seq = ++seq,
        receivedAt = TIME_FORMAT.format(Date()),
        id = update.id,
        type = update.type,
        data = update.data,
      )
    _uiState.update { state ->
      // 新しい順に並べ、際限なく溜まらないよう上限を設ける
      state.copy(events = (listOf(received) + state.events).take(MAX_EVENTS))
    }
  }

  private companion object {
    const val RETRY_DELAY_MILLIS = 3_000L
    const val MAX_EVENTS = 200
    val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
  }
}
