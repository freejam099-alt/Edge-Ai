package com.google.ai.edge.gallery.customtasks.agentchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class McpServerJsonParserTest {
  @Test
  fun parsesNamedMcpServersAndPreservesSingleAuthorizationHeader() {
    val parsed =
      McpServerJsonParser.parse(
        """{"mcpServers":{"demo":{"url":"https://example.com/mcp","headers":{"Authorization":"Bearer placeholder"}}}}"""
      )

    assertEquals(1, parsed.size)
    assertEquals("demo", parsed.single().name)
    assertEquals("https://example.com/mcp", parsed.single().url)
    assertEquals("Authorization", parsed.single().headerName)
    assertEquals("Bearer placeholder", parsed.single().headerValue)
    assertEquals("Bearer placeholder", parsed.single().toAuth().requestHeader.headerValue)
  }

  @Test
  fun parsesSingleRemoteServerAndMultipleNamedRemoteServers() {
    val single = McpServerJsonParser.parse("""{"url":"http://localhost:8123/mcp"}""")
    assertEquals("http://localhost:8123/mcp", single.single().url)
    assertEquals(null, single.single().headerName)

    val multiple =
      McpServerJsonParser.parse(
        """{"mcpServers":{"one":{"url":"https://one.example/mcp"},"two":{"url":"https://two.example/mcp","headers":{"X-Api-Key":"placeholder-key"}}}}"""
      )
    assertEquals(listOf("one", "two"), multiple.map { it.name })
    assertEquals(2, multiple.size)
  }

  @Test
  fun rejectsMalformedOrInvalidConfigurationWithoutEchoingInput() {
    val badInput = """{"mcpServers":{"bad":{"url":"file:///secret/path"}}}"""
    val error = assertThrows(McpServerJsonException::class.java) {
      McpServerJsonParser.parse(badInput)
    }
    assertFalse(error.message.orEmpty().contains("secret"))
    assertThrows(McpServerJsonException::class.java) {
      McpServerJsonParser.parse("not json")
    }
    assertThrows(McpServerJsonException::class.java) {
      McpServerJsonParser.parse("""{"url":"https://user:password@example.com/mcp"}""")
    }
    assertThrows(McpServerJsonException::class.java) {
      McpServerJsonParser.parse("""{"mcpServers":{}}""")
    }
  }

  @Test
  fun rejectsStdioAndUnknownServerFeaturesRatherThanDroppingThem() {
    assertThrows(McpServerJsonException::class.java) {
      McpServerJsonParser.parse("""{"command":"node","args":["server.js"]}""")
    }
    assertThrows(McpServerJsonException::class.java) {
      McpServerJsonParser.parse("""{"mcpServers":{"local":{"command":"node"}}}""")
    }
    assertThrows(McpServerJsonException::class.java) {
      McpServerJsonParser.parse("""{"url":"https://example.com/mcp","transport":"stdio"}""")
    }
  }

  @Test
  fun rejectsMultipleOrInvalidHeadersInsteadOfSilentlyDiscardingAuthorization() {
    val error = assertThrows(McpServerJsonException::class.java) {
      McpServerJsonParser.parse(
        """{"url":"https://example.com/mcp","headers":{"Authorization":"Bearer placeholder","X-Api-Key":"placeholder-key"}}"""
      )
    }
    assertFalse(error.message.orEmpty().contains("placeholder"))
    assertThrows(McpServerJsonException::class.java) {
      McpServerJsonParser.parse(
        """{"url":"https://example.com/mcp","headers":{"Authorization":"Bearer placeholder\nInjected: value"}}"""
      )
    }
  }

  @Test
  fun rejectsDuplicateUrlsAndEmbeddedQueryCredentials() {
    assertThrows(McpServerJsonException::class.java) {
      McpServerJsonParser.parse(
        """{"mcpServers":{"first":{"url":"https://example.com/mcp"},"second":{"url":"https://example.com/mcp"}}}"""
      )
    }
    assertThrows(McpServerJsonException::class.java) {
      McpServerJsonParser.parse("""{"url":"https://example.com/mcp?token=placeholder"}""")
    }
  }
}
