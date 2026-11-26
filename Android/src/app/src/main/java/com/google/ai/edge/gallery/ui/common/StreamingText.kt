package com.google.ai.edge.gallery.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val BASE_INTERVAL_MS = 40L
private const val MAX_INTERVAL_MS = 140L
private const val CHARS_PER_EXTRA_MS = 16

/**
 * Returns [text] throttled while [inProgress] is true.
 *
 * Markdown is re-parsed and re-laid-out from scratch on every change, so rendering each streamed
 * token makes the cost grow with the message length and starves the UI thread. While streaming this
 * publishes the newest text at a bounded rate (slower for longer messages) so frames stay
 * smooth. The moment streaming ends, the exact final [text] is returned with no delay.
 */
@Composable
fun rememberStreamingText(text: String, inProgress: Boolean): String {
  val latest by rememberUpdatedState(text)
  var shown by remember { mutableStateOf(text) }
  LaunchedEffect(inProgress) {
    if (inProgress) {
      while (isActive) {
        shown = latest
        delay((BASE_INTERVAL_MS + latest.length / CHARS_PER_EXTRA_MS).coerceAtMost(MAX_INTERVAL_MS))
      }
    } else {
      shown = latest
    }
  }
  return if (inProgress) shown else text
}
