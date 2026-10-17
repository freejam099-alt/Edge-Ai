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

package com.google.ai.edge.gallery.ui.common.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.MarkdownText
import com.google.ai.edge.gallery.ui.common.ThinkingOrb

@Composable
fun MessageBodyThinking(
  thinkingText: String,
  inProgress: Boolean,
  onCopyClicked: (String) -> Unit = {},
) {
  var isExpanded by remember { mutableStateOf(inProgress) }
  // Expand when a new thinking phase starts, but do not force it open on every streamed update:
  // the user can still collapse the live content if they choose.
  LaunchedEffect(inProgress) {
    if (inProgress) isExpanded = true
  }

  Surface(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
      Row(
        modifier =
          Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { isExpanded = !isExpanded }
            .semantics {
              role = Role.Button
              stateDescription = if (isExpanded) "Expanded" else "Collapsed"
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        ThinkingOrb(size = 20.dp, active = inProgress)
        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
          Text(
            text = stringResource(R.string.show_thinking),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
          )
          Text(
            text = if (inProgress) "Thinking in progress" else "Thinking complete",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { if (!inProgress) liveRegion = LiveRegionMode.Polite },
          )
        }
        Icon(
          imageVector = if (isExpanded) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
          contentDescription = if (isExpanded) "Hide thinking" else "Show thinking",
        )
      }

      AnimatedVisibility(
        visible = isExpanded,
        enter = expandVertically(),
        exit = shrinkVertically(),
      ) {
        val lineColor = MaterialTheme.colorScheme.outlineVariant
        Column(
          modifier =
            Modifier.padding(top = 8.dp, bottom = 4.dp, start = 8.dp)
              .drawBehind {
                drawLine(
                  color = lineColor,
                  start = Offset(0f, 0f),
                  end = Offset(0f, size.height),
                  strokeWidth = 2.dp.toPx(),
                )
              }
              .padding(start = 12.dp)
        ) {
          // This body is strictly the text supplied by the model/provider; no synthetic chain of
          // thought is generated here.
          LongPressCopyContainer(copyText = thinkingText, onCopyClicked = onCopyClicked) {
            MarkdownText(
              text = thinkingText,
              smallFontSize = true,
              textColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}
