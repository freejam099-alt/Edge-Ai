package com.google.ai.edge.gallery.customtasks.agentchat

import java.io.BufferedReader
import java.io.StringReader
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudSseParserTest {
  @Test
  fun readsMultilineDataAndIgnoresCommentsAndNonDataFields() {
    val events = mutableListOf<String>()
    val input =
      ": keep-alive\n" +
        "event: message\n" +
        "data: {\"text\":\n" +
        "data: \"hello\"}\n\n" +
        "id: 2\n" +
        "data: final-without-newline"

    readSseDataEvents(BufferedReader(StringReader(input)), AtomicBoolean(false), events::add)

    assertEquals(listOf("{\"text\":\n\"hello\"}", "final-without-newline"), events)
  }

  @Test
  fun doneSentinelIsDeliveredAsAnEventForCallerToHandle() {
    val events = mutableListOf<String>()
    readSseDataEvents(
      BufferedReader(StringReader("data: {\"ok\":true}\n\ndata: [DONE]\n\n")),
      AtomicBoolean(false),
      events::add,
    )

    assertEquals(listOf("{\"ok\":true}", "[DONE]"), events)
  }

  @Test
  fun parsesProviderErrorEventAsAnException() {
    val error =
      assertThrows(IllegalStateException::class.java) {
        throwIfStreamProviderError(JSONObject("{\"error\":{\"message\":\"rate limited\"}}"))
      }

    assertEquals("rate limited", error.message)
  }

  @Test
  fun malformedSseJsonFailsRatherThanSilentlyDroppingAChunk() {
    val error = assertThrows(IllegalStateException::class.java) { parseSseJson("not-json") }
    assertEquals(true, error.message!!.contains("malformed streaming data"))
  }
  @Test
  fun cancellationDoesNotLookLikeSuccessfulEof() {
    assertThrows(java.util.concurrent.CancellationException::class.java) {
      readSseDataEvents(BufferedReader(StringReader("")), AtomicBoolean(true)) {}
    }
  }

  @Test
  fun doneSentinelStopsReadingFurtherFrames() {
    val events = mutableListOf<String>()
    readSseDataEvents(
      BufferedReader(StringReader("data: [DONE]\n\ndata: ignored\n\n")),
      AtomicBoolean(false), events::add,
    )
    assertEquals(listOf("[DONE]"), events)
  }
}
