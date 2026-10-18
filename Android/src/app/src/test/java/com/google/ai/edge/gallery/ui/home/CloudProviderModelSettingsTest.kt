package com.google.ai.edge.gallery.ui.home

import com.google.ai.edge.gallery.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class CloudProviderModelSettingsTest {
  @Test
  fun eayHasExactEndpointAndDefaultsToWikiFlash() {
    assertEquals("https://api.eay.my.id/v1", defaultCloudEndpoint(CLOUD_PROVIDER_EAY))
    assertEquals("eay/gemini-3.1-flash-lite", defaultCloudTextModel(CLOUD_PROVIDER_EAY))
    assertEquals(CLOUD_PROTOCOL_OPENAI, defaultCloudProtocol(CLOUD_PROVIDER_EAY))
  }

  @Test
  fun eayPresetsHaveExactOrderNamesAndRequestIds() {
    assertEquals(
      listOf(
        CloudModelPreset("Wiki lite", "eay/gemini-3-flash-preview"),
        CloudModelPreset("Wiki Flash", "eay/gemini-3.1-flash-lite"),
        CloudModelPreset("Wiki Pro", "eay/gemini-3.1-pro-preview"),
      ),
      EAY_MODEL_PRESETS,
    )
    assertFalse(EAY_MODEL_PRESETS.any { "flash-latest" in it.id })
  }

  @Test
  fun pickerSearchMatchesAliasesAndReturnsOriginalIds() {
    val matches = cloudModelPickerChoices(CLOUD_PROVIDER_EAY, emptyList(), "wiki flash")
    assertEquals(listOf("Wiki Flash"), matches.map { it.label })
    assertEquals(listOf("eay/gemini-3.1-flash-lite"), matches.map { it.id })
    assertEquals("Wiki Pro", cloudModelDisplayName("eay/gemini-3.1-pro-preview"))
  }

  @Test
  fun eayMissingOrUnlistedModelsResolveToFlashAndValidChoicesPersist() {
    assertEquals(EAY_DEFAULT_MODEL, normalizedCloudTextModel(CLOUD_PROVIDER_EAY, null))
    assertEquals(EAY_DEFAULT_MODEL, normalizedCloudTextModel(CLOUD_PROVIDER_EAY, ""))
    assertEquals(EAY_DEFAULT_MODEL, normalizedCloudTextModel(CLOUD_PROVIDER_EAY, "obsolete-model"))
    EAY_MODEL_PRESETS.forEach { preset ->
      assertEquals(preset.id, normalizedCloudTextModel(CLOUD_PROVIDER_EAY, preset.id))
    }
    assertEquals("my-custom-model", normalizedCloudTextModel(CLOUD_PROVIDER_CUSTOM, "my-custom-model"))
  }

  @Test
  fun bundledCredentialIsRestrictedToTheExactEayApi() {
    assertTrue(isBundledCloudEndpoint(CLOUD_PROVIDER_EAY, "https://api.eay.my.id/v1"))
    assertTrue(isBundledCloudEndpoint(CLOUD_PROVIDER_EAY, "https://api.eay.my.id/v1/"))
    assertFalse(isBundledCloudEndpoint(CLOUD_PROVIDER_CUSTOM, "https://api.eay.my.id/v1"))
    assertFalse(isBundledCloudEndpoint(CLOUD_PROVIDER_EAY, "https://api.eay.my.id.attacker.test/v1"))
    assertFalse(isBundledCloudEndpoint(CLOUD_PROVIDER_EAY, "http://api.eay.my.id/v1"))
    assertFalse(isBundledCloudEndpoint(CLOUD_PROVIDER_EAY, "https://api.eay.my.id/v1/other"))
    assertFalse(isBundledCloudEndpoint(CLOUD_PROVIDER_EAY, "https://api.eay.my.id:444/v1"))
    assertEquals("", bundledCloudApiKey(CLOUD_PROVIDER_OPENROUTER, "https://openrouter.ai/api/v1"))
  }

  @Test
  fun bundledWikiPresetsAreReadyWithoutUserCredentials() {
    assumeTrue(BuildConfig.EAY_API_KEY.isNotBlank())
    val endpoint = defaultCloudEndpoint(CLOUD_PROVIDER_EAY)
    val bundledKey = bundledCloudApiKey(CLOUD_PROVIDER_EAY, endpoint)
    assertTrue("Release must include its distribution credential", bundledKey.isNotBlank())
    EAY_MODEL_PRESETS.forEach { preset ->
      assertTrue(CloudProviderConfig(CLOUD_PROVIDER_EAY, endpoint, preset.id, bundledKey).isConfigured)
    }
  }

  @Test
  fun pickingWikiModelPreservesImageAndThinkingSettings() {
    val settings = eayModelSelectionSettings(EAY_MODEL_PRESETS[2].id, "saved-image", CLOUD_THINKING_HIGH)
    assertEquals(EAY_MODEL_PRESETS[2].id, settings[CLOUD_TEXT_MODEL_KEY])
    assertEquals("saved-image", settings[CLOUD_IMAGE_MODEL_KEY])
    assertEquals("saved-image", settings[cloudProfileKey(CLOUD_PROVIDER_EAY, "image_model")])
    assertEquals(CLOUD_THINKING_HIGH, settings[CLOUD_THINKING_EFFORT_KEY])
    assertEquals(CLOUD_THINKING_HIGH, settings[cloudProfileKey(CLOUD_PROVIDER_EAY, "thinking_effort")])
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
