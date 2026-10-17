package com.google.ai.edge.gallery.customtasks.agentchat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AnthropicStreamAccumulatorTest {
  @Test fun textAndThinkingRemainSeparateWithSignaturePreserved() {
    val parser = AnthropicStreamAccumulator()
    val answers = StringBuilder()
    val thoughts = StringBuilder()
    listOf(
      """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
      """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"summary"}}""",
      """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"signed"}}""",
      """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
      """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"hello"}}""",
      """{"type":"message_stop"}""",
    ).forEach { parser.add(JSONObject(it), { answers.append(it) }, { thoughts.append(it) }) }
    assertEquals("hello", answers.toString())
    assertEquals(CLOUD_PROCESSING_STATUS, thoughts.toString())
    assertEquals("summary", parser.content().getJSONObject(0).getString("thinking"))
    assertEquals("signed", parser.content().getJSONObject(0).getString("signature"))
  }

  @Test fun fragmentedToolArgumentsAreReconstructed() {
    val parser = AnthropicStreamAccumulator()
    fun add(json: String) = parser.add(JSONObject(json), {}, {})
    add("""{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tool_1","name":"loadSkill","input":{}}}""")
    parser.add(JSONObject().put("type", "content_block_delta").put("index", 0).put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", "{\"skill")), {}, {})
    parser.add(JSONObject().put("type", "content_block_delta").put("index", 0).put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", "Name\":\"map\"}")), {}, {})
    add("""{"type":"content_block_stop","index":0}""")
    add("""{"type":"message_stop"}""")
    assertEquals("map", parser.content().getJSONObject(0).getJSONObject("input").getString("skillName"))
    assertEquals("tool_1", parser.content().getJSONObject(0).getString("id"))
  }

  @Test fun incompleteStreamIsNotSuccessful() {
    assertThrows(IllegalStateException::class.java) { AnthropicStreamAccumulator().content() }
  }

  @Test fun tokenLimitCannotExecuteIncompleteTool() {
    val parser = AnthropicStreamAccumulator()
    parser.add(JSONObject("""{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}"""), {}, {})
    parser.add(JSONObject("""{"type":"message_stop"}"""), {}, {})
    assertThrows(IllegalStateException::class.java) { parser.content() }
  }
  @Test fun nullTextAndThinkingNeverEmitNull() {
    val parser = AnthropicStreamAccumulator()
    val outputs = mutableListOf<String>()
    listOf(
      """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":null}}""",
      """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":null}}""",
      """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":null}}""",
      """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":null}}""",
    ).forEach { parser.add(JSONObject(it), { if (it.isNotEmpty()) outputs.add(it) }, { outputs.add(it) }) }
    assertEquals(emptyList<String>(), outputs)
  }
}
