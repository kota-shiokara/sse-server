package jp.ikanoshiokara.sseclient.ui.main

import jp.ikanoshiokara.sseclient.data.SseRepository
import jp.ikanoshiokara.sseclient.data.SseUpdate
import java.io.IOException
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainScreenViewModelTest {

  private val dispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun initialState_isDisconnected() {
    val viewModel = MainScreenViewModel(FakeSseRepository())
    assertEquals(ConnectionStatus.Disconnected, viewModel.uiState.value.status)
    assertEquals(DEFAULT_SSE_URL, viewModel.uiState.value.url)
  }

  @Test
  fun connect_collectsReceivedEvents() = runTest(dispatcher) {
    val repository =
      FakeSseRepository(
        flow {
          emit(SseUpdate.Open)
          emit(SseUpdate.Event(id = "1", type = "message", data = "hello"))
          emit(SseUpdate.Event(id = "2", type = "message", data = "world"))
        }
      )
    val viewModel = MainScreenViewModel(repository)

    viewModel.onToggleConnection()
    advanceUntilIdle()

    val state = viewModel.uiState.value
    assertEquals(ConnectionStatus.Connected, state.status)
    // 新しい順に並ぶ
    assertEquals(listOf("world", "hello"), state.events.map { it.data })
  }

  @Test
  fun connect_retriesAfterFailure() = runTest(dispatcher) {
    var attempts = 0
    val repository =
      FakeSseRepository(
        flow {
          attempts++
          if (attempts == 1) throw IOException("boom")
          emit(SseUpdate.Open)
        }
      )
    val viewModel = MainScreenViewModel(repository)

    viewModel.onToggleConnection()
    advanceUntilIdle()

    assertEquals(2, attempts)
    assertEquals(ConnectionStatus.Connected, viewModel.uiState.value.status)
  }

  @Test
  fun disconnect_stopsAndResetsStatus() = runTest(dispatcher) {
    val viewModel = MainScreenViewModel(FakeSseRepository(flow { emit(SseUpdate.Open) }))

    viewModel.onToggleConnection()
    advanceUntilIdle()
    assertTrue(viewModel.uiState.value.isActive)

    viewModel.onToggleConnection()
    advanceUntilIdle()
    assertEquals(ConnectionStatus.Disconnected, viewModel.uiState.value.status)
  }

  @Test
  fun clearEvents_emptiesList() = runTest(dispatcher) {
    val viewModel =
      MainScreenViewModel(
        FakeSseRepository(flow { emit(SseUpdate.Event(id = null, type = null, data = "x")) })
      )

    viewModel.onToggleConnection()
    advanceUntilIdle()
    assertEquals(1, viewModel.uiState.value.events.size)

    viewModel.onClearEvents()
    assertEquals(0, viewModel.uiState.value.events.size)
  }
}

private class FakeSseRepository(private val updates: Flow<SseUpdate> = flow {}) : SseRepository {
  override fun stream(url: String): Flow<SseUpdate> = updates
}
