/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import com.google.ai.edge.gallery.agent.AgentEvent
import com.google.ai.edge.gallery.agent.AgentExecutionContext
import com.google.ai.edge.gallery.agent.AgentRequest
import com.google.ai.edge.gallery.agent.AgentRuntimeConfig
import com.google.ai.edge.gallery.agent.AgentRuntimeExecutor
import com.google.ai.edge.gallery.agent.AgentResponse
import com.google.ai.edge.gallery.agent.sessions.LlmSessionManager
import com.google.ai.edge.gallery.agent.sessions.generateSessionId
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.tools.CallJsSkillResultImage
import com.google.ai.edge.gallery.tools.ToolExecutionContext
import com.google.ai.edge.gallery.tools.RuntimeToolDispatcher
import com.google.ai.edge.gallery.ui.home.CLOUD_THINKING_DEFAULT
import com.google.ai.edge.gallery.ui.home.CLOUD_THINKING_HIGH
import com.google.ai.edge.gallery.ui.home.CLOUD_THINKING_LOW
import com.google.ai.edge.gallery.ui.home.CLOUD_THINKING_MEDIUM
import com.google.ai.edge.gallery.ui.home.CLOUD_PROVIDER_GEMINI
import com.google.ai.edge.gallery.ui.home.CloudProviderConfig
import com.google.ai.edge.gallery.ui.home.loadCloudProviderConfig
import com.google.ai.edge.gallery.ui.home.supportsCloudThinkingEffort
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_TOOL_ROUNDS = 8
private const val OPENAI_CHAT_PATH = "/chat/completions"
private const val OPENAI_IMAGES_PATH = "/images/generations"

private data class CloudFunction(
  val name: String,
  val description: String,
  val parameters: Map<String, String>,
) {
  val required: List<String> get() = parameters.keys.toList()
}

private val cloudFunctions =
  listOf(
    CloudFunction("loadSkill", "Loads the full instructions for a named skill.", mapOf("skillName" to "The exact skill name to load.")),
    CloudFunction(
      "runJs",
      "Runs a JavaScript skill after its instructions have been loaded.",
      mapOf(
        "skillName" to "The skill name.",
        "scriptName" to "The script file name, usually index.html.",
        "data" to "JSON input for the script, or an empty string.",
      ),
    ),
    CloudFunction(
      "runMcpTool",
      "Runs an enabled MCP tool. Ask the user for permission when the app prompts.",
      mapOf("toolName" to "The exact MCP tool name.", "input" to "The JSON input for that tool."),
    ),
    CloudFunction(
      "runIntent",
      "Runs a supported Android action. The app will request permissions when needed.",
      mapOf("intent" to "The exact supported action name.", "parameters" to "JSON parameters for the action."),
    ),
    CloudFunction(
      "createImage",
      "Generates one image with the separately configured image model when the user explicitly asks for an image.",
      mapOf("prompt" to "A detailed visual description of the image to generate."),
    ),
  )

/** Cloud-backed Agent Skills runtime; device-local model engines are not used by this executor. */
class CloudAgentRuntimeExecutor(
  @ApplicationContext private val appContext: Context,
  private val dataStoreRepository: DataStoreRepository,
  private val llmSessionManager: LlmSessionManager,
  private val agentTools: AgentTools,
) : AgentRuntimeExecutor {
  private val activeConfig = AtomicReference<AgentRuntimeConfig?>(null)
  private val activeConnection = AtomicReference<HttpURLConnection?>(null)
  private val activeRequestCancelled = AtomicReference<AtomicBoolean?>(null)
  private val interrupted = AtomicBoolean(false)
  private val toolDispatcher = RuntimeToolDispatcher()

  override val activeSessionId: String?
    get() = llmSessionManager.activeSessionId

  override suspend fun initialize(
    context: Context,
    config: AgentRuntimeConfig,
    onDone: (errorMsg: String) -> Unit,
  ) {
    val cloudConfig = loadCloudProviderConfig(appContext, dataStoreRepository)
    if (!cloudConfig.isConfigured) {
      activeConfig.set(null)
      onDone("Cloud settings are incomplete. Add a provider, model ID, and API key in settings.")
      return
    }
    val sessionId = config.sessionId.ifBlank { llmSessionManager.activeSessionId ?: generateSessionId() }
    llmSessionManager.activeSessionId = sessionId
    activeConfig.set(config.copy(sessionId = sessionId))
    toolDispatcher.setupExecutionContext(
      tools = agentTools.getAvailableTools(),
      context = ToolExecutionContext(taskId = config.taskId, actionChannel = config.actionChannel),
    )
    onDone("")
  }

  override fun executeStream(context: AgentExecutionContext, request: AgentRequest): Flow<AgentEvent> =
    channelFlow {
      send(AgentEvent.LoopInitiated(request))
      interrupted.set(false)
      val requestCancelled = AtomicBoolean(false)
      activeRequestCancelled.getAndSet(requestCancelled)?.set(true)
      activeConnection.getAndSet(null)?.disconnect()
      val config = activeConfig.get()
      if (config == null) {
        send(AgentEvent.Error("Cloud model is not ready. Open Cloud model settings and save a valid API key."))
        return@channelFlow
      }
      val providerConfig = loadCloudProviderConfig(appContext, dataStoreRepository)
      if (!providerConfig.isConfigured) {
        send(AgentEvent.Error("Cloud settings are incomplete. Add a provider, model ID, and API key in settings."))
        return@channelFlow
      }
      if (request.attachments.any { it is com.google.ai.edge.gallery.agent.Attachment.AudioBytes || it is com.google.ai.edge.gallery.agent.Attachment.AudioUri }) {
        send(AgentEvent.Error("Cloud chat currently supports text and images. Remove the audio attachment and try again."))
        return@channelFlow
      }

      val finalResponse = StringBuilder()
      val streamAnswer: (String) -> Unit = { token ->
        if (token.isNotEmpty() && !requestCancelled.get()) {
          finalResponse.append(token)
          trySend(AgentEvent.StreamToken(token = token))
        }
      }
      // Only provider-exposed thought/reasoning summaries reach this callback; never infer or
      // synthesize hidden chain-of-thought from the answer text.
      val streamThinking: (String) -> Unit = { thought ->
        if (thought.isNotEmpty() && !requestCancelled.get()) {
          trySend(AgentEvent.StreamToken(token = "", thinking = thought))
        }
      }

      try {
        val history = readHistory(request.metadata[AgentRequest.CLOUD_HISTORY])
        val imageParts = encodeImages(request.attachments)
        withContext(Dispatchers.IO) {
          when (providerConfig.protocol) {
            "gemini" ->
              runGemini(providerConfig, config.systemInstruction.orEmpty(), history, request.query, imageParts, requestCancelled, streamAnswer, streamThinking)
            "openai" ->
              runOpenAiCompatible(providerConfig, config.systemInstruction.orEmpty(), history, request.query, imageParts, requestCancelled, streamAnswer, streamThinking)
            "anthropic" ->
              runAnthropic(providerConfig, config.systemInstruction.orEmpty(), history, request.query, imageParts, requestCancelled, streamAnswer, streamThinking)
            else -> error("Unsupported API protocol. Choose OpenAI, Gemini, or Anthropic in settings.")
          }
        }
        if (requestCancelled.get()) {
          send(AgentEvent.LoopCancelled)
        } else {
          send(AgentEvent.StreamToken(token = "", done = true))
          send(AgentEvent.LoopTerminated(finalResponse.toString()))
        }
      } catch (e: CancellationException) {
        if (requestCancelled.get()) send(AgentEvent.LoopCancelled) else throw e
      } catch (e: Exception) {
        if (requestCancelled.get()) {
          send(AgentEvent.LoopCancelled)
        } else {
          send(AgentEvent.Error(e.message ?: "Cloud request failed. Check the provider settings and network connection."))
        }
      } finally {
        activeRequestCancelled.compareAndSet(requestCancelled, null)
      }
    }

  private fun runOpenAiCompatible(
    config: CloudProviderConfig,
    systemPrompt: String,
    history: List<Pair<String, String>>,
    query: String,
    images: List<Pair<String, String>>,
    requestCancelled: AtomicBoolean,
    streamAnswer: (String) -> Unit,
    streamThinking: (String) -> Unit,
  ) {
    val messages = JSONArray()
    if (systemPrompt.isNotBlank()) {
      messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
    }
    history.forEach { (role, content) ->
      messages.put(JSONObject().put("role", role).put("content", content))
    }
    messages.put(openAiUserMessage(query, images))
    val tools = openAiTools()

    repeat(MAX_TOOL_ROUNDS) {
      check(!requestCancelled.get()) { "Request cancelled" }
      val body =
        JSONObject()
          .put("model", config.modelId)
          .put("messages", messages)
          .put("tools", tools)
          .put("tool_choice", "auto")
          .put("stream", true)
      if (
        config.thinkingEffort != CLOUD_THINKING_DEFAULT &&
          supportsCloudThinkingEffort(if (config.protocol == "gemini") CLOUD_PROVIDER_GEMINI else config.providerId, config.modelId) &&
          !config.modelId.trim().lowercase().contains("deepseek-r1")
      ) {
        body.put("reasoning_effort", config.thinkingEffort)
      }
      val completion = openAiRequest(config, body, requestCancelled, streamAnswer, streamThinking)
      if (completion.toolCalls.isEmpty()) return
      val callsArray = JSONArray()
      completion.toolCalls.forEachIndexed { index, call ->
        val callObject =
          JSONObject()
            .put("index", index)
            .put("id", call.id)
            .put("type", "function")
            .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments))
        callsArray.put(callObject)
      }
      messages.put(
        JSONObject()
          .put("role", "assistant")
          .put("content", completion.text.ifEmpty { JSONObject.NULL })
          .put("tool_calls", callsArray)
          .apply {
            if (completion.reasoning.isNotBlank() && (config.providerId == "deepseek" || config.modelId.contains("deepseek", ignoreCase = true))) {
              put("reasoning_content", completion.reasoning)
            }
          }
      )
      completion.toolCalls.forEach { call ->
        val result = invokeTool(call.name, call.arguments, config)
        messages.put(
          JSONObject()
            .put("role", "tool")
            .put("tool_call_id", call.id)
            .put("content", result),
        )
      }
    }
    error("The agent reached the maximum number of tool calls for this turn. Try a more focused request.")
  }

  private fun runAnthropic(
    config: CloudProviderConfig,
    systemPrompt: String,
    history: List<Pair<String, String>>,
    query: String,
    images: List<Pair<String, String>>,
    requestCancelled: AtomicBoolean,
    streamAnswer: (String) -> Unit,
    streamThinking: (String) -> Unit,
  ) {
    val messages = JSONArray()
    history.forEach { (role, text) ->
      if (text.isNotBlank()) messages.put(JSONObject().put("role", role).put("content", text))
    }
    val userContent = JSONArray()
    images.forEach { (mimeType, base64) ->
      userContent.put(JSONObject().put("type", "image").put("source",
        JSONObject().put("type", "base64").put("media_type", mimeType).put("data", base64)))
    }
    if (query.isNotBlank()) userContent.put(JSONObject().put("type", "text").put("text", query))
    messages.put(JSONObject().put("role", "user").put("content", userContent))
    val tools = JSONArray()
    cloudFunctions.forEach { function ->
      tools.put(JSONObject().put("name", function.name).put("description", function.description)
        .put("input_schema", functionSchema(function)))
    }
    repeat(MAX_TOOL_ROUNDS) {
      check(!requestCancelled.get()) { "Request cancelled" }
      val body = JSONObject().put("model", config.modelId).put("max_tokens", 8192)
        .put("messages", messages).put("tools", tools).put("stream", true)
      if (systemPrompt.isNotBlank()) body.put("system", systemPrompt)
      val content = anthropicRequest(config, body, requestCancelled, streamAnswer, streamThinking)
      val results = JSONArray()
      for (index in 0 until content.length()) {
        val block = content.getJSONObject(index)
        if (block.optString("type") == "tool_use") {
          check(!requestCancelled.get()) { "Request cancelled" }
          val result = invokeTool(block.getString("name"), block.getJSONObject("input").toString(), config)
          results.put(JSONObject().put("type", "tool_result")
            .put("tool_use_id", block.getString("id")).put("content", result))
        }
      }
      if (results.length() == 0) return
      messages.put(JSONObject().put("role", "assistant").put("content", content))
      messages.put(JSONObject().put("role", "user").put("content", results))
    }
    error("The agent reached the maximum number of tool calls for this turn. Try a more focused request.")
  }

  private fun anthropicRequest(
    config: CloudProviderConfig,
    body: JSONObject,
    requestCancelled: AtomicBoolean,
    streamAnswer: (String) -> Unit,
    streamThinking: (String) -> Unit,
  ): JSONArray {
    val endpoint = config.endpoint.trim().trimEnd('/')
    val requestUrl = if (endpoint.endsWith("/messages")) endpoint else "$endpoint/messages"
    val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      instanceFollowRedirects = false
      connectTimeout = 20_000
      readTimeout = 120_000
      doOutput = true
      setRequestProperty("Content-Type", "application/json")
      setRequestProperty("Accept", "text/event-stream")
      setRequestProperty("x-api-key", config.apiKey)
      setRequestProperty("anthropic-version", "2023-06-01")
    }
    activeConnection.set(connection)
    try {
      connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
      val code = connection.responseCode
      if (code !in 200..299) throw providerError(connection, code)
      val accumulator = AnthropicStreamAccumulator()
      connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
        readSseDataEvents(reader, requestCancelled) { data ->
          val chunk = parseSseJson(data)
          throwIfStreamProviderError(chunk)
          accumulator.add(chunk, streamAnswer, streamThinking)
        }
      }
      return accumulator.content()
    } finally {
      activeConnection.compareAndSet(connection, null)
      connection.disconnect()
    }
  }

  private fun runGemini(
    config: CloudProviderConfig,
    systemPrompt: String,
    history: List<Pair<String, String>>,
    query: String,
    images: List<Pair<String, String>>,
    requestCancelled: AtomicBoolean,
    streamAnswer: (String) -> Unit,
    streamThinking: (String) -> Unit,
  ) {
    val contents = JSONArray()
    history.forEach { (role, content) ->
      contents.put(
        JSONObject()
          .put("role", if (role == "assistant") "model" else "user")
          .put("parts", JSONArray().put(JSONObject().put("text", content))),
      )
    }
    val userParts = JSONArray()
    if (query.isNotBlank()) userParts.put(JSONObject().put("text", query))
    images.forEach { (mimeType, base64) ->
      userParts.put(
        JSONObject()
          .put("inlineData", JSONObject().put("mimeType", mimeType).put("data", base64)),
      )
    }
    contents.put(JSONObject().put("role", "user").put("parts", userParts))
    val tools = geminiTools()

    repeat(MAX_TOOL_ROUNDS) {
      check(!requestCancelled.get()) { "Request cancelled" }
      val body = JSONObject().put("contents", contents).put("tools", tools)
      if (systemPrompt.isNotBlank()) {
        body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
      }
      val supportsThinking = supportsCloudThinkingEffort(if (config.protocol == "gemini") CLOUD_PROVIDER_GEMINI else config.providerId, config.modelId)
      if (supportsThinking || config.thinkingEffort != CLOUD_THINKING_DEFAULT) {
        val thinkingConfig = JSONObject()
        // includeThoughts requests the provider's explicitly marked thought summary, not hidden
        // reasoning. Only Gemini model families known to support thinking get this field.
        if (supportsThinking) thinkingConfig.put("includeThoughts", true)
        if (config.thinkingEffort != CLOUD_THINKING_DEFAULT && supportsThinking) {
          if (config.modelId.trim().lowercase().startsWith("gemini-3")) {
            thinkingConfig.put("thinkingLevel", config.thinkingEffort.uppercase())
          } else {
            val thinkingBudget =
              when (config.thinkingEffort) {
                CLOUD_THINKING_LOW -> 1024
                CLOUD_THINKING_HIGH -> 24576
                else -> 8192
              }
            thinkingConfig.put("thinkingBudget", thinkingBudget)
          }
        }
        if (thinkingConfig.length() > 0) {
          body.put("generationConfig", JSONObject().put("thinkingConfig", thinkingConfig))
        }
      }

      val stream = geminiStreamRequest(config, body, requestCancelled, streamAnswer, streamThinking)
      if (stream.calls.isEmpty()) return
      // Keep the original model parts (including thoughtSignature) intact for the next tool round.
      contents.put(JSONObject().put("role", "model").put("parts", stream.parts))
      val functionResponses = JSONArray()
      stream.calls.forEach { (name, args) ->
        val result = invokeTool(name, args.toString(), config)
        functionResponses.put(
          JSONObject()
            .put("functionResponse", JSONObject().put("name", name).put("response", JSONObject().put("result", result))),
        )
      }
      contents.put(JSONObject().put("role", "user").put("parts", functionResponses))
    }
    error("The agent reached the maximum number of tool calls for this turn. Try a more focused request.")
  }

  private fun openAiRequest(
    config: CloudProviderConfig,
    body: JSONObject,
    requestCancelled: AtomicBoolean,
    streamAnswer: (String) -> Unit,
    streamThinking: (String) -> Unit,
  ): OpenAiCompletion {
    val endpoint = config.endpoint.trim().trimEnd('/')
    val requestUrl = if (endpoint.endsWith(OPENAI_CHAT_PATH)) endpoint else "$endpoint$OPENAI_CHAT_PATH"
    val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      instanceFollowRedirects = false
      connectTimeout = 20_000
      readTimeout = 120_000
      doOutput = true
      setRequestProperty("Content-Type", "application/json")
      setRequestProperty("Accept", "text/event-stream")
      setRequestProperty("Authorization", "Bearer ${config.apiKey}")
    }
    activeConnection.set(connection)
    try {
      connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
      val code = connection.responseCode
      if (code !in 200..299) throw providerError(connection, code)
      val toolCalls = TreeMap<Int, MutableOpenAiToolCall>()
      val responseText = StringBuilder()
      val responseReasoning = StringBuilder()
      BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { reader ->
        readSseDataEvents(reader, requestCancelled) { data ->
          if (data == "[DONE]") return@readSseDataEvents
          val chunk = parseSseJson(data)
          throwIfStreamProviderError(chunk)
          val delta = chunk.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: return@readSseDataEvents
          delta.optString("content").takeIf { it.isNotEmpty() }?.let { token ->
            responseText.append(token)
            streamAnswer(token)
          }
          // Compatible providers expose reasoning only when they choose to include it. Never
          // derive this field from ordinary response text.
          listOf("reasoning_content", "reasoning").forEach { key ->
            delta.optString(key).takeIf { it.isNotEmpty() }?.let { thought ->
              if (key == "reasoning_content") responseReasoning.append(thought)
              streamThinking(thought)
            }
          }
          val deltas = delta.optJSONArray("tool_calls") ?: return@readSseDataEvents
          for (i in 0 until deltas.length()) {
            val item = deltas.optJSONObject(i) ?: continue
            val index = item.optInt("index", i)
            val target = toolCalls.getOrPut(index) { MutableOpenAiToolCall() }
            item.optString("id").takeIf { it.isNotEmpty() }?.let { target.id = it }
            val function = item.optJSONObject("function") ?: continue
            function.optString("name").takeIf { it.isNotEmpty() }?.let { target.name.append(it) }
            function.optString("arguments").takeIf { it.isNotEmpty() }?.let { target.arguments.append(it) }
          }
        }
      }
      return OpenAiCompletion(
        text = responseText.toString(),
        reasoning = responseReasoning.toString(),
        toolCalls = toolCalls.values.map { call ->
          CloudToolCall(
            id = call.id.ifBlank { "call_${System.nanoTime()}" },
            name = call.name.toString(),
            arguments = call.arguments.toString().ifBlank { "{}" },
          )
        },
      )
    } finally {
      activeConnection.compareAndSet(connection, null)
      connection.disconnect()
    }
  }

  private fun geminiStreamRequest(
    config: CloudProviderConfig,
    body: JSONObject,
    requestCancelled: AtomicBoolean,
    streamAnswer: (String) -> Unit,
    streamThinking: (String) -> Unit,
  ): GeminiStreamResult {
    val endpoint = config.endpoint.trim().trimEnd('/')
    val model = URLEncoder.encode(config.modelId, "UTF-8").replace("+", "%20")
    val requestUrl = "$endpoint/models/$model:streamGenerateContent?alt=sse"
    val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      instanceFollowRedirects = false
      connectTimeout = 20_000
      readTimeout = 120_000
      doOutput = true
      setRequestProperty("Content-Type", "application/json")
      setRequestProperty("Accept", "text/event-stream")
      setRequestProperty("x-goog-api-key", config.apiKey)
    }
    activeConnection.set(connection)
    try {
      connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
      val code = connection.responseCode
      if (code !in 200..299) throw providerError(connection, code)
      val accumulated = GeminiStreamAccumulator()
      var sawCandidate = false
      BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { reader ->
        readSseDataEvents(reader, requestCancelled) { data ->
          if (data == "[DONE]") return@readSseDataEvents
          val chunk = parseSseJson(data)
          throwIfStreamProviderError(chunk)
          val candidates = chunk.optJSONArray("candidates") ?: return@readSseDataEvents
          val candidate = candidates.optJSONObject(0) ?: return@readSseDataEvents
          sawCandidate = true
          val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return@readSseDataEvents
          for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            accumulated.add(index, part)
            val text = part.optString("text")
            if (text.isNotEmpty()) {
              if (part.optBoolean("thought", false)) streamThinking(text) else streamAnswer(text)
            }
          }
        }
      }
      if (!sawCandidate) error("The provider returned no response. Check the model ID and API key.")
      return accumulated.result()
    } catch (e: java.io.IOException) {
      if (requestCancelled.get()) throw CancellationException("Request cancelled", e)
      throw e
    } finally {
      activeConnection.compareAndSet(connection, null)
      connection.disconnect()
    }
  }

  private fun geminiRequest(config: CloudProviderConfig, body: JSONObject): JSONObject {
    val endpoint = config.endpoint.trim().trimEnd('/')
    val model = URLEncoder.encode(config.modelId, "UTF-8").replace("+", "%20")
    val requestUrl = "$endpoint/models/$model:generateContent"
    val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      instanceFollowRedirects = false
      connectTimeout = 20_000
      readTimeout = 120_000
      doOutput = true
      setRequestProperty("Content-Type", "application/json")
      setRequestProperty("Accept", "application/json")
      setRequestProperty("x-goog-api-key", config.apiKey)
    }
    activeConnection.set(connection)
    try {
      connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
      val code = connection.responseCode
      if (code !in 200..299) throw providerError(connection, code)
      return JSONObject(connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() })
    } finally {
      activeConnection.compareAndSet(connection, null)
      connection.disconnect()
    }
  }

  private fun createImage(config: CloudProviderConfig, prompt: String): String {
    require(prompt.isNotBlank()) { "The image prompt cannot be empty." }
    require(config.isImageModelConfigured) { "Add an image model ID in Cloud model settings first." }
    val base64 =
      when (config.protocol) {
        "gemini" -> generateGeminiImage(config.copy(modelId = config.imageModelId), prompt)
        "openai" -> generateOpenAiImage(config, prompt)
        else -> error("Image generation is not supported by this provider.")
      }
    agentTools.resultImageToShow = CallJsSkillResultImage(base64 = base64)
    return Gson().toJson(
      mapOf("status" to "succeeded", "imageGenerated" to true, "model" to config.imageModelId)
    )
  }

  private fun generateGeminiImage(config: CloudProviderConfig, prompt: String): String {
    val body =
      JSONObject()
        .put(
          "contents",
          JSONArray().put(
            JSONObject()
              .put("role", "user")
              .put("parts", JSONArray().put(JSONObject().put("text", prompt))),
          ),
        )
        .put(
          "generationConfig",
          JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")),
        )
    val response = geminiRequest(config, body)
    val parts =
      response.optJSONArray("candidates")
        ?.optJSONObject(0)
        ?.optJSONObject("content")
        ?.optJSONArray("parts")
        ?: JSONArray()
    for (index in 0 until parts.length()) {
      val part = parts.optJSONObject(index) ?: continue
      val imageData = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
      imageData?.optString("data")?.takeIf { it.isNotBlank() }?.let { return it }
    }
    error("The image model returned no image. Check that the selected Gemini model supports image generation.")
  }

  private fun generateOpenAiImage(config: CloudProviderConfig, prompt: String): String {
    val endpoint = config.endpoint.trim().trimEnd('/')
    val requestUrl =
      if (endpoint.endsWith(OPENAI_IMAGES_PATH)) endpoint
      else "$endpoint$OPENAI_IMAGES_PATH"
    val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      instanceFollowRedirects = false
      connectTimeout = 20_000
      readTimeout = 180_000
      doOutput = true
      setRequestProperty("Content-Type", "application/json")
      setRequestProperty("Accept", "application/json")
      setRequestProperty("Authorization", "Bearer ${config.apiKey}")
    }
    activeConnection.set(connection)
    try {
      val body =
        JSONObject()
          .put("model", config.imageModelId)
          .put("prompt", prompt)
          .put("n", 1)
          .put("size", "1024x1024")
      connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
      val code = connection.responseCode
      if (code !in 200..299) throw providerError(connection, code)
      val response = JSONObject(connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() })
      val firstImage = response.optJSONArray("data")?.optJSONObject(0)
        ?: error("The image provider returned no image data.")
      firstImage.optString("b64_json").takeIf { it.isNotBlank() }?.let { return it }
      val imageUrl = firstImage.optString("url").takeIf { it.isNotBlank() }
        ?: error("The image provider did not return an image payload.")
      return downloadGeneratedImage(imageUrl)
    } finally {
      activeConnection.compareAndSet(connection, null)
      connection.disconnect()
    }
  }

  private fun downloadGeneratedImage(imageUrl: String): String {
    val url = URL(imageUrl)
    require(url.protocol.equals("https", ignoreCase = true)) {
      "The image provider returned an insecure image URL."
    }
    val connection = (url.openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = 20_000
      readTimeout = 60_000
    }
    activeConnection.set(connection)
    try {
      val code = connection.responseCode
      if (code !in 200..299) throw providerError(connection, code)
      val bytes = connection.inputStream.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
          if (interrupted.get()) throw CancellationException("Request cancelled")
          val count = input.read(buffer)
          if (count < 0) break
          total += count
          require(total <= 20 * 1024 * 1024) { "The generated image is larger than 20 MB." }
          output.write(buffer, 0, count)
        }
        output.toByteArray()
      }
      return Base64.encodeToString(bytes, Base64.NO_WRAP)
    } finally {
      activeConnection.compareAndSet(connection, null)
      connection.disconnect()
    }
  }

  private fun providerError(connection: HttpURLConnection, code: Int): IllegalStateException {
    val body = runCatching {
      val stream = connection.errorStream ?: return@runCatching ""
      stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }.getOrDefault("")
    val detail = runCatching {
      val json = JSONObject(body)
      json.optJSONObject("error")?.optString("message")
        ?: json.optString("message")
    }.getOrNull().orEmpty()
    return IllegalStateException(
      if (detail.isNotBlank()) "Cloud provider returned HTTP $code: $detail"
      else "Cloud provider returned HTTP $code. Check the endpoint, model ID, and API key."
    )
  }

  private fun openAiUserMessage(query: String, images: List<Pair<String, String>>): JSONObject {
    if (images.isEmpty()) return JSONObject().put("role", "user").put("content", query)
    val parts = JSONArray()
    if (query.isNotBlank()) parts.put(JSONObject().put("type", "text").put("text", query))
    images.forEach { (mimeType, base64) ->
      parts.put(
        JSONObject()
          .put("type", "image_url")
          .put("image_url", JSONObject().put("url", "data:$mimeType;base64,$base64")),
      )
    }
    return JSONObject().put("role", "user").put("content", parts)
  }

  private fun openAiTools(): JSONArray =
    JSONArray().apply {
      cloudFunctions.forEach { function ->
        put(
          JSONObject()
            .put("type", "function")
            .put(
              "function",
              JSONObject()
                .put("name", function.name)
                .put("description", function.description)
                .put("parameters", functionSchema(function)),
            ),
        )
      }
    }

  private fun geminiTools(): JSONArray =
    JSONArray().put(
      JSONObject().put(
        "functionDeclarations",
        JSONArray().apply {
          cloudFunctions.forEach { function ->
            put(
              JSONObject()
                .put("name", function.name)
                .put("description", function.description)
                .put("parameters", geminiFunctionSchema(function)),
            )
          }
        },
      ),
    )

  private fun functionSchema(function: CloudFunction): JSONObject {
    val properties = JSONObject()
    function.parameters.forEach { (name, description) ->
      properties.put(name, JSONObject().put("type", "string").put("description", description))
    }
    return JSONObject()
      .put("type", "object")
      .put("properties", properties)
      .put("required", JSONArray(function.required))
      .put("additionalProperties", false)
  }

  private fun geminiFunctionSchema(function: CloudFunction): JSONObject {
    val properties = JSONObject()
    function.parameters.forEach { (name, description) ->
      properties.put(name, JSONObject().put("type", "STRING").put("description", description))
    }
    return JSONObject()
      .put("type", "OBJECT")
      .put("properties", properties)
      .put("required", JSONArray(function.required))
  }

  private fun invokeTool(name: String, arguments: String, config: CloudProviderConfig): String {
    val json = runCatching { JSONObject(arguments) }.getOrDefault(JSONObject())
    if (name == "createImage") {
      return createImage(config, json.optString("prompt"))
    }
    toolDispatcher.setupExecutionContext(
      tools = agentTools.getAvailableTools(),
      context = ToolExecutionContext(
        taskId = activeConfig.get()?.taskId.orEmpty(),
        actionChannel = activeConfig.get()?.actionChannel,
      ),
    )
    val result = agentTools.invokeCloudTool(name, json)
    return when (result) {
      is String -> result
      null -> "Tool did not return a result."
      else -> Gson().toJson(result)
    }
  }

  private fun readHistory(value: Any?): List<Pair<String, String>> {
    val values = value as? List<*> ?: return emptyList()
    return values.mapNotNull { item ->
      val map = item as? Map<*, *> ?: return@mapNotNull null
      val role = map["role"] as? String ?: return@mapNotNull null
      val content = map["content"] as? String ?: return@mapNotNull null
      if (role == "user" || role == "assistant") role to content else null
    }
  }

  private fun encodeImages(attachments: List<com.google.ai.edge.gallery.agent.Attachment>): List<Pair<String, String>> {
    return attachments.mapNotNull { attachment ->
      val bytesAndType =
        when (attachment) {
          is com.google.ai.edge.gallery.agent.Attachment.ImageBitmap -> bitmapBytes(attachment.bitmap) to "image/jpeg"
          is com.google.ai.edge.gallery.agent.Attachment.ImageUri -> {
            val uri = Uri.parse(attachment.uri)
            val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@mapNotNull null
            bytes to (appContext.contentResolver.getType(uri) ?: "image/jpeg")
          }
          is com.google.ai.edge.gallery.agent.Attachment.File -> File(attachment.path).readBytes() to attachment.mimeType
          else -> return@mapNotNull null
        }
      bytesAndType.second to Base64.encodeToString(bytesAndType.first, Base64.NO_WRAP)
    }
  }

  private fun bitmapBytes(bitmap: Bitmap): ByteArray {
    val output = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 86, output)
    return output.toByteArray()
  }

  override suspend fun execute(context: AgentExecutionContext, request: AgentRequest): AgentResponse {
    var output = ""
    var success = true
    executeStream(context, request).collect { event ->
      when (event) {
        is AgentEvent.StreamToken -> output += event.token
        is AgentEvent.LoopTerminated -> output = event.finalResponse
        is AgentEvent.Error -> { output = event.errorMessage; success = false }
        else -> Unit
      }
    }
    return AgentResponse(output = output, isSuccessful = success)
  }

  override fun interrupt() {
    interrupted.set(true)
    activeRequestCancelled.get()?.set(true)
    activeConnection.getAndSet(null)?.disconnect()
  }

  override suspend fun resetSession(config: AgentRuntimeConfig) {
    val sessionId = config.sessionId.ifBlank { llmSessionManager.activeSessionId ?: generateSessionId() }
    llmSessionManager.activeSessionId = sessionId
    activeConfig.set(config.copy(sessionId = sessionId))
    toolDispatcher.setupExecutionContext(
      tools = agentTools.getAvailableTools(),
      context = ToolExecutionContext(taskId = config.taskId, actionChannel = config.actionChannel),
    )
  }

  override fun cleanUp(onDone: () -> Unit) {
    interrupt()
    activeConfig.set(null)
    onDone()
  }
}

private data class CloudToolCall(val id: String, val name: String, val arguments: String)
private data class OpenAiCompletion(val text: String, val toolCalls: List<CloudToolCall>, val reasoning: String = "")
private class MutableOpenAiToolCall {
  var id: String = ""
  val name = StringBuilder()
  val arguments = StringBuilder()
}


/** Reads standard SSE data frames, including multiline data and a final unterminated frame. */
internal fun readSseDataEvents(
  reader: BufferedReader,
  interrupted: AtomicBoolean,
  onData: (String) -> Unit,
) {
  val dataLines = mutableListOf<String>()
  fun dispatch(): Boolean {
    if (dataLines.isEmpty()) return false
    val data = dataLines.joinToString("\n")
    dataLines.clear()
    onData(data)
    return data == "[DONE]"
  }
  while (true) {
    if (interrupted.get()) throw CancellationException("Request cancelled")
    val line = try {
      reader.readLine()
    } catch (e: java.io.IOException) {
      if (interrupted.get()) throw CancellationException("Request cancelled", e)
      throw e
    } ?: break
    if (line.isEmpty()) {
      if (dispatch()) return
    } else if (!line.startsWith(":")) {
      val colon = line.indexOf(':')
      val field = if (colon < 0) line else line.substring(0, colon)
      if (field == "data") {
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        dataLines.add(value)
      }
    }
  }
  if (interrupted.get()) throw CancellationException("Request cancelled")
  dispatch()
}

internal fun parseSseJson(data: String): JSONObject =
  try {
    JSONObject(data)
  } catch (e: Exception) {
    throw IllegalStateException("The cloud provider sent malformed streaming data: ${e.message ?: data.take(160)}", e)
  }

internal fun throwIfStreamProviderError(json: JSONObject) {
  json.optJSONObject("error")?.let { error ->
    throw IllegalStateException(error.optString("message").ifBlank { error.toString() })
  }
}

private data class GeminiStreamResult(
  val parts: JSONArray,
  val calls: List<Pair<String, JSONObject>>,
)

/** Merges Gemini deltas by part index/type while retaining signatures and other provider fields. */
private class GeminiStreamAccumulator {
  private data class Slot(val index: Int, val thought: Boolean, val isCall: Boolean, val part: JSONObject)
  private val slots = mutableListOf<Slot>()

  fun add(index: Int, incoming: JSONObject) {
    val thought = incoming.optBoolean("thought", false)
    val isCall = incoming.optJSONObject("functionCall") != null
    val slot = slots.firstOrNull { it.index == index && it.thought == thought && it.isCall == isCall }
    if (slot == null) {
      slots.add(Slot(index, thought, isCall, JSONObject(incoming.toString())))
      return
    }
    incoming.optString("text").takeIf { it.isNotEmpty() }?.let { text ->
      slot.part.put("text", slot.part.optString("text") + text)
    }
    incoming.optJSONObject("functionCall")?.let { functionCall ->
      val merged = JSONObject(slot.part.optJSONObject("functionCall")?.toString() ?: "{}")
      functionCall.optString("name").takeIf { it.isNotEmpty() }?.let { merged.put("name", it) }
      functionCall.optJSONObject("args")?.let { args ->
        val mergedArgs = JSONObject(merged.optJSONObject("args")?.toString() ?: "{}")
        val keys = args.keys()
        while (keys.hasNext()) {
          val key = keys.next()
          mergedArgs.put(key, args.get(key))
        }
        merged.put("args", mergedArgs)
      }
      slot.part.put("functionCall", merged)
    }
    val keys = incoming.keys()
    while (keys.hasNext()) {
      val key = keys.next()
      if (key != "text" && key != "functionCall") slot.part.put(key, incoming.get(key))
    }
  }

  fun result(): GeminiStreamResult {
    val parts = JSONArray()
    val calls = mutableListOf<Pair<String, JSONObject>>()
    slots.sortedBy { it.index }.forEach { slot ->
      parts.put(slot.part)
      slot.part.optJSONObject("functionCall")?.let { call ->
        calls.add(call.optString("name") to (call.optJSONObject("args") ?: JSONObject()))
      }
    }
    return GeminiStreamResult(parts, calls)
  }
}
