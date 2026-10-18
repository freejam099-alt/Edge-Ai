package com.google.ai.edge.gallery.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudProviderModelSettingsTest {
  @Test
  fun eayHasExactEndpointAndDoesNotGuessAStartingModel() {
    assertEquals("https://api.eay.my.id/v1", defaultCloudEndpoint(CLOUD_PROVIDER_EAY))
    assertEquals("", defaultCloudTextModel(CLOUD_PROVIDER_EAY))
    assertEquals(CLOUD_PROTOCOL_OPENAI, defaultCloudProtocol(CLOUD_PROVIDER_EAY))
  }

  @Test
  fun eayPresetsHaveExactOrderNamesAndRequestIds() {
    assertEquals(
      listOf(
        CloudModelPreset("Wiki lite", "eay/gemini-3-flash-preview"),
        CloudModelPreset("Wiki flash", "eay/gemini-3.1-flash-lite"),
        CloudModelPreset("Wiki Pro", "eay/gemini-3.1-pro-preview"),
      ),
      EAY_MODEL_PRESETS,
    )
    assertFalse(EAY_MODEL_PRESETS.any { "flash-latest" in it.id })
  }

  @Test
  fun pickerSearchMatchesAliasesAndReturnsOriginalIds() {
    val matches = cloudModelPickerChoices(CLOUD_PROVIDER_EAY, emptyList(), "wiki flash")
    assertEquals(listOf("Wiki flash"), matches.map { it.label })
    assertEquals(listOf("eay/gemini-3.1-flash-lite"), matches.map { it.id })
    assertEquals("Wiki Pro", cloudModelDisplayName("eay/gemini-3.1-pro-preview"))
  }

  @Test
  fun eayPresetModelsSupportThinkingEffort() {
    EAY_MODEL_PRESETS.forEach { preset ->
      assertTrue(supportsCloudThinkingEffort(CLOUD_PROVIDER_EAY, preset.id))
    }
    assertFalse(supportsCloudThinkingEffort(CLOUD_PROVIDER_EAY, ""))
    assertFalse(supportsCloudThinkingEffort(CLOUD_PROVIDER_EAY, "unlisted-model"))
  }
}
