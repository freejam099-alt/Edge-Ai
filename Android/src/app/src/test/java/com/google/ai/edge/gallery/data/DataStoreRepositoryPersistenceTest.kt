package com.google.ai.edge.gallery.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import com.google.ai.edge.gallery.BenchmarkResultsSerializer
import com.google.ai.edge.gallery.CutoutsSerializer
import com.google.ai.edge.gallery.SettingsSerializer
import com.google.ai.edge.gallery.SkillsSerializer
import com.google.ai.edge.gallery.UserDataSerializer
import com.google.ai.edge.gallery.proto.BenchmarkResults
import com.google.ai.edge.gallery.proto.CutoutCollection
import com.google.ai.edge.gallery.proto.Settings
import com.google.ai.edge.gallery.proto.Skills
import com.google.ai.edge.gallery.proto.UserData
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DataStoreRepositoryPersistenceTest {
  @Test
  fun secretsPersistAcrossRepositoryInstancesAndAreObservable() = runBlocking {
    val directory = Files.createTempDirectory("gallery-secret-persistence").toFile()
    val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val firstRepository = createRepository(directory, firstScope)
      firstRepository.saveSecrets(
        mapOf(
          "cloud_provider_id" to "eay",
          "cloud_model" to "model",
          "cloud_profile_eay_text_model" to "eay/gemini-3.1-flash-lite",
          "cloud_profile_eay_thinking_effort" to "medium",
        )
      )
      assertEquals("eay", firstRepository.readSecret("cloud_provider_id"))
      firstScope.coroutineContext[Job]!!.cancelAndJoin()

      val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
      try {
        val restoredRepository = createRepository(directory, secondScope)
        assertEquals("eay", restoredRepository.readSecret("cloud_provider_id"))
        assertEquals("model", restoredRepository.observeSecret("cloud_model").first())
        assertEquals(
          "eay/gemini-3.1-flash-lite",
          restoredRepository.readSecret("cloud_profile_eay_text_model"),
        )
        assertEquals("medium", restoredRepository.readSecret("cloud_profile_eay_thinking_effort"))

        restoredRepository.saveSecret("cloud_model", "updated-model")
        assertEquals("updated-model", restoredRepository.observeSecret("cloud_model").first())
      } finally {
        secondScope.coroutineContext[Job]!!.cancelAndJoin()
      }
    } finally {
      firstScope.coroutineContext[Job]!!.cancelAndJoin()
      directory.deleteRecursively()
    }
  }

  private fun createRepository(directory: File, scope: CoroutineScope): DefaultDataStoreRepository =
    DefaultDataStoreRepository(
      createStore(directory, "settings.pb", SettingsSerializer, scope),
      createStore(directory, "user_data.pb", UserDataSerializer, scope),
      createStore(directory, "cutouts.pb", CutoutsSerializer, scope),
      createStore(directory, "benchmarks.pb", BenchmarkResultsSerializer, scope),
      createStore(directory, "skills.pb", SkillsSerializer, scope),
    )

  private fun <T> createStore(
    directory: File,
    name: String,
    serializer: Serializer<T>,
    scope: CoroutineScope,
  ): DataStore<T> =
    DataStoreFactory.create(serializer = serializer, scope = scope, produceFile = { File(directory, name) })
}
