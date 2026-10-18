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
package com.google.ai.edge.gallery.ui.home

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.ai.edge.gallery.data.DataStoreRepository
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

const val CLOUD_PROVIDER_GEMINI = "gemini"
const val CLOUD_PROVIDER_OPENAI_COMPATIBLE = "openai_compatible"
const val CLOUD_PROVIDER_OPENROUTER = "openrouter"
const val CLOUD_PROVIDER_GROQ = "groq"
const val CLOUD_PROVIDER_DEEPSEEK = "deepseek"
const val CLOUD_PROVIDER_QWEN = "qwen"
const val CLOUD_PROVIDER_ANTHROPIC = "anthropic"
const val CLOUD_PROVIDER_CUSTOM = "custom"
const val CLOUD_PROVIDER_EAY = "eay"

/** Curated EAY model choices; labels are presentation-only and IDs are sent unchanged. */
data class CloudModelPreset(val label: String, val id: String)

val EAY_MODEL_PRESETS = listOf(
  CloudModelPreset("Wiki lite", "eay/gemini-3-flash-preview"),
  CloudModelPreset("Wiki flash", "eay/gemini-3.1-flash-lite"),
  CloudModelPreset("Wiki Pro", "eay/gemini-3.1-pro-preview"),
)

fun cloudModelDisplayName(modelId: String): String =
  EAY_MODEL_PRESETS.firstOrNull { it.id == modelId }?.label ?: modelId

fun cloudModelDisplayName(providerId: String, modelId: String): String =
  if (providerId == CLOUD_PROVIDER_EAY) cloudModelDisplayName(modelId) else modelId

/** Produces ordered choices for the compact default-model picker without changing request IDs. */
fun cloudModelPickerChoices(
  providerId: String,
  fetchedModelIds: List<String>,
  query: String,
): List<CloudModelPreset> {
  val choices = if (providerId == CLOUD_PROVIDER_EAY) {
    EAY_MODEL_PRESETS
  } else {
    fetchedModelIds.distinct().map { CloudModelPreset(it, it) }
  }
  val normalizedQuery = query.trim().lowercase()
  return if (normalizedQuery.isEmpty()) choices else choices.filter {
    it.label.lowercase().contains(normalizedQuery) || it.id.lowercase().contains(normalizedQuery)
  }
}

const val CLOUD_PROVIDER_KEY = "cloud_provider_id"
const val CLOUD_ENDPOINT_KEY = "cloud_provider_endpoint"
const val CLOUD_MODEL_KEY = "cloud_provider_model"
const val CLOUD_TEXT_MODEL_KEY = "cloud_provider_text_model"
const val CLOUD_IMAGE_MODEL_KEY = "cloud_provider_image_model"
const val CLOUD_THINKING_EFFORT_KEY = "cloud_provider_thinking_effort"
const val CLOUD_THINKING_DEFAULT = "default"
const val CLOUD_THINKING_LOW = "low"
const val CLOUD_THINKING_MEDIUM = "medium"
const val CLOUD_THINKING_HIGH = "high"
const val CLOUD_PROTOCOL_KEY = "cloud_provider_protocol"
const val CLOUD_PROTOCOL_OPENAI = "openai"
const val CLOUD_PROTOCOL_GEMINI = "gemini"
const val CLOUD_PROTOCOL_ANTHROPIC = "anthropic"

fun defaultCloudProtocol(providerId: String): String = when (providerId) {
  CLOUD_PROVIDER_GEMINI -> CLOUD_PROTOCOL_GEMINI
  CLOUD_PROVIDER_ANTHROPIC -> CLOUD_PROTOCOL_ANTHROPIC
  else -> CLOUD_PROTOCOL_OPENAI
}

fun defaultCloudEndpoint(providerId: String): String =
  when (providerId) {
    CLOUD_PROVIDER_EAY -> "https://api.eay.my.id/v1"
    CLOUD_PROVIDER_OPENAI_COMPATIBLE -> "https://api.openai.com/v1"
    CLOUD_PROVIDER_OPENROUTER -> "https://openrouter.ai/api/v1"
    CLOUD_PROVIDER_GROQ -> "https://api.groq.com/openai/v1"
    CLOUD_PROVIDER_DEEPSEEK -> "https://api.deepseek.com"
    CLOUD_PROVIDER_QWEN -> "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
    CLOUD_PROVIDER_ANTHROPIC -> "https://api.anthropic.com/v1"
    CLOUD_PROVIDER_CUSTOM -> ""
    else -> "https://generativelanguage.googleapis.com/v1beta"
  }

fun defaultCloudTextModel(providerId: String): String =
  when (providerId) {
    CLOUD_PROVIDER_EAY -> ""
    CLOUD_PROVIDER_OPENAI_COMPATIBLE -> "gpt-4o-mini"
    CLOUD_PROVIDER_OPENROUTER -> "openai/gpt-4o-mini"
    CLOUD_PROVIDER_GROQ -> "llama-3.3-70b-versatile"
    CLOUD_PROVIDER_DEEPSEEK -> "deepseek-flash"
    CLOUD_PROVIDER_QWEN -> "qwen-plus"
    CLOUD_PROVIDER_ANTHROPIC -> "claude-sonnet-4-5"
    CLOUD_PROVIDER_CUSTOM -> ""
    else -> "gemini-2.5-flash"
  }

fun defaultCloudImageModel(providerId: String): String =
  when (providerId) {
    CLOUD_PROVIDER_OPENAI_COMPATIBLE -> "gpt-image-1"
    CLOUD_PROVIDER_GEMINI -> "gemini-2.5-flash-image"
    else -> ""
  }

/** Returns true only for model families with a known thinking-effort request parameter. */
fun supportsCloudThinkingEffort(providerId: String, modelId: String): Boolean {
  val id = modelId.trim().lowercase()
  if (id.isEmpty()) return false
  if (providerId == CLOUD_PROVIDER_EAY) return EAY_MODEL_PRESETS.any { it.id == id }
  return when (providerId) {
    CLOUD_PROVIDER_GEMINI ->
      id.startsWith("gemini-2.5") || id.startsWith("gemini-3") || "thinking" in id
    CLOUD_PROVIDER_OPENAI_COMPATIBLE, CLOUD_PROVIDER_OPENROUTER, CLOUD_PROVIDER_GROQ,
    CLOUD_PROVIDER_DEEPSEEK, CLOUD_PROVIDER_QWEN, CLOUD_PROVIDER_CUSTOM ->
      id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4") ||
        id.startsWith("gpt-5")
    else -> false
  }
}

/** Runtime-ready snapshot of the locally stored cloud provider settings. */
data class CloudProviderConfig(
  val providerId: String,
  val endpoint: String,
  val modelId: String,
  val apiKey: String,
  val imageModelId: String = "",
  val thinkingEffort: String = CLOUD_THINKING_DEFAULT,
  val protocol: String = defaultCloudProtocol(providerId),
) {
  val isConfigured: Boolean
    get() = providerId.isNotBlank() && endpoint.isNotBlank() && modelId.isNotBlank() && apiKey.isNotBlank()

  val isImageModelConfigured: Boolean
    get() = imageModelId.isNotBlank()
}

/** Stores the provider API key encrypted with a key held by Android Keystore. */
object CloudApiKeyVault {
  private const val PREFERENCES_NAME = "cloud_provider_credentials"
  private const val ENCRYPTED_API_KEY = "encrypted_api_key"
  private const val KEY_PROVIDER = "key_provider"
  private const val KEY_ORIGIN = "key_origin"
  private const val KEY_ALIAS = "gallery_cloud_provider_api_key"
  private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
  private const val TRANSFORMATION = "AES/GCM/NoPadding"
  private const val GCM_TAG_LENGTH_BITS = 128
  private const val GCM_IV_LENGTH_BYTES = 12

  fun hasSavedKey(context: Context): Boolean = preferences(context).contains(ENCRYPTED_API_KEY)

  fun save(context: Context, apiKey: String, providerId: String, endpoint: String) {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
    val encrypted = cipher.doFinal(apiKey.toByteArray(StandardCharsets.UTF_8))
    val payload = cipher.iv + encrypted
    preferences(context)
      .edit()
      .putString(ENCRYPTED_API_KEY, Base64.encodeToString(payload, Base64.NO_WRAP))
      .putString(KEY_PROVIDER, providerId)
      .putString(KEY_ORIGIN, cloudEndpointOrigin(endpoint))
      .apply()
  }

  /** Claims an older unscoped encrypted key for the provider/endpoint already stored on device. */
  fun bindLegacyKey(context: Context, providerId: String, endpoint: String) {
    val prefs = preferences(context)
    if (prefs.contains(ENCRYPTED_API_KEY) && !prefs.contains(KEY_PROVIDER) && !prefs.contains(KEY_ORIGIN)) {
      prefs.edit().putString(KEY_PROVIDER, providerId).putString(KEY_ORIGIN, cloudEndpointOrigin(endpoint)).apply()
    }
  }

  fun hasSavedKeyFor(context: Context, providerId: String, endpoint: String): Boolean =
    hasSavedKey(context) && preferences(context).getString(KEY_PROVIDER, null) == providerId &&
      preferences(context).getString(KEY_ORIGIN, null) == cloudEndpointOrigin(endpoint)

  fun read(context: Context, providerId: String, endpoint: String): String? =
    if (hasSavedKeyFor(context, providerId, endpoint)) read(context) else null

  fun read(context: Context): String? {
    val encodedPayload = preferences(context).getString(ENCRYPTED_API_KEY, null) ?: return null
    return try {
      val payload = Base64.decode(encodedPayload, Base64.NO_WRAP)
      if (payload.size <= GCM_IV_LENGTH_BYTES) return null
      val iv = payload.copyOfRange(0, GCM_IV_LENGTH_BYTES)
      val encrypted = payload.copyOfRange(GCM_IV_LENGTH_BYTES, payload.size)
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(
        Cipher.DECRYPT_MODE,
        getOrCreateKey(),
        GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
      )
      String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    } catch (_: Exception) {
      null
    }
  }

  fun remove(context: Context) {
    preferences(context).edit().remove(ENCRYPTED_API_KEY).remove(KEY_PROVIDER).remove(KEY_ORIGIN).apply()
  }

  private fun preferences(context: Context) =
    context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  private fun getOrCreateKey(): SecretKey {
    val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    if (existingKey != null) return existingKey

    val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
    keyGenerator.init(
      KeyGenParameterSpec.Builder(
          KEY_ALIAS,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setRandomizedEncryptionRequired(true)
        .build()
    )
    return keyGenerator.generateKey()
  }
}

fun cloudEndpointOrigin(endpoint: String): String {
  return try {
    val uri = java.net.URI(endpoint.trim())
    val scheme = uri.scheme?.lowercase() ?: return ""
    val host = uri.host?.lowercase() ?: return ""
    val port = if (uri.port == -1) "" else ":${uri.port}"
    "$scheme://$host$port"
  } catch (_: Exception) { "" }
}

/** Loads all runtime settings in one place; API keys are decrypted only when explicitly needed. */
fun loadCloudProviderConfig(context: Context, dataStoreRepository: DataStoreRepository): CloudProviderConfig {
  val providerId = dataStoreRepository.readSecret(CLOUD_PROVIDER_KEY) ?: CLOUD_PROVIDER_GEMINI
  val savedEndpoint = dataStoreRepository.readSecret(CLOUD_ENDPOINT_KEY) ?: defaultCloudEndpoint(providerId)
  CloudApiKeyVault.bindLegacyKey(context, providerId, savedEndpoint)
  val textModel =
    dataStoreRepository.readSecret(CLOUD_TEXT_MODEL_KEY)
      ?: dataStoreRepository.readSecret(CLOUD_MODEL_KEY)
      ?: defaultCloudTextModel(providerId)
  return CloudProviderConfig(
    providerId = providerId,
    endpoint = savedEndpoint,
    modelId = textModel,
    apiKey = CloudApiKeyVault.read(context, providerId,
      savedEndpoint).orEmpty(),
    imageModelId =
      dataStoreRepository.readSecret(CLOUD_IMAGE_MODEL_KEY) ?: defaultCloudImageModel(providerId),
    thinkingEffort =
      dataStoreRepository.readSecret(CLOUD_THINKING_EFFORT_KEY) ?: CLOUD_THINKING_DEFAULT,
    protocol = dataStoreRepository.readSecret(CLOUD_PROTOCOL_KEY) ?: defaultCloudProtocol(providerId),
  )
}
