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
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.window.PopupProperties
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.ui.common.RotationalLoader
import com.google.ai.edge.gallery.ui.theme.GalleryGlassIconButton
import kotlinx.coroutines.launch

private data class CloudProviderOption(
  val id: String,
  val label: String,
  val defaultEndpoint: String,
  val defaultTextModel: String,
  val defaultImageModel: String,
  val detail: String,
)

private val cloudProviderOptions = listOf(
  CLOUD_PROVIDER_GEMINI to "Google Gemini API",
  CLOUD_PROVIDER_EAY to "EAY",
  CLOUD_PROVIDER_OPENAI_COMPATIBLE to "OpenAI",
  CLOUD_PROVIDER_OPENROUTER to "OpenRouter",
  CLOUD_PROVIDER_GROQ to "Groq",
  CLOUD_PROVIDER_DEEPSEEK to "DeepSeek",
  CLOUD_PROVIDER_QWEN to "Qwen (DashScope)",
  CLOUD_PROVIDER_ANTHROPIC to "Anthropic",
  CLOUD_PROVIDER_CUSTOM to "Custom endpoint",
).map { (id, label) ->
    CloudProviderOption(id, label, defaultCloudEndpoint(id), defaultCloudTextModel(id),
    defaultCloudImageModel(id), when (id) {
      CLOUD_PROVIDER_EAY -> "OpenAI-compatible API; curated Wiki model presets"
      CLOUD_PROVIDER_GEMINI -> "Gemini API; image-capable models may be available"
      CLOUD_PROVIDER_OPENROUTER -> "OpenAI-compatible API; catalog is fetched live"
      CLOUD_PROVIDER_GROQ -> "OpenAI-compatible API; catalog is fetched live"
      CLOUD_PROVIDER_DEEPSEEK -> "OpenAI-compatible API; catalog is fetched live"
      CLOUD_PROVIDER_QWEN -> "OpenAI-compatible API; enter model ID manually if catalog is unavailable"
      CLOUD_PROVIDER_ANTHROPIC -> "Anthropic Messages API"
      CLOUD_PROVIDER_CUSTOM -> "Choose an OpenAI-compatible, Gemini, or Anthropic API"
      else -> "OpenAI-compatible API"
    })
}

/** Full-screen cloud provider configuration. Name retained for call-site compatibility. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CloudProviderSettingsDialog(
  dataStoreRepository: DataStoreRepository,
  onDismiss: () -> Unit,
  onSaved: () -> Unit = {},
) {
  val context = LocalContext.current
  val initialConfig = remember(context, dataStoreRepository) {
    loadCloudProviderConfig(context, dataStoreRepository)
  }
  val initialProvider =
    cloudProviderOptions.firstOrNull { it.id == initialConfig.providerId } ?: cloudProviderOptions.first()
  val initialEndpoint =
    if (initialProvider.id == CLOUD_PROVIDER_EAY) defaultCloudEndpoint(CLOUD_PROVIDER_EAY)
    else initialConfig.endpoint.ifBlank { initialProvider.defaultEndpoint }
  val initialTextModel =
    dataStoreRepository.readSecret(cloudProfileKey(initialProvider.id, "text_model"))
      ?: initialConfig.modelId.ifBlank {
        if (initialProvider.id == CLOUD_PROVIDER_EAY) EAY_MODEL_PRESETS[1].id
        else initialProvider.defaultTextModel
      }
  val initialProfileEndpoint =
    if (initialProvider.id == CLOUD_PROVIDER_EAY) initialEndpoint
    else dataStoreRepository.readSecret(cloudProfileKey(initialProvider.id, "endpoint"))
      ?: initialEndpoint
  var providerId by remember { mutableStateOf(initialProvider.id) }
  var endpoint by remember { mutableStateOf(initialProfileEndpoint) }
  var textModel by remember { mutableStateOf(normalizedCloudTextModel(initialProvider.id, initialTextModel)) }
  var imageModel by remember {
    mutableStateOf(
      dataStoreRepository.readSecret(cloudProfileKey(initialProvider.id, "image_model"))
        ?: initialConfig.imageModelId
    )
  }
  var thinkingEffort by remember {
    mutableStateOf(
      dataStoreRepository.readSecret(cloudProfileKey(initialProvider.id, "thinking_effort"))
        ?: initialConfig.thinkingEffort
    )
  }
  var apiKey by remember { mutableStateOf("") }
  var removeSavedApiKey by remember { mutableStateOf(false) }
  var saveError by remember { mutableStateOf<String?>(null) }
  var protocol by remember {
    mutableStateOf(
      dataStoreRepository.readSecret(cloudProfileKey(initialProvider.id, "protocol"))
        ?: initialConfig.protocol
    )
  }
  var fetchingModels by remember { mutableStateOf(false) }
  var fetchedModels by remember { mutableStateOf(emptyList<CloudModel>()) }
  var modelSearch by remember { mutableStateOf("") }
  var modelPickerExpanded by remember { mutableStateOf(false) }
  var fetchError by remember { mutableStateOf<String?>(null) }
  var fetchNotice by remember { mutableStateOf<String?>(null) }
  val coroutineScope = rememberCoroutineScope()
  val selectedProvider = cloudProviderOptions.first { it.id == providerId }
  val hasMatchingSavedKey = CloudApiKeyVault.hasSavedKeyFor(context, providerId, endpoint)
  val hasBundledApiKey =
    providerId == CLOUD_PROVIDER_EAY && bundledCloudApiKey(providerId, endpoint).isNotBlank()
  val canSave = endpoint.isNotBlank() && textModel.isNotBlank() &&
    (providerId != CLOUD_PROVIDER_EAY || EAY_MODEL_PRESETS.any { it.id == textModel })

  BackHandler(onBack = onDismiss)

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    contentWindowInsets = WindowInsets.safeDrawing,
    topBar = {
      TopAppBar(
        title = { Text("Cloud model") },
        navigationIcon = {
          GalleryGlassIconButton(onClick = onDismiss, seed = 321) {
            Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          TextButton(
            enabled = canSave,
            onClick = {
              val hasKeyAfterSave =
                hasBundledApiKey || apiKey.isNotBlank() ||
                  (hasMatchingSavedKey && !removeSavedApiKey)
              if (!hasKeyAfterSave && !removeSavedApiKey) {
                saveError = "Enter an API key to use a cloud model."
                return@TextButton
              }
              try {
                when {
                  removeSavedApiKey -> {
                    CloudApiKeyVault.remove(context, providerId, endpoint.trim())
                  }
                  apiKey.isNotBlank() -> {
                    CloudApiKeyVault.save(context, apiKey.trim(), providerId, endpoint.trim())
                    apiKey = ""
                  }
                }
                dataStoreRepository.saveSecrets(
                  mapOf(
                    CLOUD_PROVIDER_KEY to providerId,
                    CLOUD_ENDPOINT_KEY to endpoint.trim(),
                    CLOUD_MODEL_KEY to textModel.trim(),
                    CLOUD_TEXT_MODEL_KEY to textModel.trim(),
                    CLOUD_IMAGE_MODEL_KEY to imageModel.trim(),
                    CLOUD_THINKING_EFFORT_KEY to thinkingEffort,
                    CLOUD_PROTOCOL_KEY to protocol,
                    cloudProfileKey(providerId, "endpoint") to endpoint.trim(),
                    cloudProfileKey(providerId, "text_model") to textModel.trim(),
                    cloudProfileKey(providerId, "image_model") to imageModel.trim(),
                    cloudProfileKey(providerId, "thinking_effort") to thinkingEffort,
                    cloudProfileKey(providerId, "protocol") to protocol,
                  )
                )
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
              if (providerId != option.id) {
                providerId = option.id
                endpoint = if (option.id == CLOUD_PROVIDER_EAY) defaultCloudEndpoint(option.id)
                  else dataStoreRepository.readSecret(cloudProfileKey(option.id, "endpoint")) ?: option.defaultEndpoint
                textModel = normalizedCloudTextModel(option.id,
                  dataStoreRepository.readSecret(cloudProfileKey(option.id, "text_model")))
                imageModel = dataStoreRepository.readSecret(cloudProfileKey(option.id, "image_model"))
                  ?: option.defaultImageModel
                thinkingEffort = dataStoreRepository.readSecret(cloudProfileKey(option.id, "thinking_effort"))
                  ?: CLOUD_THINKING_DEFAULT
                protocol = dataStoreRepository.readSecret(cloudProfileKey(option.id, "protocol"))
                  ?: defaultCloudProtocol(option.id)
                apiKey = ""
                removeSavedApiKey = false
                fetchedModels = emptyList()
                modelSearch = ""
                modelPickerExpanded = false
                fetchError = null
                fetchNotice = null
                saveError = null
              }
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
        if (providerId == CLOUD_PROVIDER_CUSTOM) {
          Text("API protocol", style = MaterialTheme.typography.titleSmall)
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(CLOUD_PROTOCOL_OPENAI to "OpenAI-compatible", CLOUD_PROTOCOL_GEMINI to "Gemini", CLOUD_PROTOCOL_ANTHROPIC to "Anthropic").forEach { (value, label) ->
              FilterChip(selected = protocol == value, onClick = { protocol = value; fetchedModels = emptyList(); fetchError = null }, label = { Text(label) })
            }
          }
        }
        OutlinedTextField(
          value = endpoint,
          onValueChange = { endpoint = it; fetchedModels = emptyList(); fetchError = null; fetchNotice = null; saveError = null },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          readOnly = providerId == CLOUD_PROVIDER_EAY,
          label = { Text("API base URL") },
        )
      }

      SettingsSection(title = "Models", subtitle = "Configure distinct models for conversation and image generation.") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          OutlinedTextField(
            value = cloudModelDisplayName(providerId, textModel),
            onValueChange = { textModel = it; saveError = null },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            readOnly = providerId == CLOUD_PROVIDER_EAY,
            leadingIcon = { Icon(Icons.Rounded.TextFields, contentDescription = null) },
            label = { Text(if (providerId == CLOUD_PROVIDER_EAY) "Chat model" else "Text model ID") },
            supportingText = { Text("Used for conversation and tool decisions") },
          )
          OutlinedTextField(
            value = imageModel,
            onValueChange = { imageModel = it; saveError = null },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Image, contentDescription = null) },
            label = { Text("Image model ID") },
            supportingText = { Text("Optional; leave blank when this provider has no image model") },
          )
          if (providerId != CLOUD_PROVIDER_EAY) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = !fetchingModels && endpoint.isNotBlank() && (apiKey.isNotBlank() || hasMatchingSavedKey), onClick = {
              fetchingModels = true; fetchError = null; fetchNotice = null
              val key = apiKey.trim().ifBlank { CloudApiKeyVault.read(context, providerId, endpoint).orEmpty() }
              coroutineScope.launch {
                try { fetchedModels = fetchCloudModels(providerId, protocol, endpoint, key); fetchNotice = "${fetchedModels.size} models fetched from the provider." }
                catch (e: CloudModelFetchException) {
                  fetchError = e.message
                  if (providerId == CLOUD_PROVIDER_QWEN) fetchNotice = "Qwen model IDs can be entered manually; no static list is shown as fetched."
                } catch (_: Exception) { fetchError = "Could not fetch models. Check the API settings and try again." }
                finally { fetchingModels = false }
              }
            }) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fetchingModels) RotationalLoader(size = 16.dp, color = MaterialTheme.colorScheme.onPrimary)
                Text(if (fetchingModels) "Fetching…" else "Fetch models")
              }
            }
            Text("Uses the selected endpoint and matching API key only.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          if (fetchError != null) Text(fetchError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
          if (fetchNotice != null) Text(fetchNotice.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          if (providerId == CLOUD_PROVIDER_EAY || fetchedModels.isNotEmpty()) {
            OutlinedTextField(
              value = modelSearch,
              onValueChange = { modelSearch = it; modelPickerExpanded = true },
              modifier = Modifier.fillMaxWidth(),
              singleLine = true,
              label = { Text("Search models") },
              placeholder = { Text("Search by name or model ID") },
            )
            Box {
              TextButton(onClick = { modelPickerExpanded = true }) {
                val selectedLabel = cloudModelDisplayName(providerId, textModel)
                Text(if (textModel.isBlank()) "Choose default model" else "Default model: $selectedLabel")
              }
              DropdownMenu(
                expanded = modelPickerExpanded,
                onDismissRequest = { modelPickerExpanded = false },
                modifier = Modifier.heightIn(max = 280.dp),
                properties = PopupProperties(focusable = false),
              ) {
                val choices = cloudModelPickerChoices(providerId, fetchedModels.map { it.id }, modelSearch)
                if (choices.isEmpty()) {
                  DropdownMenuItem(text = { Text("No matching models") }, onClick = {}, enabled = false)
                }
                choices.forEach { choice ->
                    DropdownMenuItem(
                      text = { Text(choice.label) },
                      onClick = {
                        textModel = choice.id
                        modelPickerExpanded = false
                        saveError = null
                      },
                    )
                  }
              }
            }
          }
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
        if (hasBundledApiKey) {
          Text(
            "EAY is ready to use with its built-in API key. No key entry is needed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
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
            placeholder = { Text(if (hasMatchingSavedKey) "Key saved for this provider and origin" else "Enter API key for selected provider") },
          )
        }
        if (hasMatchingSavedKey && !removeSavedApiKey) {
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
