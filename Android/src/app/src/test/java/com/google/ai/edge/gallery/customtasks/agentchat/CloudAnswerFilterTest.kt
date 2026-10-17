package com.google.ai.edge.gallery.customtasks.agentchat

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudAnswerFilterTest {
  private fun filtered(parts: List<String>): String {
    val output = StringBuilder()
    val filter = CloudAnswerFilter { output.append(it) }
    parts.forEach(filter::add)
    filter.finish()
    return output.toString()
  }

  @Test fun reasoningTagsNeverLeakAtAnyTokenBoundary() {
    listOf("think", "thinking", "analysis").forEach { tag ->
      val input = "<$tag>private internal analysis</$tag>Halo!"
      for (split in 0..input.length) {
        assertEquals("Halo!", filtered(listOf(input.take(split), input.drop(split))))
      }
      assertEquals("Halo!", filtered(input.map { it.toString() }))
    }
  }

  @Test fun incompleteReasoningIsDiscarded() {
    assertEquals("", filtered(listOf("<thi")))
    assertEquals("", filtered(listOf("<think>private internal analysis")))
  }

  @Test fun ordinaryTextAndMarkupArePreserved() {
    assertEquals("Halo! 2 < 3. <b>OK</b>", filtered(listOf("Halo! 2 < 3. <b>OK</b>")))
  }

  @Test fun answersOnBothSidesAndNestedReasoningAreHandled() {
    assertEquals("BeforeAfter", filtered(listOf("Before<THINK>one<analysis>two</analysis>three</THINK>After")))
  }
}
