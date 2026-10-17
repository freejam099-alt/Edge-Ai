package com.google.ai.edge.gallery.customtasks.agentchat

import com.google.ai.edge.gallery.ui.home.fetchCloudModels
import com.google.ai.edge.gallery.ui.home.CLOUD_PROTOCOL_OPENAI
import com.google.ai.edge.gallery.ui.home.CLOUD_PROTOCOL_GEMINI
import com.google.ai.edge.gallery.ui.home.CLOUD_PROTOCOL_ANTHROPIC
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudModelNetworkTest {
  @Test fun openAiUsesBearerAndReturnsRemoteIds() = runBlocking {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    var auth = ""
    server.createContext("/v1/models") { exchange ->
      auth = exchange.requestHeaders.getFirst("Authorization")
      val bytes = """{"data":[{"id":"remote-model"}]}""".toByteArray()
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    try {
      val models = fetchCloudModels("custom", CLOUD_PROTOCOL_OPENAI, "http://127.0.0.1:${server.address.port}/v1", "test-key")
      assertEquals(listOf("remote-model"), models.map { it.id })
      assertEquals("Bearer test-key", auth)
    } finally { server.stop(0) }
  }

  @Test fun geminiFollowsPagesOnceAndPreservesFullModelId() = runBlocking {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val requests = AtomicInteger()
    var auth = ""
    server.createContext("/v1beta/models") { exchange ->
      requests.incrementAndGet()
      auth = exchange.requestHeaders.getFirst("x-goog-api-key")
      val page2 = exchange.requestURI.query.orEmpty().contains("pageToken=second")
      val body = if (page2) """{"models":[{"name":"models/gemini-second","supportedGenerationMethods":["generateContent"]}]}"""
        else """{"models":[{"name":"models/gemini-2.5-flash-latest","baseModelId":"gemini-2.5-flash","supportedGenerationMethods":["generateContent"]},{"name":"models/embed","supportedGenerationMethods":["embedContent"]}],"nextPageToken":"second"}"""
      val bytes = body.toByteArray()
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    try {
      val models = fetchCloudModels("gemini", CLOUD_PROTOCOL_GEMINI, "http://127.0.0.1:${server.address.port}/v1beta", "test-key")
      assertEquals(2, requests.get())
      assertEquals("test-key", auth)
      assertEquals(listOf("gemini-2.5-flash-latest", "gemini-second"), models.map { it.id })
    } finally { server.stop(0) }
  }

  @Test fun anthropicUsesCorrectVersionRouteAndStopsAfterLastPage() = runBlocking {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val requests = AtomicInteger()
    var correctHeaders = false
    server.createContext("/v1/models") { exchange ->
      requests.incrementAndGet()
      correctHeaders = exchange.requestHeaders.getFirst("x-api-key") == "test-key" &&
        exchange.requestHeaders.getFirst("anthropic-version") == "2023-06-01"
      val page2 = exchange.requestURI.query.orEmpty().contains("after_id=claude-first")
      val body = if (page2) """{"data":[{"id":"claude-second"}],"has_more":false}"""
        else """{"data":[{"id":"claude-first"}],"has_more":true,"last_id":"claude-first"}"""
      val bytes = body.toByteArray()
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    try {
      val models = fetchCloudModels("custom", CLOUD_PROTOCOL_ANTHROPIC, "http://127.0.0.1:${server.address.port}/v1", "test-key")
      assertEquals(2, requests.get())
      assertTrue(correctHeaders)
      assertEquals(listOf("claude-first", "claude-second"), models.map { it.id })
    } finally { server.stop(0) }
  }
}
