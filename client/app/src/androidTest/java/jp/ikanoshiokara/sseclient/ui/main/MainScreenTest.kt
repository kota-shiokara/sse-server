package jp.ikanoshiokara.sseclient.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** UI tests for [jp.ikanoshiokara.sseclient.ui.main.MainScreen]. */
class MainScreenTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Before
  fun setup() {
    composeTestRule.setContent { MainScreen(FAKE_STATE) }
  }

  @Test
  fun receivedEvents_areDisplayed() {
    FAKE_STATE.events.forEach { composeTestRule.onNodeWithText(it.data).assertExists() }
  }

  @Test
  fun connectedStatus_isDisplayed() {
    composeTestRule.onNodeWithText("connected").assertExists()
  }

  @Test
  fun disconnectButton_isShownWhileActive() {
    composeTestRule.onNodeWithText("Disconnect").assertExists()
  }
}

private val FAKE_STATE =
  MainScreenUiState(
    status = ConnectionStatus.Connected,
    events =
      listOf(
        ReceivedEvent(2, "12:34:57", id = "2", type = "message", data = "Sample2"),
        ReceivedEvent(1, "12:34:56", id = "1", type = "message", data = "Sample1"),
      ),
  )
