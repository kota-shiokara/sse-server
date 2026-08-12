package com.example.sseclient.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sseclient.data.OkHttpSseRepository
import com.example.sseclient.theme.SSEClientTheme

@Composable
fun MainScreen(
  modifier: Modifier = Modifier,
  viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(OkHttpSseRepository()) },
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  MainScreen(
    state = state,
    onUrlChange = viewModel::onUrlChange,
    onToggleConnection = viewModel::onToggleConnection,
    onClearEvents = viewModel::onClearEvents,
    modifier = modifier,
  )
}

@Composable
internal fun MainScreen(
  state: MainScreenUiState,
  onUrlChange: (String) -> Unit = {},
  onToggleConnection: () -> Unit = {},
  onClearEvents: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    OutlinedTextField(
      value = state.url,
      onValueChange = onUrlChange,
      label = { Text("SSE endpoint") },
      singleLine = true,
      // 接続中に URL を変えても反映されないので、切断中のみ編集可能にする
      enabled = !state.isActive,
      modifier = Modifier.fillMaxWidth(),
    )

    Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Button(onClick = onToggleConnection) {
        Text(if (state.isActive) "Disconnect" else "Connect")
      }
      OutlinedButton(onClick = onClearEvents, enabled = state.events.isNotEmpty()) { Text("Clear") }
    }

    StatusLine(state.status)

    HorizontalDivider()

    Text(
      text = "received: ${state.events.size}",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      items(state.events, key = { it.seq }) { event -> EventCard(event) }
    }
  }
}

@Composable
private fun StatusLine(status: ConnectionStatus, modifier: Modifier = Modifier) {
  val (label, color) =
    when (status) {
      ConnectionStatus.Disconnected -> "disconnected" to MaterialTheme.colorScheme.onSurfaceVariant
      ConnectionStatus.Connecting -> "connecting…" to MaterialTheme.colorScheme.onSurfaceVariant
      ConnectionStatus.Connected -> "connected" to Color(0xFF2E7D32)
      is ConnectionStatus.Reconnecting ->
        "reconnecting… (${status.reason})" to MaterialTheme.colorScheme.error
    }
  Text(
    text = label,
    color = color,
    style = MaterialTheme.typography.bodyMedium,
    fontWeight = FontWeight.Medium,
    modifier = modifier,
  )
}

@Composable
private fun EventCard(event: ReceivedEvent, modifier: Modifier = Modifier) {
  Card(modifier = modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(12.dp)) {
      Text(
        text =
          buildString {
            append(event.receivedAt)
            event.type?.let { append("  event=$it") }
            event.id?.let { append("  id=$it") }
          },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = event.data,
        style = MaterialTheme.typography.bodyLarge,
        fontFamily = FontFamily.Monospace,
      )
    }
  }
}

private val PREVIEW_STATE =
  MainScreenUiState(
    status = ConnectionStatus.Connected,
    events =
      listOf(
        ReceivedEvent(2, "12:34:57", id = "2", type = "message", data = "second"),
        ReceivedEvent(1, "12:34:56", id = "1", type = "message", data = "hello sse"),
      ),
  )

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
  SSEClientTheme { MainScreen(PREVIEW_STATE, modifier = Modifier.padding(16.dp)) }
}

@Preview(showBackground = true, widthDp = 340)
@Composable
fun MainScreenDisconnectedPreview() {
  SSEClientTheme {
    MainScreen(MainScreenUiState(), modifier = Modifier.padding(16.dp))
  }
}
