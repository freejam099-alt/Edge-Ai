package com.google.ai.edge.gallery.customtasks.agentchat

/** Suppresses explicit private-reasoning blocks, even when tags cross SSE token boundaries. */
internal class CloudAnswerFilter(private val emit: (String) -> Unit) {
  private val pending = StringBuilder()
  private var hiddenDepth = 0
  private val tags = listOf("<think>", "</think>", "<thinking>", "</thinking>", "<analysis>", "</analysis>")

  fun add(text: String) {
    val visible = StringBuilder()
    text.forEach { char ->
      if (pending.isEmpty() && char != '<') {
        if (hiddenDepth == 0) visible.append(char)
      } else {
        pending.append(char)
        val candidate = pending.toString().lowercase()
        if (candidate in tags) {
          if (candidate.startsWith("</")) hiddenDepth = (hiddenDepth - 1).coerceAtLeast(0)
          else hiddenDepth++
          pending.clear()
        } else if (tags.none { it.startsWith(candidate) }) {
          if (hiddenDepth == 0) visible.append(pending)
          pending.clear()
        }
      }
    }
    if (visible.isNotEmpty()) emit(visible.toString())
  }

  fun finish() {
    // An unfinished reasoning tag or block is intentionally discarded, not exposed at EOF.
    pending.clear()
  }
}
