package com.turnocontrol

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule

/** The startup compatibility gate runs on Dispatchers.IO, which Compose idling does not track. */
fun ComposeTestRule.awaitStartup() = waitUntil(15_000) { onAllNodes(hasText("Calendario")).fetchSemanticsNodes().isNotEmpty() }
