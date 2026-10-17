package com.google.ai.edge.gallery.ui.common.chat

import com.google.ai.edge.gallery.proto.ChatMessageProto
import com.google.ai.edge.gallery.proto.ChatSideProto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessagePresentationTest {
  @Test
  fun blankAssistantTextIsNotRenderedButOtherMessagesRemainVisible() {
    assertFalse(shouldRenderChatMessage(ChatMessageText(" \n", ChatSide.AGENT)))
    assertFalse(shouldRenderChatMessage(ChatMessageText("null", ChatSide.AGENT)))
    assertFalse(shouldRenderChatMessage(ChatMessageText(" NULL ", ChatSide.AGENT)))
    assertTrue(shouldRenderChatMessage(ChatMessageText("answer", ChatSide.AGENT)))
    assertTrue(shouldRenderChatMessage(ChatMessageText("", ChatSide.USER)))
    assertTrue(shouldRenderChatMessage(ChatMessageThinking("private legacy text", false)))
    assertFalse(shouldRenderChatMessage(ChatMessageThinking("null", false)))
    assertFalse(shouldRenderChatMessage(ChatMessageThinking("", false)))
    assertTrue(shouldRenderChatMessage(ChatMessageThinking("", true)))
  }

  @Test
  fun doneOnlyAndControlOnlyChunksDoNotCreateAssistantBubbles() {
    assertFalse(shouldAppendAssistantToken(""))
    assertFalse(shouldAppendAssistantToken("null"))
    assertFalse(shouldAppendAssistantToken(" NULL ", hasExistingAnswer = true))
    assertFalse(shouldAppendAssistantToken("   "))
    assertFalse(shouldAppendAssistantToken("<ctrl100>"))
    assertFalse(shouldAppendAssistantToken("  <ctrl100>"))
    assertTrue(shouldAppendAssistantToken("final answer"))
    assertFalse(shouldAppendAssistantToken(" "))
    assertTrue(shouldAppendAssistantToken(" ", hasExistingAnswer = true))
  }

  @Test
  fun restoringLegacyThinkingDiscardsPersistedReasoningText() = runBlocking {
    val proto =
      ChatMessageProto.newBuilder()
        .setMessageType("THINKING")
        .setContent("private reasoning that must never be shown")
        .setSide(ChatSideProto.CHAT_SIDE_MODEL)
        .setInProgress(false)
        .build()

    val restored =
      ChatMessageMapper.deserializeProtoMessage(proto, Dispatchers.Unconfined)
        as ChatMessageThinking

    assertEquals(GENERIC_THINKING_STATUS, restored.content)
    assertFalse(restored.inProgress)
  }

  @Test
  fun serializingThinkingAlwaysStoresGenericStatus() = runBlocking {
    val proto =
      ChatMessageMapper.serializeMessage(
        ChatMessageThinking("private reasoning", inProgress = true),
        sessionId = "test",
        dispatcher = Dispatchers.Unconfined,
      )!!

    assertEquals(GENERIC_THINKING_STATUS, proto.content)
  }
  @Test
  fun restoringEmptyOrNullThinkingDropsTheCard() = runBlocking {
    listOf("", " ", "null", " NULL ").forEach { content ->
      val proto = ChatMessageProto.newBuilder().setMessageType("THINKING")
        .setContent(content).setSide(ChatSideProto.CHAT_SIDE_MODEL).setInProgress(false).build()
      assertEquals(null, ChatMessageMapper.deserializeProtoMessage(proto, Dispatchers.Unconfined))
    }
  }
}
