package com.google.ai.edge.gallery.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.mttmcknn.seaglass.SeaGlassShape
import dev.mttmcknn.seaglass.SeaGlassStyle
import dev.mttmcknn.seaglass.SeaGlassTheme
import dev.mttmcknn.seaglass.seaGlass

/** Quiet optics with stable contours avoid unnecessary geometry work during streaming. */
val GalleryGlassRecipe = SeaGlassStyle.Quiet.copy(
  frost = .52f, grain = .26f, tumble = .012f, thickness = 4.dp,
  bulge = .44f, elevation = 4.dp, transmission = .58f,
)

@Composable
fun Modifier.galleryGlass(seed: Int, shape: SeaGlassShape = SeaGlassShape.Rounded): Modifier =
  seaGlass(style = GalleryGlassRecipe.copy(seed = seed), palette = SeaGlassTheme.palette, shape = shape)

/** Native button semantics, 48dp target, and a damped iOS-inspired press response. */
@Composable
fun GalleryGlassIconButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  seed: Int = 101,
  content: @Composable () -> Unit,
) {
  val interactions = remember { MutableInteractionSource() }
  val pressed by interactions.collectIsPressedAsState()
  val scale = animateFloatAsState(
    targetValue = if (pressed && enabled) .94f else 1f,
    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 650f),
    label = "glass-icon-press",
  )
  val palette = SeaGlassTheme.palette
  IconButton(
    onClick = onClick, enabled = enabled, interactionSource = interactions,
    modifier = modifier.size(48.dp).graphicsLayer {
      scaleX = scale.value
      scaleY = scale.value
      alpha = if (enabled) 1f else .42f
    }.seaGlass(GalleryGlassRecipe.copy(seed = seed), palette, SeaGlassShape.Disc),
    colors = IconButtonDefaults.iconButtonColors(
      containerColor = Color.Transparent, contentColor = palette.ink,
      disabledContainerColor = Color.Transparent,
      disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ),
    content = content,
  )
}
