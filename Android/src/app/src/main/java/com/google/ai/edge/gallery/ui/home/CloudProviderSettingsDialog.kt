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

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.data.DataStoreRepository

private data class CloudProviderOption(
  val id: String,
  val label: String,
  val defaultEndpoint: String,
  val defaultTextModel: String,
  val defaultImageModel: String,
  val detail: String,
)

private val cloudProviderOptions =
  listOf(
    CloudProviderOption(
      id = CLOUD_PROVIDER_GEMINI,
      label = "Google Gemini API",
      defaultEndpoint = defaultCloudEndpoint(CLOUD_PROVIDER_GEMINI),
      defaultTextModel = defaultCloudTextModel(CLOUD_PROVIDER_GEMINI),
      defaultImageModel = defaultCloudImageModel(CLOUD_PROVIDER_GEMINI),
      detail = "Gemini models with image input",
    ),
    CloudProviderOption(
      id = CLOUD_PROVIDER_OPENAI_COMPATIBLE,
      label = "OpenAI-compatible",
      defaultEndpoint = defaultCloudEndpoint(CLOUD_PROVIDER_OPENAI_COMPATIBLE),
      defaultTextModel = defaultCloudTextModel(CLOUD_PROVIDER_OPENAI_COMPATIBLE),
      defaultImageModel = defaultCloudImageModel(CLOUD_PROVIDER_OPENAI_COMPATIBLE),
      detail = "OpenAI API or a compatible endpoint",
    ),
  )

/** Full-screen cloud provider configuration. Name retained for call-site compatibility. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CloudProviderSettingsDialog(
  dataStoreRepository: DataStoreRepository,
  onDismiss: () -> Unit,
  onSaved: () -> Unit = {},
) {
  val context = LocalContext.current
  val savedProviderId = remember { dataStoreRepository.readSecret(CLOUD_PROVIDER_KEY) }
  val initialProvider =
    cloudProviderOptions.firstOrNull { it.id == savedProviderId } ?: cloudProviderOptions.first()
  var providerId by remember { mutableStateOf(initialProvider.id) }
  var endpoint by remember {
    mutableStateOf(
      dataStoreRepository.readSecret(CLOUD_ENDPOINT_KEY) ?: initialProvider.defaultEndpoint
    )
  }
  var textModel by remember {
    mutableStateOf(
      dataStoreRepository.readSecret(CLOUD_TEXT_MODEL_KEY)
        ?: dataStoreRepository.readSecret(CLOUD_MODEL_KEY)
        ?: initialProvider.defaultTextModel
    )
  }
  var imageModel by remember {
    mutableStateOf(dataStoreRepository.readSecret(CLOUD_IMAGE_MODEL_KEY) ?: initialProvider.defaultImageModel)
  }
  var thinkingEffort by remember {
    mutableStateOf(dataStoreRepository.readSecret(CLOUD_THINKING_EFFORT_KEY) ?: CLOUD_THINKING_DEFAULT)
  }
  var apiKey by remember { mutableStateOf("") }
  var hasSavedApiKey by remember { mutableStateOf(CloudApiKeyVault.hasSavedKey(context)) }
  var removeSavedApiKey by remember { mutableStateOf(false) }
  var saveError by remember { mutableStateOf<String?>(null) }
  val selectedProvider = cloudProviderOptions.first { it.id == providerId }
  val canSave = endpoint.isNotBlank() && textModel.isNotBlank() && imageModel.isNotBlank()

  BackHandler(onBack = onDismiss)

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    contentWindowInsets = WindowInsets.safeDrawing,
    topBar = {
      TopAppBar(
        title = { Text("Cloud model") },
        navigationIcon = {
          IconButton(onClick = onDismiss) {
            Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          TextButton(
            enabled = canSave,
            onClick = {
              val hasKeyAfterSave =
                removeSavedApiKey || apiKey.isNotBlank() || (hasSavedApiKey && !removeSavedApiKey)
              if (!hasKeyAfterSave) {
                saveError = "Enter an API key to use a cloud model."
                return@TextButton
              }
              try {
                dataStoreRepository.saveSecret(CLOUD_PROVIDER_KEY, providerId)
                dataStoreRepository.saveSecret(CLOUD_ENDPOINT_KEY, endpoint.trim())
                dataStoreRepository.saveSecret(CLOUD_MODEL_KEY, textModel.trim())
                dataStoreRepository.saveSecret(CLOUD_TEXT_MODEL_KEY, textModel.trim())
                dataStoreRepository.saveSecret(CLOUD_IMAGE_MODEL_KEY, imageModel.trim())
                dataStoreRepository.saveSecret(CLOUD_THINKING_EFFORT_KEY, thinkingEffort)
                when {
                  removeSavedApiKey -> {
                    CloudApiKeyVault.remove(context)
                    hasSavedApiKey = false
                  }
                  apiKey.isNotBlank() -> {
                    CloudApiKeyVault.save(context, apiKey.trim())
                    hasSavedApiKey = true
                    apiKey = ""
                  }
                }
                onSaved()
                onDismiss()
              } catch (_: Exception) {
                saveError = "Could not save the cloud settings on this device. Please try again."
              }
            },
          ) {
            Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Save")
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.surface,
        ),
      )
    },
  ) { innerPadding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(innerPadding)
          .consumeWindowInsets(innerPadding)
          .imePadding()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 20.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      CloudSettingsIntro()

      SettingsSection(
        title = "Provider",
        subtitle = "Choose the service that will receive your requests.",
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          cloudProviderOptions.forEach { option ->
            val selected = providerId == option.id
            val containerColor by
              animateColorAsState(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow,
                label = "provider-card-color",
              )
            val chooseProvider = {
              val oldProvider = selectedProvider
              providerId = option.id
              if (endpoint == oldProvider.defaultEndpoint) endpoint = option.defaultEndpoint
              if (textModel == oldProvider.defaultTextModel) textModel = option.defaultTextModel
              if (imageModel == oldProvider.defaultImageModel) imageModel = option.defaultImageModel
              saveError = null
            }
            Card(
              modifier = Modifier.fillMaxWidth().clickable(onClick = chooseProvider),
              colors = CardDefaults.cardColors(containerColor = containerColor),
              shape = RoundedCornerShape(18.dp),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                RadioButton(selected = selected, onClick = chooseProvider)
                Column(
                  modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                  verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                  Text(option.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                  Text(option.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
              }
            }
          }
        }
      }

      SettingsSection(title = "Connection", subtitle = "Requests are sent directly to this endpoint.") {
        OutlinedTextField(
          value = endpoint,
          onValueChange = { endpoint = it; saveError = null },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          label = { Text("API base URL") },
        )
      }

      SettingsSection(title = "Models", subtitle = "Configure distinct models for conversation and image generation.") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          OutlinedTextField(
            value = textModel,
            onValueChange = { textModel = it; saveError = null },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.TextFields, contentDescription = null) },
            label = { Text("Text model ID") },
            supportingText = { Text("Used for conversation and tool decisions") },
          )
          OutlinedTextField(
            value = imageModel,
            onValueChange = { imageModel = it; saveError = null },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Image, contentDescription = null) },
            label = { Text("Image model ID") },
            supportingText = { Text("Used only when the assistant calls createImage") },
          )
          Text(
            "Image generation uses the image model above. Video generation is not available here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      val supportsThinking = supportsCloudThinkingEffort(providerId, textModel)
      SettingsSection(title = "Thinking effort", subtitle = "Tune reasoning for supported cloud models.") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Psychology, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("Reasoning level", style = MaterialTheme.typography.titleSmall)
          }
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            listOf(
              CLOUD_THINKING_DEFAULT to "Default",
              CLOUD_THINKING_LOW to "Low",
              CLOUD_THINKING_MEDIUM to "Medium",
              CLOUD_THINKING_HIGH to "High",
            ).forEach { (value, label) ->
              FilterChip(
                selected = thinkingEffort == value,
                enabled = supportsThinking,
                onClick = { thinkingEffort = value },
                label = { Text(label) },
              )
            }
          }
          Text(
            if (supportsThinking) "Sent only to recognized reasoning models."
            else "Select a supported reasoning model to adjust this setting.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      SettingsSection(title = "API key", subtitle = "Keep your provider credentials protected on this device.") {
        OutlinedTextField(
          value = apiKey,
          onValueChange = {
            apiKey = it
            saveError = null
            if (it.isNotBlank()) removeSavedApiKey = false
          },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
          label = { Text("API key") },
          placeholder = { Text(if (hasSavedApiKey) "Key saved on this device" else "Enter API key") },
        )
        if (hasSavedApiKey && !removeSavedApiKey) {
          TextButton(
            onClick = {
              apiKey = ""
              removeSavedApiKey = true
              saveError = null
            }
          ) { Text("Remove saved API key") }
        }
        if (removeSavedApiKey) {
          TextButton(onClick = { removeSavedApiKey = false }) { Text("Keep saved key") }
        }
        if (saveError != null) {
          Text(
            text = saveError.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
          Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
          Text(
            "Your API key is encrypted on this device. Cloud inference uses your provider account and may incur charges.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      Spacer(Modifier.size(8.dp))
    }
  }
}

@Composable
private fun CloudSettingsIntro() {
  val transition = rememberInfiniteTransition(label = "cloud-settings-icon")
  val pulse by
    transition.animateFloat(
      initialValue = 0.96f,
      targetValue = 1.04f,
      animationSpec = infiniteRepeatable(animation = tween(1500), repeatMode = RepeatMode.Reverse),
      label = "cloud-settings-pulse",
    )
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    shape = RoundedCornerShape(24.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(20.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Icon(
        Icons.Rounded.Cloud,
        contentDescription = null,
        modifier = Modifier.size(42.dp).graphicsLayer { scaleX = pulse; scaleY = pulse },
        tint = MaterialTheme.colorScheme.primary,
      )
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Connect a cloud model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
          "Set up the provider, model IDs, and reasoning options for Agent Skills.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun SettingsSection(
  title: String,
  subtitle: String? = null,
  content: @Composable () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    shape = RoundedCornerShape(22.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) {
          Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      content()
    }
  }
}
