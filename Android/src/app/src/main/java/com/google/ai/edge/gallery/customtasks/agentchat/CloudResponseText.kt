package com.google.ai.edge.gallery.customtasks.agentchat

import java.util.TreeMap
import org.json.JSONObject

/** Read only actual text, never JSONObject.NULL, objects, arrays, or provider null sentinels. */
internal fun JSONObject.responseText(key: String): String =
  (opt(key) as? String)?.takeUnless { it.trim().equals("null", ignoreCase = true) }.orEmpty()

/** A UI status, not a provider's private reasoning or a generated reasoning summary. */
internal const val CLOUD_PROCESSING_STATUS = "Preparing response."

internal data class CloudToolCall(val id: String, val name: String, val arguments: String)
internal data class OpenAiCompletion(val text: String, val toolCalls: List<CloudToolCall>, val reasoning: String = "")

/** Only explicit, provider-designated summary fields are eligible for display. */
internal fun JSONObject.explicitProviderSummary(): String {
  responseText("reasoning_summary").takeIf { it.isNotEmpty() }?.let { return it }
  val content = optJSONArray("content") ?: return ""
  return buildString {
    for (index in 0 until content.length()) {
      val part = content.optJSONObject(index) ?: continue
      if (part.responseText("type") != "summary_text") continue
      append(part.responseText("text").ifEmpty { part.responseText("summary_text") })
    }
  }
}

internal class OpenAiStreamAccumulator {
  private class ToolCall {
    var id = ""
    val name = StringBuilder()
    val arguments = StringBuilder()
  }
  private val tools = TreeMap<Int, ToolCall>()
  private val text = StringBuilder()
  private val reasoning = StringBuilder()

  fun add(
    chunk: JSONObject,
    answer: (String) -> Unit,
    processing: (String) -> Unit,
    providerSummary: (String) -> Unit = {},
  ) {
    val delta = chunk.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: return
    delta.responseText("content").takeIf { it.isNotEmpty() }?.let {
      text.append(it)
      answer(it)
    }
    // DeepSeek requires this exact private field in subsequent tool rounds. Never send it to UI.
    val privateReasoning = delta.responseText("reasoning_content")
    reasoning.append(privateReasoning)
    if (privateReasoning.isNotBlank() || delta.responseText("reasoning").isNotBlank()) {
      processing(CLOUD_PROCESSING_STATUS)
    }
    delta.explicitProviderSummary().takeIf { it.isNotEmpty() }?.let(providerSummary)
    val calls = delta.optJSONArray("tool_calls") ?: return
    for (index in 0 until calls.length()) {
      val item = calls.optJSONObject(index) ?: continue
      val target = tools.getOrPut(item.optInt("index", index)) { ToolCall() }
      item.responseText("id").takeIf { it.isNotEmpty() }?.let { target.id = it }
      val function = item.optJSONObject("function") ?: continue
      target.name.append(function.responseText("name"))
      target.arguments.append(function.responseText("arguments"))
    }
  }

  fun result() = OpenAiCompletion(
    text = text.toString(),
    reasoning = reasoning.toString(),
    toolCalls = tools.values.map {
      CloudToolCall(it.id.ifBlank { "call_${System.nanoTime()}" }, it.name.toString(), it.arguments.toString().ifBlank { "{}" })
    },
  )
}
