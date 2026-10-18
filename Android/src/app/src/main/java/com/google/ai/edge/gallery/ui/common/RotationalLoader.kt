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

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val SPOKE_COUNT = 12

/** A compact iOS-style indeterminate spinner that respects Android's animator-duration setting. */
@Composable
fun RotationalLoader(
  size: Dp,
  modifier: Modifier = Modifier,
  color: Color = MaterialTheme.colorScheme.primary,
) {
  val context = LocalContext.current
  val animationsEnabled = remember {
    Settings.Global.getFloat(
      context.contentResolver,
      Settings.Global.ANIMATOR_DURATION_SCALE,
      1f,
    ) != 0f
  }
  val phase: State<Float> =
    if (animationsEnabled) {
      val transition = rememberInfiniteTransition(label = "rotational-loader")
      transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
        label = "rotational-loader-phase",
      )
    } else {
      remember { mutableStateOf(0f) }
    }

  Canvas(
    modifier =
      modifier.size(size).semantics {
        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
      },
  ) {
    val currentPhase = phase.value
    val canvasSize = this.size
    val center = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
    val outerRadius = minOf(canvasSize.width, canvasSize.height) * 0.42f
    val innerRadius = outerRadius * 0.58f
    val strokeWidth = minOf(canvasSize.width, canvasSize.height) * 0.085f
    for (index in 0 until SPOKE_COUNT) {
      val angle = (2.0 * PI * index / SPOKE_COUNT) - PI / 2.0
      val start = Offset(
        center.x + cos(angle).toFloat() * innerRadius,
        center.y + sin(angle).toFloat() * innerRadius,
      )
      val end = Offset(
        center.x + cos(angle).toFloat() * outerRadius,
        center.y + sin(angle).toFloat() * outerRadius,
      )
      val distance = ((index / SPOKE_COUNT.toFloat() - currentPhase + 1f) % 1f)
      val alpha = 0.18f + 0.82f * (1f - distance)
      drawLine(
        color = color.copy(alpha = color.alpha * alpha),
        start = start,
        end = end,
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
      )
    }
  }
}
