package com.google.ai.edge.gallery.customtasks.agentchat

import java.util.TreeMap
import org.json.JSONArray
import org.json.JSONObject

/** Retains original thinking signatures for the next Messages API tool round. */
internal class AnthropicStreamAccumulator {
  private val blocks = TreeMap<Int, JSONObject>()
  private val toolInputs = mutableMapOf<Int, StringBuilder>()
  var finished = false
    private set
  var stopReason = ""
    private set

  fun add(chunk: JSONObject, answer: (String) -> Unit, thinking: (String) -> Unit) {
    when (chunk.optString("type")) {
      "content_block_start" -> {
        val index = chunk.getInt("index")
        val block = JSONObject(chunk.getJSONObject("content_block").toString())
        blocks[index] = block
        when (block.optString("type")) {
          "tool_use" -> toolInputs[index] = StringBuilder()
          "text" -> block.responseText("text").takeIf { it.isNotEmpty() }?.let(answer)
          "thinking" -> block.responseText("thinking").takeIf { it.isNotEmpty() }?.let { thinking(CLOUD_PROCESSING_STATUS) }
        }
      }
      "content_block_delta" -> {
        val index = chunk.getInt("index")
        val block = blocks[index] ?: error("Provider sent a delta without a content block.")
        val delta = chunk.getJSONObject("delta")
        when (delta.optString("type")) {
          "text_delta" -> {
            val text = delta.responseText("text")
            block.put("text", block.responseText("text") + text)
            answer(text)
          }
          "thinking_delta" -> {
            val text = delta.responseText("thinking")
            block.put("thinking", block.responseText("thinking") + text)
            if (text.isNotBlank()) thinking(CLOUD_PROCESSING_STATUS)
          }
          "signature_delta" -> block.put("signature", block.responseText("signature") + delta.responseText("signature"))
          "input_json_delta" -> toolInputs.getOrPut(index) { StringBuilder() }.append(delta.responseText("partial_json"))
        }
      }
      "content_block_stop" -> {
        val index = chunk.getInt("index")
        toolInputs[index]?.toString()?.takeIf { it.isNotBlank() }?.let { blocks[index]?.put("input", JSONObject(it)) }
      }
      "message_delta" -> stopReason = chunk.optJSONObject("delta")?.optString("stop_reason").orEmpty()
      "message_stop" -> finished = true
    }
  }

  fun content(): JSONArray {
    check(finished) { "The provider stream ended before the message was complete. Please retry." }
    check(stopReason != "max_tokens") { "The provider reached its output limit. Try a shorter request." }
    return JSONArray(blocks.values.toList())
  }
}
