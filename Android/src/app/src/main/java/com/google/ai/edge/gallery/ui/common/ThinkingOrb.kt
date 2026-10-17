/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val RocketRed = Color(0xFFE84B3C)
private val RocketDark = Color(0xFFB9232F)
private val RocketGold = Color(0xFFFFC857)

/** Red cinematic rocket reserved for active AI thinking. */
@Composable
fun ThinkingOrb(modifier: Modifier = Modifier, size: Dp = 28.dp, active: Boolean = true) {
  if (active) {
    // Compose infinite transitions automatically use MotionDurationScale (including Android's
    // animator-duration scale), so reduced-motion settings are respected without manual polling.
    val transition = rememberInfiniteTransition(label = "thinking-rocket")
    val flame by transition.animateFloat(0.72f, 1.18f, infiniteRepeatable(tween(440, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "rocket-exhaust")
    val tilt by transition.animateFloat(-7f, 7f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "rocket-flight")
    val aura by transition.animateFloat(0.3f, 0.82f, infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "rocket-aura")
    RocketVisual(modifier, size, flame, tilt, aura, active)
  } else RocketVisual(modifier, size, 0.8f, 0f, 0.3f, false)
}

@Composable
private fun RocketVisual(modifier: Modifier, size: Dp, flame: Float, tilt: Float, aura: Float, active: Boolean) {
  Box(
    modifier = modifier.size(size).drawBehind {
      val center = Offset(this.size.width / 2f, this.size.height / 2f)
      val radius = this.size.minDimension * 0.72f
      drawCircle(Brush.radialGradient(listOf(RocketRed.copy(alpha = aura * 0.5f), RocketRed.copy(alpha = 0.08f), Color.Transparent), center, radius), radius, center)
      if (active) drawCircle(RocketGold.copy(alpha = aura * 0.6f), radius * 0.62f, center, style = Stroke(this.size.minDimension * 0.025f))
    }.graphicsLayer { rotationZ = tilt }.semantics { contentDescription = if (active) "AI is thinking" else "AI thought" }
  ) {
    Canvas(Modifier.fillMaxSize()) {
      val w = this.size.width; val h = this.size.height; val cx = w / 2f
      val exhaust = Path().apply {
        moveTo(cx - w * 0.14f, h * 0.62f)
        cubicTo(cx - w * 0.28f, h * (0.78f + flame * 0.04f), cx - w * 0.08f, h * (0.91f + flame * 0.08f), cx, h * (0.88f + flame * 0.1f))
        cubicTo(cx + w * 0.08f, h * (0.91f + flame * 0.08f), cx + w * 0.28f, h * (0.78f + flame * 0.04f), cx + w * 0.14f, h * 0.62f); close()
      }
      drawPath(exhaust, Brush.verticalGradient(listOf(RocketGold, RocketRed.copy(alpha = 0.8f), Color.Transparent), h * 0.58f, h))
      val hull = Path().apply {
        moveTo(cx, h * 0.08f); cubicTo(cx + w * 0.22f, h * 0.28f, cx + w * 0.2f, h * 0.45f, cx + w * 0.14f, h * 0.65f)
        lineTo(cx - w * 0.14f, h * 0.65f); cubicTo(cx - w * 0.2f, h * 0.45f, cx - w * 0.22f, h * 0.28f, cx, h * 0.08f); close()
      }
      drawPath(hull, Brush.linearGradient(listOf(Color(0xFFFF806B), RocketRed, RocketDark), Offset(cx - w * 0.18f, h * 0.2f), Offset(cx + w * 0.18f, h * 0.68f)))
      drawPath(Path().apply { moveTo(cx, h * 0.1f); cubicTo(cx - w * 0.1f, h * 0.2f, cx - w * 0.12f, h * 0.28f, cx - w * 0.12f, h * 0.32f) }, Color.White.copy(alpha = 0.72f), style = Stroke(w * 0.035f))
      drawCircle(Color(0xFFBDEBFF), w * 0.07f, Offset(cx, h * 0.4f)); drawCircle(Color.White.copy(alpha = 0.8f), w * 0.025f, Offset(cx - w * 0.02f, h * 0.38f))
      val fins = Path().apply {
        moveTo(cx - w * 0.13f, h * 0.5f); lineTo(cx - w * 0.3f, h * 0.68f); lineTo(cx - w * 0.11f, h * 0.64f); close()
        moveTo(cx + w * 0.13f, h * 0.5f); lineTo(cx + w * 0.3f, h * 0.68f); lineTo(cx + w * 0.11f, h * 0.64f); close()
      }
      drawPath(fins, RocketDark)
      drawRoundRect(RocketGold, Offset(cx - w * 0.09f, h * 0.61f), Size(w * 0.18f, h * 0.07f), CornerRadius(w * 0.04f))
      if (active) rotate(tilt * 2f, Offset(cx, h * 0.48f)) {
        drawCircle(RocketGold.copy(alpha = 0.9f), w * 0.035f, Offset(w * 0.12f, h * 0.27f))
        drawCircle(Color.White.copy(alpha = 0.8f), w * 0.025f, Offset(w * 0.86f, h * 0.55f))
      }
    }
  }
}
