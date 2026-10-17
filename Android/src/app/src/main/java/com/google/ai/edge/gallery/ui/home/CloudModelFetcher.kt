/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0
 */
package com.google.ai.edge.gallery.ui.home

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Model IDs returned by the provider; errors intentionally never include request URLs or credentials. */
data class CloudModel(val id: String, val displayName: String = id)

class CloudModelFetchException(message: String) : Exception(message)

/** Fetches real remote model catalogs. Gemini and Anthropic cursor pagination is followed. */
suspend fun fetchCloudModels(
  providerId: String,
  protocol: String,
  endpoint: String,
  apiKey: String,
): List<CloudModel> = withContext(Dispatchers.IO) {
  if (apiKey.isBlank()) throw CloudModelFetchException("Enter an API key before fetching models.")
  val base = endpoint.trim().trimEnd('/')
  if (base.isBlank()) throw CloudModelFetchException("Enter a valid API base URL.")
  val models = linkedMapOf<String, CloudModel>()
  try {
    when (protocol) {
      CLOUD_PROTOCOL_GEMINI -> {
        var token: String? = null
        var pageCount = 0
        while (pageCount++ < 100) {
          val url = buildString {
            append(if (base.endsWith("/models")) base else "$base/models")
            append(if ('?' in this) '&' else '?')
            append("pageSize=1000")
            if (!token.isNullOrBlank()) append("&pageToken=").append(encodeQuery(token!!))
          }
          val body = request(url, mapOf("x-goog-api-key" to apiKey, "Accept" to "application/json"))
          val json = JSONObject(body)
          parseGeminiModels(json.toString()).forEach { models[it.id] = it }
          token = json.optString("nextPageToken").ifBlank { null }
          if (token == null) break
        }
      }
      CLOUD_PROTOCOL_ANTHROPIC -> {
        var cursor: String? = null
        var pageCount = 0
        while (pageCount++ < 100) {
          val modelsUrl = when {
            base.endsWith("/v1/models") -> base
            base.endsWith("/v1") -> "$base/models"
            else -> "$base/v1/models"
          }
          val url = buildString {
            append(modelsUrl)
            if (!cursor.isNullOrBlank()) append("?after_id=").append(encodeQuery(cursor!!))
          }
          val body = request(url, mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01", "Accept" to "application/json"))
          val json = JSONObject(body)
          parseAnthropicModels(json.toString()).forEach { models[it.id] = it }
          if (!json.optBoolean("has_more", false)) break
          cursor = json.optString("last_id").ifBlank { null }
          if (cursor == null) throw CloudModelFetchException("The provider returned an incomplete model page.")
        }
      }
      CLOUD_PROTOCOL_OPENAI -> {
        val url = if (base.endsWith("/models")) base else "$base/models"
        val body = request(url, mapOf("Authorization" to "Bearer $apiKey", "Accept" to "application/json"))
        val json = JSONObject(body)
        val page = json.optJSONArray("data") ?: json.optJSONArray("models") ?: JSONArray()
        for (i in 0 until page.length()) {
          val item = page.optJSONObject(i) ?: continue
          val id = item.optString("id").ifBlank { item.optString("name").removePrefix("models/") }
          if (id.isNotBlank()) models[id] = CloudModel(id, item.optString("display_name", id))
        }
      }
      else -> throw CloudModelFetchException("Choose a supported API protocol first.")
    }
  } catch (e: CloudModelFetchException) {
    throw e
  } catch (_: java.net.SocketTimeoutException) {
    throw CloudModelFetchException("The model request timed out. Check the endpoint and try again.")
  } catch (_: java.net.UnknownHostException) {
    throw CloudModelFetchException("Could not reach the provider. Check the endpoint and network connection.")
  } catch (_: Exception) {
    throw CloudModelFetchException("Could not read the provider's model list. Check the endpoint, protocol, and API key.")
  }
  if (models.isEmpty()) throw CloudModelFetchException("The provider returned no models available for this API.")
  models.values.sortedBy { it.id.lowercase() }
}

/** Pure parser helper suitable for unit tests and provider payload variations. */
fun parseGeminiModels(json: String): List<CloudModel> {
  val page = JSONObject(json).optJSONArray("models") ?: JSONArray()
  return (0 until page.length()).mapNotNull { i ->
    val model = page.optJSONObject(i) ?: return@mapNotNull null
    val methods = model.optJSONArray("supportedGenerationMethods") ?: JSONArray()
    if ((0 until methods.length()).none { methods.optString(it) == "generateContent" }) return@mapNotNull null
    val id = model.optString("name").removePrefix("models/")
    id.takeIf(String::isNotBlank)?.let { CloudModel(it, model.optString("displayName", it)) }
  }.distinctBy { it.id }
}

fun parseAnthropicModels(json: String): List<CloudModel> {
  val data = JSONObject(json).optJSONArray("data") ?: JSONArray()
  return (0 until data.length()).mapNotNull { i ->
    val item = data.optJSONObject(i) ?: return@mapNotNull null
    item.optString("id").takeIf(String::isNotBlank)?.let { CloudModel(it, item.optString("display_name", it)) }
  }.distinctBy { it.id }
}

fun parseOpenAiModelIds(json: String): List<String> {
  val root = JSONObject(json)
  val data = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
  return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }.distinct()
}

private fun request(url: String, headers: Map<String, String>): String {
  val connection = URL(url).openConnection() as HttpURLConnection
  connection.connectTimeout = 12_000
  connection.readTimeout = 20_000
  connection.instanceFollowRedirects = false
  connection.requestMethod = "GET"
  headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
  try {
    val status = connection.responseCode
    if (status !in 200..299) {
      val message = when (status) {
        401, 403 -> "Authentication failed. Check the API key for this provider."
        404, 405 -> "This provider does not expose a compatible model-list endpoint. Enter a model ID manually."
        429 -> "The provider rate-limited the model request. Try again shortly."
        in 500..599 -> "The provider is temporarily unavailable (HTTP $status). Try again later."
        else -> "The provider rejected the model request (HTTP $status). Check the endpoint and protocol."
      }
      throw CloudModelFetchException(message)
    }
    return connection.inputStream.bufferedReader().use { it.readText() }
  } finally { connection.disconnect() }
}

private fun encodeQuery(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
