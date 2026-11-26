package com.google.ai.edge.gallery.ui.common

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Markdown text for streamed responses.
 *
 * Previously this cross-faded two full markdown layers inside an offscreen buffer on every update,
 * which doubled layout work per token. It now renders a single layer at a bounded rate (see
 * [rememberStreamingText]); the final text is shown immediately once streaming ends.
 */
@Composable
fun BufferedFadingMarkdownText(text: String, inProgress: Boolean, modifier: Modifier = Modifier) {
  val shown = rememberStreamingText(text, inProgress)
  SelectionContainer { MarkdownText(text = shown, modifier = modifier) }
}
