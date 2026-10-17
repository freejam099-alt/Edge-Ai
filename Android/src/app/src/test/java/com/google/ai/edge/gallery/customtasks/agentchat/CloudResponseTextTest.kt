package com.google.ai.edge.gallery.customtasks.agentchat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CloudResponseTextTest {
  @Test fun nullAndNonTextValuesNeverBecomeVisibleText() {
    listOf("{}", """{"text":null}""", """{"text":"null"}""", """{"text":" NULL "}""", """{"text":{}}""", """{"text":[]}""", """{"text":123}""").forEach {
      assertEquals("", JSONObject(it).responseText("text"))
    }
    assertEquals("Halo", JSONObject("""{"text":"Halo"}""").responseText("text"))
  }

  @Test fun screenshotRegressionReasoningAndNullNeverLeakIntoAnswer() {
    val parser = OpenAiStreamAccumulator()
    val answer = StringBuilder()
    val statuses = mutableListOf<String>()
    listOf(
      """{"content":null,"reasoning_content":"private analysis"}""",
      """{"content":"Halo! ","reasoning_content":null}""",
      """{"content":"Ada yang bisa saya bantu?","reasoning":null}""",
      """{"content":null,"reasoning_content":null,"reasoning":null}""",
    ).forEach { delta ->
      parser.add(JSONObject("""{"choices":[{"delta":$delta}]}"""), { answer.append(it) }, { statuses.add(it) })
    }
    assertEquals("Halo! Ada yang bisa saya bantu?", answer.toString())
    assertEquals(answer.toString(), parser.result().text)
    assertEquals(listOf(CLOUD_PROCESSING_STATUS), statuses)
    assertEquals("private analysis", parser.result().reasoning)
    assertFalse(statuses.joinToString().contains("private analysis"))
  }

  @Test fun nullOnlyDeltasDoNotEmitAnything() {
    val parser = OpenAiStreamAccumulator()
    val emitted = mutableListOf<String>()
    parser.add(JSONObject("""{"choices":[{"delta":{"content":null,"reasoning":null,"reasoning_content":null}}]}"""), { emitted.add(it) }, { emitted.add(it) })
    assertEquals(emptyList<String>(), emitted)
    assertEquals("", parser.result().text)
    assertEquals("", parser.result().reasoning)
  }

  @Test fun toolFragmentsAndDeepSeekContinuationRemainIntact() {
    val parser = OpenAiStreamAccumulator()
    fun delta(value: JSONObject) = parser.add(JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("delta", value))), {}, {})
    delta(JSONObject("""{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"loadSkill","arguments":"{\"skill"}}]}"""))
    delta(JSONObject("""{"tool_calls":[{"index":0,"id":null,"function":{"name":null,"arguments":"Name\":\"map\"}"}}]}"""))
    val call = parser.result().toolCalls.single()
    assertEquals("call_1", call.id)
    assertEquals("loadSkill", call.name)
    assertEquals("map", JSONObject(call.arguments).getString("skillName"))
  }
}
