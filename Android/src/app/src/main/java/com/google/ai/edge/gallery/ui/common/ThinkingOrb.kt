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
package com.google.ai.edge.gallery.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme

/** A small, theme-aware glowing orb for model preparation and thinking states. */
@Composable
fun ThinkingOrb(
  modifier: Modifier = Modifier,
  size: Dp = 28.dp,
  active: Boolean = true,
) {
  val transition = rememberInfiniteTransition(label = "thinking-orb")
  val pulse by
    transition.animateFloat(
      initialValue = 0.92f,
      targetValue = 1.06f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
          repeatMode = RepeatMode.Reverse,
        ),
      label = "thinking-orb-pulse",
    )
  val rotation by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 360f,
      animationSpec = infiniteRepeatable(animation = tween(durationMillis = 9000)),
      label = "thinking-orb-rotation",
    )
  val primary = MaterialTheme.colorScheme.primary
  val secondary = MaterialTheme.colorScheme.tertiary
  val glow = MaterialTheme.colorScheme.primaryContainer
  Box(
    modifier =
      modifier
        .size(size)
        .graphicsLayer {
          val motion = if (active) pulse else 1f
          scaleX = motion
          scaleY = motion
          rotationZ = if (active) rotation else 0f
        }
        .drawBehind {
          val drawingSize = this.size
          val center = Offset(drawingSize.width / 2f, drawingSize.height / 2f)
          val radius = this.size.minDimension * 0.46f
          drawCircle(
            brush =
              Brush.radialGradient(
                colors = listOf(glow.copy(alpha = 0.55f), primary.copy(alpha = 0.08f), Color.Transparent),
                center = center,
                radius = radius * 1.75f,
              ),
            radius = radius * 1.75f,
            center = center,
          )
          drawCircle(
            brush =
              Brush.linearGradient(
                colors = listOf(primary, secondary, primary.copy(alpha = 0.86f)),
                start = Offset(center.x - radius, center.y - radius),
                end = Offset(center.x + radius, center.y + radius),
              ),
            radius = radius,
            center = center,
          )
          drawCircle(
            color = Color.White.copy(alpha = 0.72f),
            radius = radius * 0.12f,
            center = Offset(center.x - radius * 0.24f, center.y - radius * 0.28f),
          )
        }
        .semantics { contentDescription = if (active) "AI is thinking" else "AI thought" },
  )
}
