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

fun defaultCloudEndpoint(providerId: String): String =
  when (providerId) {
    CLOUD_PROVIDER_OPENAI_COMPATIBLE -> "https://api.openai.com/v1"
    else -> "https://generativelanguage.googleapis.com/v1beta"
  }

fun defaultCloudTextModel(providerId: String): String =
  when (providerId) {
    CLOUD_PROVIDER_OPENAI_COMPATIBLE -> "gpt-4o-mini"
    else -> "gemini-2.5-flash"
  }

fun defaultCloudImageModel(providerId: String): String =
  when (providerId) {
    CLOUD_PROVIDER_OPENAI_COMPATIBLE -> "gpt-image-1"
    else -> "gemini-2.5-flash-image"
  }

/** Returns true only for model families with a known thinking-effort request parameter. */
fun supportsCloudThinkingEffort(providerId: String, modelId: String): Boolean {
  val id = modelId.trim().lowercase()
  if (id.isEmpty()) return false
  return when (providerId) {
    CLOUD_PROVIDER_GEMINI ->
      id.startsWith("gemini-2.5") || id.startsWith("gemini-3") || "thinking" in id
    CLOUD_PROVIDER_OPENAI_COMPATIBLE ->
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
  private const val KEY_ALIAS = "gallery_cloud_provider_api_key"
  private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
  private const val TRANSFORMATION = "AES/GCM/NoPadding"
  private const val GCM_TAG_LENGTH_BITS = 128
  private const val GCM_IV_LENGTH_BYTES = 12

  fun hasSavedKey(context: Context): Boolean = preferences(context).contains(ENCRYPTED_API_KEY)

  fun save(context: Context, apiKey: String) {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
    val encrypted = cipher.doFinal(apiKey.toByteArray(StandardCharsets.UTF_8))
    val payload = cipher.iv + encrypted
    preferences(context)
      .edit()
      .putString(ENCRYPTED_API_KEY, Base64.encodeToString(payload, Base64.NO_WRAP))
      .apply()
  }

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
    preferences(context).edit().remove(ENCRYPTED_API_KEY).apply()
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

/** Loads all runtime settings in one place; API keys are decrypted only when explicitly needed. */
fun loadCloudProviderConfig(context: Context, dataStoreRepository: DataStoreRepository): CloudProviderConfig {
  val providerId = dataStoreRepository.readSecret(CLOUD_PROVIDER_KEY) ?: CLOUD_PROVIDER_GEMINI
  val textModel =
    dataStoreRepository.readSecret(CLOUD_TEXT_MODEL_KEY)
      ?: dataStoreRepository.readSecret(CLOUD_MODEL_KEY)
      ?: defaultCloudTextModel(providerId)
  return CloudProviderConfig(
    providerId = providerId,
    endpoint = dataStoreRepository.readSecret(CLOUD_ENDPOINT_KEY) ?: defaultCloudEndpoint(providerId),
    modelId = textModel,
    apiKey = CloudApiKeyVault.read(context).orEmpty(),
    imageModelId =
      dataStoreRepository.readSecret(CLOUD_IMAGE_MODEL_KEY) ?: defaultCloudImageModel(providerId),
    thinkingEffort =
      dataStoreRepository.readSecret(CLOUD_THINKING_EFFORT_KEY) ?: CLOUD_THINKING_DEFAULT,
  )
}
