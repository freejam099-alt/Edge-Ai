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
import com.google.ai.edge.gallery.ui.home.CLOUD_PROVIDER_OPENAI_COMPATIBLE
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
import kotlinx.coroutines.delay
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
      val streamToken: (String) -> Unit = { token ->
        if (token.isNotEmpty() && !interrupted.get()) {
          finalResponse.append(token)
          trySend(AgentEvent.StreamToken(token = token))
        }
      }

      try {
        val history = readHistory(request.metadata[AgentRequest.CLOUD_HISTORY])
        val imageParts = encodeImages(request.attachments)
        withContext(Dispatchers.IO) {
          when (providerConfig.providerId) {
            CLOUD_PROVIDER_GEMINI ->
              runGemini(providerConfig, config.systemInstruction.orEmpty(), history, request.query, imageParts, streamToken)
            CLOUD_PROVIDER_OPENAI_COMPATIBLE ->
              runOpenAiCompatible(providerConfig, config.systemInstruction.orEmpty(), history, request.query, imageParts, streamToken)
            else -> error("Unsupported cloud provider. Choose Gemini or OpenAI-compatible.")
          }
        }
        if (interrupted.get()) {
          send(AgentEvent.LoopCancelled)
        } else {
          send(AgentEvent.StreamToken(token = "", done = true))
          send(AgentEvent.LoopTerminated(finalResponse.toString()))
        }
      } catch (e: CancellationException) {
        activeConnection.getAndSet(null)?.disconnect()
        throw e
      } catch (e: Exception) {
        activeConnection.getAndSet(null)?.disconnect()
        if (interrupted.get()) {
          send(AgentEvent.LoopCancelled)
        } else {
          send(AgentEvent.Error(e.message ?: "Cloud request failed. Check the provider settings and network connection."))
        }
      } finally {
        activeConnection.getAndSet(null)?.disconnect()
      }
    }

  private fun runOpenAiCompatible(
    config: CloudProviderConfig,
    systemPrompt: String,
    history: List<Pair<String, String>>,
    query: String,
    images: List<Pair<String, String>>,
    streamToken: (String) -> Unit,
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
      check(!interrupted.get()) { "Request cancelled" }
      val body =
        JSONObject()
          .put("model", config.modelId)
          .put("messages", messages)
          .put("tools", tools)
          .put("tool_choice", "auto")
          .put("stream", true)
      if (
        config.thinkingEffort != CLOUD_THINKING_DEFAULT &&
          supportsCloudThinkingEffort(config.providerId, config.modelId) &&
          !config.modelId.trim().lowercase().contains("deepseek-r1")
      ) {
        body.put("reasoning_effort", config.thinkingEffort)
      }
      val completion = openAiRequest(config, body, streamToken)
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

  private suspend fun runGemini(
    config: CloudProviderConfig,
    systemPrompt: String,
    history: List<Pair<String, String>>,
    query: String,
    images: List<Pair<String, String>>,
    streamToken: (String) -> Unit,
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
      check(!interrupted.get()) { "Request cancelled" }
      val body = JSONObject().put("contents", contents).put("tools", tools)
      if (systemPrompt.isNotBlank()) {
        body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
      }
      if (
        config.thinkingEffort != CLOUD_THINKING_DEFAULT &&
          supportsCloudThinkingEffort(config.providerId, config.modelId)
      ) {
        val thinkingConfig = JSONObject()
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
        body.put("generationConfig", JSONObject().put("thinkingConfig", thinkingConfig))
      }
      val response = geminiRequest(config, body)
      val candidate = response.optJSONArray("candidates")?.optJSONObject(0)
        ?: error("The provider returned no response. Check the model ID and API key.")
      val responseContent = candidate.optJSONObject("content") ?: JSONObject()
      val parts = responseContent.optJSONArray("parts") ?: JSONArray()
      val calls = mutableListOf<Pair<String, JSONObject>>()
      val text = StringBuilder()
      for (index in 0 until parts.length()) {
        val part = parts.optJSONObject(index) ?: continue
        part.optString("text").takeIf { it.isNotEmpty() }?.let(text::append)
        val functionCall = part.optJSONObject("functionCall")
        if (functionCall != null) {
          calls.add(functionCall.optString("name") to (functionCall.optJSONObject("args") ?: JSONObject()))
        }
      }
      if (calls.isEmpty()) {
        emitTextSmoothly(text.toString(), streamToken)
        return
      }
      contents.put(JSONObject().put("role", "model").put("parts", parts))
      val functionResponses = JSONArray()
      calls.forEach { (name, args) ->
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
    streamToken: (String) -> Unit,
  ): OpenAiCompletion {
    val endpoint = config.endpoint.trim().trimEnd('/')
    val requestUrl = if (endpoint.endsWith(OPENAI_CHAT_PATH)) endpoint else "$endpoint$OPENAI_CHAT_PATH"
    val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
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
      BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { reader ->
        while (true) {
          if (interrupted.get()) throw CancellationException("Request cancelled")
          val line = reader.readLine() ?: break
          if (!line.startsWith("data:")) continue
          val data = line.removePrefix("data:").trim()
          if (data.isEmpty() || data == "[DONE]") continue
          val chunk = runCatching { JSONObject(data) }.getOrNull() ?: continue
          val delta = chunk.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
          delta.optString("content").takeIf { it.isNotEmpty() }?.let { token ->
            responseText.append(token)
            streamToken(token)
          }
          val deltas = delta.optJSONArray("tool_calls") ?: continue
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

  private fun geminiRequest(config: CloudProviderConfig, body: JSONObject): JSONObject {
    val endpoint = config.endpoint.trim().trimEnd('/')
    val model = URLEncoder.encode(config.modelId, "UTF-8").replace("+", "%20")
    val requestUrl = "$endpoint/models/$model:generateContent"
    val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
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
      when (config.providerId) {
        CLOUD_PROVIDER_GEMINI -> generateGeminiImage(config.copy(modelId = config.imageModelId), prompt)
        CLOUD_PROVIDER_OPENAI_COMPATIBLE -> generateOpenAiImage(config, prompt)
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

  private suspend fun emitTextSmoothly(text: String, streamToken: (String) -> Unit) {
    if (text.isEmpty()) return
    text.chunked(20).forEach { chunk ->
      if (interrupted.get()) throw CancellationException("Request cancelled")
      streamToken(chunk)
      delay(14)
    }
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
private data class OpenAiCompletion(val text: String, val toolCalls: List<CloudToolCall>)
private class MutableOpenAiToolCall {
  var id: String = ""
  val name = StringBuilder()
  val arguments = StringBuilder()
}
