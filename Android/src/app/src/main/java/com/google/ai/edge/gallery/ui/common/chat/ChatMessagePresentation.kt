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

package com.google.ai.edge.gallery.ui.common.chat

/** Generic placeholder persisted instead of any provider/model reasoning text. */
internal const val GENERIC_THINKING_STATUS = "Thinking in progress"

/** Whether a message has visible content worth adding to the chat transcript. */
internal fun shouldRenderChatMessage(message: ChatMessage): Boolean {
  // Empty model snapshots can occur during streaming or be restored from old history. Do not
  // create a sender row, bubble, or completion actions for a message with no user-visible text.
  if (message is ChatMessageThinking) {
    return !message.content.trim().equals("null", ignoreCase = true) &&
      (message.inProgress || message.content.isNotBlank())
  }
  return !(message is ChatMessageText && message.side == ChatSide.AGENT && (message.content.isBlank() || message.content.trim().equals("null", ignoreCase = true)))
}

/** Whether a streamed token contains answer text instead of only whitespace/control markers. */
internal fun shouldAppendAssistantToken(token: String, hasExistingAnswer: Boolean = false): Boolean {
  return token.isNotEmpty() &&
    !token.trim().equals("null", ignoreCase = true) &&
    !token.trimStart().startsWith("<ctrl") &&
    (token.isNotBlank() || hasExistingAnswer)
}
