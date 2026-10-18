/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.common

import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.agentchat.agentSkillTopK
import com.google.ai.edge.gallery.customtasks.agentchat.agentSkillTopKAdjusted
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Config
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.ModelUtils
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.convertValueToTargetType
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.ui.home.CLOUD_ENDPOINT_KEY
import com.google.ai.edge.gallery.ui.home.CLOUD_IMAGE_MODEL_KEY
import com.google.ai.edge.gallery.ui.home.CLOUD_MODEL_KEY
import com.google.ai.edge.gallery.ui.home.CLOUD_PROTOCOL_KEY
import com.google.ai.edge.gallery.ui.home.CLOUD_THINKING_DEFAULT
import com.google.ai.edge.gallery.ui.home.CLOUD_THINKING_EFFORT_KEY
import com.google.ai.edge.gallery.ui.home.CLOUD_PROVIDER_EAY
import com.google.ai.edge.gallery.ui.home.CLOUD_PROVIDER_KEY
import com.google.ai.edge.gallery.ui.home.CLOUD_TEXT_MODEL_KEY
import com.google.ai.edge.gallery.ui.home.EAY_MODEL_PRESETS
import com.google.ai.edge.gallery.ui.home.eayModelSelectionSettings
import com.google.ai.edge.gallery.ui.home.cloudProfileKey
import com.google.ai.edge.gallery.ui.home.normalizedCloudTextModel
import com.google.ai.edge.gallery.ui.home.cloudModelDisplayName
import com.google.ai.edge.gallery.ui.home.defaultCloudEndpoint
import com.google.ai.edge.gallery.ui.home.defaultCloudProtocol
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.GalleryGlassIconButton
import com.google.ai.edge.gallery.ui.theme.galleryGlass
import dev.mttmcknn.seaglass.SeaGlassShape
import com.google.ai.edge.litertlm.ModelInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPageAppBar(
  task: Task,
  model: Model,
  modelManagerViewModel: ModelManagerViewModel,
  onBackClicked: () -> Unit,
  onModelSelected: (prev: Model, cur: Model) -> Unit,
  inProgress: Boolean,
  modelPreparing: Boolean,
  modifier: Modifier = Modifier,
  hideModelSelector: Boolean = false,
  useThemeColor: Boolean = false,
  onConfigChanged: (oldConfigValues: Map<String, Any>, newConfigValues: Map<String, Any>) -> Unit =
    { _, _ ->
    },
  allowEditingSystemPrompt: Boolean = false,
  curSystemPrompt: String = "",
  defaultSystemPrompt: String = task.defaultSystemPrompt,
  onSystemPromptChanged: (String) -> Unit = {},
  shouldShowHistoryButton: Boolean = false,
  onHistoryClicked: (Model) -> Unit = {},
  isAutoCompactEnabled: Boolean = false,
  showBackButton: Boolean = true,
  showCloudSettingsButton: Boolean = false,
  onCloudSettingsClicked: () -> Unit = {},
) {
  var showConfigDialog by remember { mutableStateOf(false) }
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[model.name]
  val initStatus by model.initStatusFlow.collectAsStateWithLifecycle()
  val isModelInitializing = initStatus is Model.InitializationStatus.Initializing
  val isModelInitialized = initStatus is Model.InitializationStatus.Initialized
  val isCloudAgent = task.id == BuiltInTaskId.LLM_AGENT_CHAT && model.name == "cloud-agent-skills"

  CenterAlignedTopAppBar(
    title = {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        // Task type.
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          val tintColor =
            if (isCloudAgent) MaterialTheme.colorScheme.primary
            else if (useThemeColor) MaterialTheme.colorScheme.onSurface
            else getTaskIconColor(task = task)
          val icon = task.icon ?: task.iconVectorResourceId?.let { ImageVector.vectorResource(it) }
          if (icon != null) {
            Icon(icon, tint = tintColor, modifier = Modifier.size(24.dp), contentDescription = null)
          }
          Text(
            task.label, style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }

        // Model chips pager.
        if (isCloudAgent) {
          val repository = modelManagerViewModel.dataStoreRepository
          val savedProvider by remember(repository) { repository.observeSecret(CLOUD_PROVIDER_KEY) }.collectAsStateWithLifecycle(
            initialValue = repository.readSecret(CLOUD_PROVIDER_KEY) ?: CLOUD_PROVIDER_EAY,
          )
          val savedTextModel by remember(repository) { repository.observeSecret(CLOUD_TEXT_MODEL_KEY) }
            .collectAsStateWithLifecycle(initialValue = repository.readSecret(CLOUD_TEXT_MODEL_KEY))
          val legacyModel by remember(repository) { repository.observeSecret(CLOUD_MODEL_KEY) }
            .collectAsStateWithLifecycle(initialValue = repository.readSecret(CLOUD_MODEL_KEY))
          val provider = savedProvider?.takeIf { it.isNotBlank() } ?: CLOUD_PROVIDER_EAY
          val modelId = normalizedCloudTextModel(provider, savedTextModel ?: legacyModel)
          var cloudMenuExpanded by remember { mutableStateOf(false) }
          val coroutineScope = rememberCoroutineScope()

          Box {
            Surface(
              onClick = { cloudMenuExpanded = true },
              enabled = !inProgress,
              modifier = Modifier.galleryGlass(seed = 311, shape = SeaGlassShape.Capsule),
              shape = RoundedCornerShape(50),
              color = Color.Transparent,
              contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
              Row(
                modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
              ) {
                Text(
                  cloudModelDisplayName(provider, modelId).ifBlank { "Choose model" },
                  style = MaterialTheme.typography.labelSmall,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                  Icons.Rounded.ArrowDropDown,
                  contentDescription = "Choose cloud model",
                  modifier = Modifier.size(16.dp),
                )
              }
            }

            DropdownMenu(
              expanded = cloudMenuExpanded,
              onDismissRequest = { cloudMenuExpanded = false },
            ) {
              EAY_MODEL_PRESETS.forEach { preset ->
                DropdownMenuItem(
                  text = { Text(preset.label) },
                  onClick = {
                    cloudMenuExpanded = false
                    coroutineScope.launch(Dispatchers.IO) {
                      try {
                        val currentProvider = repository.readSecret(CLOUD_PROVIDER_KEY) ?: CLOUD_PROVIDER_EAY
                        val imageModel = if (currentProvider == CLOUD_PROVIDER_EAY)
                          repository.readSecret(CLOUD_IMAGE_MODEL_KEY)
                        else repository.readSecret(cloudProfileKey(CLOUD_PROVIDER_EAY, "image_model"))
                        val eayThinkingEffort = if (currentProvider == CLOUD_PROVIDER_EAY)
                          repository.readSecret(CLOUD_THINKING_EFFORT_KEY)
                        else repository.readSecret(cloudProfileKey(CLOUD_PROVIDER_EAY, "thinking_effort"))
                        repository.saveSecrets(
                          eayModelSelectionSettings(preset.id, imageModel.orEmpty(),
                            eayThinkingEffort ?: CLOUD_THINKING_DEFAULT)
                        )
                      } catch (e: CancellationException) {
                        throw e
                      } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                          Toast.makeText(
                            context.applicationContext,
                            "Could not save cloud model selection",
                            Toast.LENGTH_SHORT,
                          ).show()
                        }
                      }
                    }
                  },
                )
              }
              if (showCloudSettingsButton) {
                DropdownMenuItem(
                  text = { Text("Cloud settings…") },
                  onClick = {
                    cloudMenuExpanded = false
                    onCloudSettingsClicked()
                  },
                )
              }
            }
          }
        } else if (!hideModelSelector) {
          val enableModelPickerChip = !isModelInitializing && !inProgress
          ModelPickerChip(
            enabled = enableModelPickerChip,
            task = task,
            initialModel = model,
            modelManagerViewModel = modelManagerViewModel,
            onModelSelected = onModelSelected,
          )
        }
      }
    },
    modifier = modifier,
    // The back button.
    navigationIcon =
      if (showCloudSettingsButton) {
        {
          GalleryGlassIconButton(onClick = onCloudSettingsClicked, enabled = !inProgress, seed = 312) {
            Icon(Icons.Rounded.Settings, contentDescription = "Settings")
          }
        }
      } else if (showBackButton) {
        {
          val enableBackButton = !isModelInitializing && !inProgress
          GalleryGlassIconButton(onClick = onBackClicked, enabled = enableBackButton, seed = 313) {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
              contentDescription = stringResource(R.string.cd_navigate_back_icon),
            )
          }
        }
      } else {
        {}
      },
    // The config button for the model (if existed).
    actions = {
      val downloadSucceeded = curDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
      val showConfigButton = !isCloudAgent && model.configs.isNotEmpty() && downloadSucceeded
      if (showConfigButton) {
        val enableConfigButton = !isModelInitializing && !inProgress && isModelInitialized
        GalleryGlassIconButton(onClick = { showConfigDialog = true }, enabled = enableConfigButton, seed = 314) {
          Icon(
            imageVector = Icons.Rounded.Tune,
            contentDescription = stringResource(R.string.cd_model_settings_icon),
          )
        }
      }
      if (downloadSucceeded && shouldShowHistoryButton) {
        val enableHistoryButton = !isModelInitializing && !modelPreparing && !inProgress && isModelInitialized
        GalleryGlassIconButton(onClick = { onHistoryClicked(model) }, enabled = enableHistoryButton, seed = 315) {
          Icon(
            imageVector = Icons.Rounded.History,
            contentDescription = stringResource(R.string.cd_chat_history),
          )
        }
      }
    },
  )

  // Config dialog.
  if (showConfigDialog) {
    var supportsSpeculativeDecoding by
      remember(model.name, task.id) {
        mutableStateOf(model.configs.any { it.key == ConfigKeys.ENABLE_SPECULATIVE_DECODING })
      }
    var currentConfigs by remember(model.name, task.id) { mutableStateOf(model.configs) }
    var currentConfigValues by remember(model.name, task.id) { mutableStateOf(model.configValues) }

    LaunchedEffect(model.name, task.id) {
      // Check if the model file supports speculative decoding and visual token budget selection.
      // The model file is read off the main thread; the model configs are updated on the main
      // thread.
      try {
        val (specSupport, visionOptions, visionCeiling) =
          withContext(Dispatchers.IO) {
            ModelInfo.from(model.getPath(context)).use { modelInfo ->
              Triple(
                (modelInfo as? ModelInfo.Llm)?.hasSpeculativeDecodingSupport(),
                modelInfo.visionSignatureSelection(),
                modelInfo.maxVisionTokenBudget(),
              )
            }
          }
        if (specSupport != null) {
          supportsSpeculativeDecoding = specSupport
        }
        val unused =
          ModelUtils.configureVisualTokenBudgetForModel(
            model = model,
            shouldEnableImage = task.isVisionEnabled(model),
            rawOptions = visionOptions,
            ceiling = visionCeiling,
          )
        currentConfigs = model.configs
        currentConfigValues = model.configValues
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Ignore exceptions and assume not supported.
      }
    }

    // Remove the reset conversation turn count config for non-tiny-garden tasks.
    //
    // This may happen when user imports a model with "enable tiny garden" turned on and use the
    // model in another non-tiny-garden task.
    val modelConfigs =
      remember(currentConfigs, supportsSpeculativeDecoding, task, model) {
        currentConfigs.toMutableList().apply {
          if (task.id != BuiltInTaskId.LLM_TINY_GARDEN) {
            removeIf { it.key == ConfigKeys.RESET_CONVERSATION_TURN_COUNT }
          }
          if (!task.allowCapability(ModelCapability.LLM_THINKING, model)) {
            removeIf { it.key == ConfigKeys.ENABLE_THINKING }
          }
          if (!task.isVisionEnabled(model)) {
            removeIf { it.key == ConfigKeys.VISUAL_TOKEN_BUDGET }
          }
          if (
            !supportsSpeculativeDecoding ||
              !task.allowCapability(ModelCapability.SPECULATIVE_DECODING, model)
          ) {
            removeIf { it.key == ConfigKeys.ENABLE_SPECULATIVE_DECODING }
          }
          if (task.isContextCompactSupported(model)) {
            if (none { it.key == ConfigKeys.ENABLE_AUTO_CONTEXT_COMPACT }) {
              add(Config.createAutoContextCompactConfig())
            }
          } else {
            removeIf { it.key == ConfigKeys.ENABLE_AUTO_CONTEXT_COMPACT }
          }
        }
      }
    val dialogInitialValues =
      remember(currentConfigValues, task, model, isAutoCompactEnabled) {
        currentConfigValues.toMutableMap().apply {
          if (task.isContextCompactSupported(model)) {
            put(ConfigKeys.ENABLE_AUTO_CONTEXT_COMPACT.label, isAutoCompactEnabled)
          }
        }
      }
    ConfigDialog(
      title = stringResource(R.string.config_dialog_title),
      configs = modelConfigs,
      initialValues = dialogInitialValues,
      onDismissed = { showConfigDialog = false },
      onOk = { curConfigValues, oldSystemPrompt, newSystemPrompt ->
        // Hide config dialog.
        showConfigDialog = false

        // Check if the configs are changed or not. Also check if the model needs to be
        // re-initialized.
        var same = true
        var needReinitialization = false
        for (config in modelConfigs) {
          val key = config.key.label
          val oldValue =
            convertValueToTargetType(
              value = dialogInitialValues.getValue(key),
              valueType = config.valueType,
            )
          val newValue =
            convertValueToTargetType(
              value = curConfigValues.getValue(key),
              valueType = config.valueType,
            )
          if (oldValue != newValue) {
            same = false
            if (config.needReinitialization) {
              needReinitialization = true
            }
            break
          }
        }
        val systemPromptChanged = newSystemPrompt != oldSystemPrompt

        if (!same || systemPromptChanged) {
          firebaseAnalytics?.logEvent(
            GalleryEvent.MODEL_CONFIG_CHANGE.id,
            Bundle().apply {
              putString("model_id", model.name)
              putString("capability_name", task.id)
              putString("model_version", model.downloadInfo.version)
              putString("app_version", BuildConfig.VERSION_NAME)
            },
          )
        }

        if (same) {
          if (systemPromptChanged) {
            onSystemPromptChanged(newSystemPrompt)
          }
          return@ConfigDialog
        }

        // Save the config values to Model (excluding task-level auto context compaction setting).
        val oldConfigValues = model.configValues
        model.prevConfigValues = oldConfigValues
        model.configValues = curConfigValues.filterKeys {
          it != ConfigKeys.ENABLE_AUTO_CONTEXT_COMPACT.label
        }
        when (task.id) {
          BuiltInTaskId.LLM_AGENT_CHAT -> {
            model.agentSkillTopKAdjusted = true
            model.agentSkillTopK = curConfigValues[ConfigKeys.TOPK.label]
          }
        }
        modelManagerViewModel.updateConfigValuesUpdateTrigger()

        if (!task.handleModelConfigChangesInTask) {
          // Force to re-initialize the model with the new configs.
          if (needReinitialization) {
            modelManagerViewModel.initializeModel(
              context = context,
              task = task,
              model = model,
              force = true,
              onDone = {
                if (oldSystemPrompt != newSystemPrompt) {
                  onSystemPromptChanged(newSystemPrompt)
                }
              },
            )
          }

          // Notify.
          onConfigChanged(dialogInitialValues, curConfigValues)
        }
      },
      showSystemPromptEditorTab = allowEditingSystemPrompt && !model.isAiCore,
      defaultSystemPrompt = defaultSystemPrompt,
      curSystemPrompt = curSystemPrompt,
    )
  }
}
