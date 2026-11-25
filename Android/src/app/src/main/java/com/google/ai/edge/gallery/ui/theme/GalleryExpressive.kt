package com.google.ai.edge.gallery.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

/**
 * Tonal container drawn from semantic Material 3 color roles. Pair content with the matching `on*`
 * role (secondaryContainer -> onSecondaryContainer, surfaceContainerHigh -> onSurface).
 */
@Composable
fun Modifier.galleryContainer(
  shape: Shape = CircleShape,
  color: Color = MaterialTheme.colorScheme.secondaryContainer,
): Modifier = this.clip(shape).background(color)

/**
 * Material 3 Expressive tonal icon button. Uses the expressive shape set so the container morphs
 * on press, follows the theme's expressive motion scheme, and keeps the 48dp minimum touch target.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GalleryIconButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  content: @Composable () -> Unit,
) {
  FilledTonalIconButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shapes = IconButtonDefaults.shapes(),
    content = content,
  )
}
