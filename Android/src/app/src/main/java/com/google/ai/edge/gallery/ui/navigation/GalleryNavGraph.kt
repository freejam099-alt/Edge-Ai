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

package com.google.ai.edge.gallery.ui.navigation

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.customtasks.agentchat.CLOUD_AGENT_MODEL
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.isLegacyTasks
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.ui.benchmark.BenchmarkScreen
import com.google.ai.edge.gallery.ui.common.ErrorDialog
import com.google.ai.edge.gallery.ui.common.LocalTestAllowlistDialog
import com.google.ai.edge.gallery.ui.common.ModelPageAppBar
import com.google.ai.edge.gallery.ui.common.chat.ModelDownloadStatusInfoPanel
import com.google.ai.edge.gallery.ui.common.tos.TosViewModel
import com.google.ai.edge.gallery.ui.common.tos.AppTosDialog
import com.google.ai.edge.gallery.ui.home.CloudProviderSettingsDialog
import com.google.ai.edge.gallery.ui.home.loadCloudProviderConfig
import com.google.ai.edge.gallery.ui.home.HomeScreen
import com.google.ai.edge.gallery.ui.modelmanager.GlobalModelManager
import com.google.ai.edge.gallery.ui.modelmanager.MODEL_ALLOWLIST_TEST_FILE_PATH
import com.google.ai.edge.gallery.ui.modelmanager.ModelManager
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.notifications.NotificationsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "AGGalleryNavGraph"
private const val ROUTE_AGENT_SKILLS = "agent_skills_home"
private const val ROUTE_CLOUD_SETTINGS = "cloud_settings"
private const val ROUTE_HOMESCREEN = "homepage"
private const val ROUTE_MODEL_LIST = "model_list"
private const val ROUTE_MODEL = "route_model"
private const val ROUTE_BENCHMARK = "benchmark"
private const val ROUTE_MODEL_MANAGER = "model_manager"
private const val ARG_IMPORT_URL = "import_url"
private const val ROUTE_NOTIFICATIONS = "notifications"
private const val ENTER_ANIMATION_DURATION_MS = 320
private val ENTER_ANIMATION_EASING = FastOutSlowInEasing

private const val EXIT_ANIMATION_DURATION_MS = 280
private val EXIT_ANIMATION_EASING = FastOutSlowInEasing

private fun enterTween(): FiniteAnimationSpec<IntOffset> {
  return tween(
    ENTER_ANIMATION_DURATION_MS,
    easing = ENTER_ANIMATION_EASING,
  )
}

private fun exitTween(): FiniteAnimationSpec<IntOffset> {
  return tween(EXIT_ANIMATION_DURATION_MS, easing = EXIT_ANIMATION_EASING)
}

private fun AnimatedContentTransitionScope<*>.slideEnter(): EnterTransition {
  return slideIntoContainer(
    animationSpec = enterTween(),
    towards = AnimatedContentTransitionScope.SlideDirection.Left,
  )
}

private fun AnimatedContentTransitionScope<*>.slideExit(): ExitTransition {
  return slideOutOfContainer(
    animationSpec = exitTween(),
    towards = AnimatedContentTransitionScope.SlideDirection.Right,
  )
}

private fun AnimatedContentTransitionScope<*>.slideUpEnter(): EnterTransition {
  return slideIntoContainer(
    animationSpec = enterTween(),
    towards = AnimatedContentTransitionScope.SlideDirection.Up,
  )
}

private fun AnimatedContentTransitionScope<*>.slideDownExit(): ExitTransition {
  return slideOutOfContainer(
    animationSpec = exitTween(),
    towards = AnimatedContentTransitionScope.SlideDirection.Down,
  )
}

/** Navigation routes. */
@Composable
fun GalleryNavHost(
  navController: NavHostController,
  modifier: Modifier = Modifier,
  modelManagerViewModel: ModelManagerViewModel,
  tosViewModel: TosViewModel = hiltViewModel(),
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  var pickedTask by remember { mutableStateOf<Task?>(null) }
  var enableHomeScreenAnimation by remember { mutableStateOf(true) }
  var enableModelListAnimation by remember { mutableStateOf(true) }
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()

  // Track whether app is in foreground.
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START,
        Lifecycle.Event.ON_RESUME -> {
          modelManagerViewModel.setAppInForeground(foreground = true)
        }
        Lifecycle.Event.ON_STOP,
        Lifecycle.Event.ON_PAUSE -> {
          modelManagerViewModel.setAppInForeground(foreground = false)
        }
        else -> {
          /* Do nothing for other events */
        }
      }
    }

    lifecycleOwner.lifecycle.addObserver(observer)

    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  NavHost(
    navController = navController,
    startDestination = ROUTE_AGENT_SKILLS,
    enterTransition = { EnterTransition.None },
    exitTransition = { ExitTransition.None },
  ) {
    composable(route = ROUTE_AGENT_SKILLS) {
      AgentSkillsMainEntry(
        modelManagerViewModel = modelManagerViewModel,
        tosViewModel = tosViewModel,
        onOpenSettings = { navController.navigate(ROUTE_CLOUD_SETTINGS) },
      )
    }

    composable(
      route = ROUTE_CLOUD_SETTINGS,
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) {
      CloudProviderSettingsDialog(
        dataStoreRepository = modelManagerViewModel.dataStoreRepository,
        onDismiss = { navController.navigateUp() },
        onSaved = { modelManagerViewModel.selectModel(CLOUD_AGENT_MODEL) },
      )
    }

    // The legacy homepage route now resolves to the cloud-only Agent Skills entry.
    composable(route = ROUTE_HOMESCREEN) {
      AgentSkillsMainEntry(
        modelManagerViewModel = modelManagerViewModel,
        tosViewModel = tosViewModel,
        onOpenSettings = { navController.navigate(ROUTE_CLOUD_SETTINGS) },
      )
    }

    // Model list.
    composable(
      route = ROUTE_MODEL_LIST,
      enterTransition = {
        if (initialState.destination.route == ROUTE_HOMESCREEN) {
          slideEnter()
        } else {
          EnterTransition.None
        }
      },
      exitTransition = {
        if (targetState.destination.route == ROUTE_HOMESCREEN) {
          slideExit()
        } else {
          ExitTransition.None
        }
      },
    ) {
      pickedTask?.let {
        ModelManager(
          viewModel = modelManagerViewModel,
          task = it,
          enableAnimation = enableModelListAnimation,
          onModelClicked = { model ->
            modelManagerViewModel.selectModel(model)
            navController.navigate("$ROUTE_MODEL/${it.id}/${model.name}")
          },
          onBenchmarkClicked = { model ->
            firebaseAnalytics?.logEvent(
              GalleryEvent.CAPABILITY_SELECT.id,
              Bundle().apply { putString("capability_name", "benchmark_${model.name}") },
            )
            navController.navigate("$ROUTE_BENCHMARK/${model.name}")
          },
          navigateUp = {
            enableHomeScreenAnimation = false
            navController.navigateUp()
          },
        )
      }
    }

    // Model page.
    composable(
      route = "$ROUTE_MODEL/{taskId}/{modelName}?query={query}",
      arguments =
        listOf(
          navArgument("taskId") { type = NavType.StringType },
          navArgument("modelName") { type = NavType.StringType },
          navArgument("query") {
            type = NavType.StringType
            nullable = true
            defaultValue = null
          },
        ),
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) { backStackEntry ->
      val modelName = backStackEntry.arguments?.getString("modelName") ?: ""
      val taskId = backStackEntry.arguments?.getString("taskId") ?: ""
      val queryParam = backStackEntry.arguments?.getString("query")
      if (taskId != BuiltInTaskId.LLM_AGENT_CHAT || modelName != CLOUD_AGENT_MODEL.name) {
        AgentSkillsMainEntry(
          modelManagerViewModel = modelManagerViewModel,
          tosViewModel = tosViewModel,
          onOpenSettings = { navController.navigate(ROUTE_CLOUD_SETTINGS) },
        )
        return@composable
      }

      AgentSkillsMainEntry(
        modelManagerViewModel = modelManagerViewModel,
        tosViewModel = tosViewModel,
        onOpenSettings = { navController.navigate(ROUTE_CLOUD_SETTINGS) },
        initialQuery = queryParam,
      )
    }

    // Global model manager page.
    composable(
      route = "$ROUTE_MODEL_MANAGER?$ARG_IMPORT_URL={$ARG_IMPORT_URL}",
      arguments =
        listOf(
          navArgument(ARG_IMPORT_URL) {
            type = NavType.StringType
            nullable = true
            defaultValue = null
          }
        ),
      enterTransition = {
        if (
          initialState.destination.route?.startsWith(ROUTE_BENCHMARK) == true ||
            initialState.destination.route?.startsWith(ROUTE_MODEL) == true
        ) {
          null
        } else {
          slideUpEnter()
        }
      },
      exitTransition = {
        if (
          targetState.destination.route?.startsWith(ROUTE_BENCHMARK) == true ||
            targetState.destination.route?.startsWith(ROUTE_MODEL) == true
        ) {
          null
        } else {
          slideDownExit()
        }
      },
    ) { backStackEntry ->
      val importUrl = backStackEntry.arguments?.getString(ARG_IMPORT_URL)
      GlobalModelManager(
        viewModel = modelManagerViewModel,
        tosViewModel = tosViewModel,
        initialImportUrl = importUrl,
        navigateUp = {
          enableHomeScreenAnimation = false
          navController.navigateUp()
        },
        onModelSelected = { task, model ->
          navController.navigate("$ROUTE_MODEL/${task.id}/${model.name}")
        },
        onBenchmarkClicked = { model ->
          firebaseAnalytics?.logEvent(
            GalleryEvent.CAPABILITY_SELECT.id,
            Bundle().apply { putString("capability_name", "benchmark_${model.name}") },
          )
          navController.navigate("$ROUTE_BENCHMARK/${model.name}")
        },
      )
    }

    // Notifications page.
    composable(
      route = ROUTE_NOTIFICATIONS,
      enterTransition = { slideUpEnter() },
      exitTransition = { slideDownExit() },
    ) {
      NotificationsScreen(navigateUp = { navController.navigateUp() })
    }

    // Benchmark creation page.
    composable(
      route = "$ROUTE_BENCHMARK/{modelName}",
      arguments = listOf(navArgument("modelName") { type = NavType.StringType }),
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) { backStackEntry ->
      val modelName = backStackEntry.arguments?.getString("modelName") ?: ""

      modelManagerViewModel.getModelByName(name = modelName)?.let { model ->
        BenchmarkScreen(
          initialModel = model,
          modelManagerViewModel = modelManagerViewModel,
          onBackClicked = {
            enableModelListAnimation = false
            navController.navigateUp()
          },
        )
      }
    }
  }

  // Shown on top of whichever screen is active, since the app may skip the home screen at launch
  // (e.g. via the STARTUP_SCREEN flag or a deep link).
  if (modelManagerUiState.showLocalTestAllowlistDialog) {
    LocalTestAllowlistDialog(
      filePath = MODEL_ALLOWLIST_TEST_FILE_PATH,
      onDismiss = { modelManagerViewModel.dismissLocalTestAllowlistDialog() },
    )
  }

  // Handle incoming intents for deep links
  val intent = androidx.activity.compose.LocalActivity.current?.intent
  val data = intent?.data
  // Wait until the model manager has been initialized and the tasks are available.
  if (data != null && modelManagerUiState.tasks.isNotEmpty()) {
    intent.data = null
    val uriStr = data.toString()
    Log.d(TAG, "navigation link clicked: $data")
    // 1. Precise model deep links are restricted to Agent Skills in the simplified UI.
    if (uriStr.startsWith("com.google.ai.edge.gallery://model/")) {
      if (data.pathSegments.size >= 2) {
        val taskId = data.pathSegments.get(data.pathSegments.size - 2)
        val modelName = data.pathSegments.last()
        val queryStr = data.getQueryParameter("query")
        modelManagerViewModel
          .getModelByName(name = modelName)
          ?.takeIf { taskId == BuiltInTaskId.LLM_AGENT_CHAT }
          ?.let { model ->
            val route =
              if (!queryStr.isNullOrEmpty()) {
                "$ROUTE_MODEL/${taskId}/${model.name}?query=${Uri.encode(queryStr)}"
              } else {
                "$ROUTE_MODEL/${taskId}/${model.name}"
              }
            navController.navigate(route)
          }
      } else {
        Log.e(TAG, "Malformed deep link URI received: $data")
      }
    } else if (data.host == "global_model_manager" || data.host == "import") {
      Log.w(TAG, "Ignoring model manager deep link in the simplified Agent Skills UI: $data")
    } else {
      // 2. Dynamic task-level deep links: com.google.ai.edge.gallery://<taskId>
      val host = data.host
      if (host == BuiltInTaskId.LLM_AGENT_CHAT) {
        val queryStr = data.getQueryParameter("query")
        val task = modelManagerUiState.tasks.find { it.id == host }
        if (task != null) {
          // Pick the first successfully downloaded model or the default active model for this task
          val defaultModel =
            task.models.firstOrNull { model ->
              modelManagerUiState.modelDownloadStatus[model.name]?.status ==
                ModelDownloadStatusType.SUCCEEDED
            } ?: task.models.firstOrNull()

          if (defaultModel != null) {
            val route =
              if (!queryStr.isNullOrEmpty()) {
                "$ROUTE_MODEL/${task.id}/${defaultModel.name}?query=${Uri.encode(queryStr)}"
              } else {
                "$ROUTE_MODEL/${task.id}/${defaultModel.name}"
              }
            navController.navigate(route)
          } else {
            Log.e(TAG, "No available model found for task: $host")
          }
        }
      }
    }
  }
}

@Composable
private fun AgentSkillsMainEntry(
  modelManagerViewModel: ModelManagerViewModel,
  tosViewModel: TosViewModel,
  onOpenSettings: () -> Unit,
  initialQuery: String? = null,
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val context = LocalContext.current
  val agentChatTask =
    modelManagerViewModel.getCustomTaskByTaskId(BuiltInTaskId.LLM_AGENT_CHAT)
  val task = agentChatTask?.task
  val selectedModel = task?.let { CLOUD_AGENT_MODEL }
  val cloudConfigured =
    loadCloudProviderConfig(context, modelManagerViewModel.dataStoreRepository).isConfigured
  var showTosDialog by remember { mutableStateOf(!tosViewModel.getIsTosAccepted()) }

  LaunchedEffect(task) {
    if (task != null) {
      task.models.removeAll { it.name != CLOUD_AGENT_MODEL.name }
      if (task.models.none { it.name == CLOUD_AGENT_MODEL.name }) {
        task.models.add(CLOUD_AGENT_MODEL)
      }
      modelManagerViewModel.setDownloadStatus(
        CLOUD_AGENT_MODEL,
        ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED),
      )
      modelManagerViewModel.selectModel(CLOUD_AGENT_MODEL)
    }
  }

  when {
    showTosDialog -> {
      AgentSkillsEntryState(
        message = "Review the app terms to continue.",
        onRetry = null,
        onCloudSettingsClicked = onOpenSettings,
      )
    }
    task == null || selectedModel == null -> {
      AgentSkillsEntryState(
        message = "Agent Skills could not be prepared on this device.",
        onRetry = { modelManagerViewModel.loadModelAllowlist() },
        onCloudSettingsClicked = onOpenSettings,
      )
    }
    !cloudConfigured -> {
      AgentSkillsEntryState(
        message = "Connect a cloud provider and save its API key to start chatting.",
        onRetry = null,
        onCloudSettingsClicked = onOpenSettings,
      )
    }
    else -> {
      LaunchedEffect(selectedModel.name) { modelManagerViewModel.selectModel(selectedModel) }
      // AgentChatScreen already owns a Scaffold and app bar. Do not nest a second one.
      agentChatTask?.MainScreen(
        data = CustomTaskDataForBuiltinTask(
          modelManagerViewModel = modelManagerViewModel,
          onNavUp = {},
          initialQuery = initialQuery,
          onOpenSettings = onOpenSettings,
        )
      )
    }
  }

  if (showTosDialog) {
    AppTosDialog(
      onTosAccepted = {
        showTosDialog = false
        tosViewModel.acceptTos()
      }
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentSkillsEntryState(
  message: String,
  onRetry: (() -> Unit)?,
  onCloudSettingsClicked: () -> Unit,
  loading: Boolean = false,
) {
  val pulseTransition = rememberInfiniteTransition(label = "cloud-entry-pulse")
  val iconScale by
    pulseTransition.animateFloat(
      initialValue = 0.97f,
      targetValue = 1.035f,
      animationSpec = infiniteRepeatable(tween(1_900), repeatMode = RepeatMode.Reverse),
      label = "cloud-entry-scale",
    )
  Scaffold(
    topBar = {
      CenterAlignedTopAppBar(
        title = {
          androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              painter = painterResource(R.drawable.agent),
              contentDescription = null,
              tint = com.google.ai.edge.gallery.ui.common.getTaskIconColor(0),
              modifier = Modifier.size(22.dp),
            )
            Text("Agent Skills")
          }
        },
        navigationIcon = {
          IconButton(onClick = onCloudSettingsClicked) {
            Icon(
              imageVector = Icons.Rounded.Settings,
              contentDescription = "Cloud provider settings",
            )
          }
        },
      )
    }
  ) { innerPadding ->
    AnimatedVisibility(
      visible = true,
      enter = fadeIn(tween(280)) + slideInVertically(animationSpec = tween(280)) { it / 16 },
      modifier = Modifier.fillMaxSize(),
    ) {
      Column(
        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        Icon(
          painter = painterResource(R.drawable.agent),
          contentDescription = null,
          tint = com.google.ai.edge.gallery.ui.common.getTaskIconColor(0),
          modifier = Modifier.size(76.dp).scale(iconScale).padding(bottom = 18.dp),
        )
        if (loading) CircularProgressIndicator(modifier = Modifier.padding(bottom = 16.dp))
        Text(
          text = message,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onRetry != null) {
          Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("Retry") }
        }
      }
    }
  }
}

@Composable
private fun CustomTaskScreen(
  task: Task,
  initialModel: Model,
  modelManagerViewModel: ModelManagerViewModel,
  disableAppBarControls: Boolean,
  hideTopBar: Boolean,
  useThemeColor: Boolean,
  onNavigateUp: () -> Unit,
  allowEditingSystemPrompt: Boolean = false,
  curSystemPrompt: String = "",
  defaultSystemPrompt: String = task.defaultSystemPrompt,
  onSystemPromptChanged: (String) -> Unit = {},
  onHistoryClicked: (() -> Unit)? = null,
  showBackButton: Boolean = true,
  handleSystemBack: Boolean = true,
  showCloudSettingsButton: Boolean = false,
  onCloudSettingsClicked: () -> Unit = {},
  content: @Composable (bottomPadding: Dp) -> Unit,
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  // Use currentModel on initial composition to prevent reading stale selectedModel from
  // ViewModel before selectModel() runs. Once ViewModel synchronizes with the current model,
  // use the live model instance from uiState to capture ongoing configuration/state updates.
  var currentModel by remember(initialModel.name) { mutableStateOf(initialModel) }
  val selectedModel =
    if (modelManagerUiState.selectedModel.name == currentModel.name) {
      modelManagerUiState.selectedModel
    } else {
      currentModel
    }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  var navigatingUp by remember { mutableStateOf(false) }
  var showErrorDialog by remember { mutableStateOf(false) }
  var appBarHeight by remember { mutableIntStateOf(0) }

  val handleNavigateUp = {
    navigatingUp = true
    onNavigateUp()
  }

  // Handle system's edge swipe.
  BackHandler(enabled = handleSystemBack) { handleNavigateUp() }

  // Initialize model when model/download state changes.
  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]
  LaunchedEffect(curDownloadStatus, selectedModel.name) {
    if (!navigatingUp) {
      if (curDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED) {
        Log.d(
          TAG,
          "Initializing model '${selectedModel.name}' from CustomTaskScreen launched effect",
        )
        modelManagerViewModel.initializeModel(context, task = task, model = selectedModel)
      }
    }
  }

  val modelInitStatus by selectedModel.initStatusFlow.collectAsState()
  LaunchedEffect(modelInitStatus) {
    showErrorDialog = modelInitStatus is Model.InitializationStatus.Failed
  }

  Scaffold(
    topBar = {
      AnimatedVisibility(
        !hideTopBar,
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
      ) {
        ModelPageAppBar(
          task = task,
          model = selectedModel,
          modelManagerViewModel = modelManagerViewModel,
          inProgress = disableAppBarControls,
          modelPreparing = disableAppBarControls,
          shouldShowHistoryButton = onHistoryClicked != null,
          onHistoryClicked = { onHistoryClicked?.invoke() },
          showBackButton = showBackButton,
          showCloudSettingsButton = showCloudSettingsButton,
          onCloudSettingsClicked = onCloudSettingsClicked,
          useThemeColor = useThemeColor,
          modifier =
            Modifier.onGloballyPositioned { coordinates -> appBarHeight = coordinates.size.height },
          hideModelSelector = task.models.size <= 1,
          onConfigChanged = { _, _ -> },
          onBackClicked = { handleNavigateUp() },
          onModelSelected = { prevModel, newSelectedModel ->
            currentModel = newSelectedModel
            val instanceToCleanUp = prevModel.instance
            scope.launch(Dispatchers.Default) {
              // Clean up prev model.
              if (prevModel.name != newSelectedModel.name) {
                modelManagerViewModel.cleanupModel(
                  context = context,
                  task = task,
                  model = prevModel,
                  instanceToCleanUp = instanceToCleanUp,
                )
              }

              // Update selected model.
              Log.d(TAG, "from model picker. new: ${newSelectedModel.name}")
              modelManagerViewModel.selectModel(model = newSelectedModel)
            }
          },
          allowEditingSystemPrompt = allowEditingSystemPrompt,
          curSystemPrompt = curSystemPrompt,
          defaultSystemPrompt = defaultSystemPrompt,
          onSystemPromptChanged = onSystemPromptChanged,
        )
      }
    }
  ) { innerPadding ->
    // Calculate the target height in Dp for the content's top padding.
    val targetPaddingDp =
      if (!hideTopBar && appBarHeight > 0) {
        // Convert measured pixel height to Dp
        with(LocalDensity.current) { appBarHeight.toDp() }
      } else {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
      }

    // Animate the actual top padding value.
    val animatedTopPadding by
      animateDpAsState(
        targetValue = targetPaddingDp,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "TopPaddingAnimation",
      )

    Box(
      modifier =
        Modifier.padding(
          top = if (!hideTopBar) innerPadding.calculateTopPadding() else animatedTopPadding,
          start = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
          end = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
        )
    ) {
      val curModelDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]
      AnimatedContent(
        targetState = curModelDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
      ) { targetState ->
        when (targetState) {
          // Main UI when model is downloaded.
          true -> content(innerPadding.calculateBottomPadding())
          // Model download
          false ->
            ModelDownloadStatusInfoPanel(
              model = selectedModel,
              task = task,
              modelManagerViewModel = modelManagerViewModel,
            )
        }
      }
    }
  }

  if (showErrorDialog) {
    val initErrorMessage =
      (modelInitStatus as? Model.InitializationStatus.Failed)?.error?.message ?: ""
    ErrorDialog(
      error = initErrorMessage,
      onDismiss = {
        showErrorDialog = false
        onNavigateUp()
      },
    )
  }
}
