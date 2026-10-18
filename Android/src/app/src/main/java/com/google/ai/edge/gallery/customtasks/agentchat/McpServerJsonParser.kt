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

package com.google.ai.edge.gallery.customtasks.agentchat

import com.google.ai.edge.gallery.proto.McpAuth
import java.net.URI
import org.json.JSONException
import org.json.JSONObject

/** One validated remote server declaration parsed from user-provided MCP JSON. */
internal data class ParsedMcpServer(
  val name: String,
  val url: String,
  val headerName: String? = null,
  val headerValue: String? = null,
) {
  fun toAuth(): McpAuth =
    if (headerName != null && headerValue != null) {
      McpAuth.newBuilder()
        .setRequestHeader(
          McpAuth.RequestHeader.newBuilder()
            .setHeaderName(headerName)
            .setHeaderValue(headerValue)
            .build()
        )
        .build()
    } else {
      McpAuth.newBuilder().setNone(true).build()
    }
}

/** Safe, user-displayable parse failure. Messages intentionally never include input values. */
internal class McpServerJsonException(message: String) : IllegalArgumentException(message)

/** Parses the supported URL-based MCP JSON subset, rejecting unsupported data rather than dropping it. */
internal object McpServerJsonParser {
  private const val MAX_JSON_LENGTH = 512 * 1024
  private const val MAX_SERVERS = 20
  private val allowedHeaderName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
  private val supportedServerFields = setOf("url", "headers", "name")

  fun parse(json: String): List<ParsedMcpServer> {
    if (json.length > MAX_JSON_LENGTH) {
      throw McpServerJsonException("The MCP configuration is too large to import.")
    }
    val root =
      try {
        JSONObject(json)
      } catch (_: JSONException) {
        throw McpServerJsonException("Invalid JSON. Check the configuration and try again.")
      } catch (_: RuntimeException) {
        throw McpServerJsonException("Invalid JSON. Check the configuration and try again.")
      }

    val servers =
      if (root.has("mcpServers")) {
        if (root.length() != 1) unsupported()
        val serverMap = root.optJSONObject("mcpServers") ?: invalid()
        if (serverMap.length() == 0) invalid()
        if (serverMap.length() > MAX_SERVERS) {
          throw McpServerJsonException("Import up to $MAX_SERVERS MCP servers at a time.")
        }
        val result = mutableListOf<ParsedMcpServer>()
        val names = serverMap.keys()
        while (names.hasNext()) {
          val name = names.next()
          if (name.isBlank()) invalid()
          val serverObject = serverMap.optJSONObject(name) ?: invalid()
          result += parseServer(serverObject, name)
        }
        result
      } else {
        if (root.has("command") || root.has("args") || root.has("transport")) unsupported()
        parseServer(root, root.optString("name", "MCP server")) .let(::listOf)
      }

    if (servers.map { it.url }.toSet().size != servers.size) {
      throw McpServerJsonException("Each server must have a unique URL.")
    }
    return servers
  }

  private fun parseServer(obj: JSONObject, defaultName: String): ParsedMcpServer {
    val keys = obj.keys()
    while (keys.hasNext()) {
      if (keys.next() !in supportedServerFields) unsupported()
    }
    val url = obj.opt("url") as? String ?: invalid()
    validateRemoteUrl(url)
    val name = (obj.opt("name") as? String)?.takeIf { it.isNotBlank() } ?: defaultName
    if (!obj.has("headers")) {
      return ParsedMcpServer(name = name, url = url)
    }
    val headers = obj.opt("headers") as? JSONObject ?: invalid()
    if (headers.length() > 1) {
      throw McpServerJsonException("Only one request header is supported per server.")
    }
    if (headers.length() == 0) return ParsedMcpServer(name = name, url = url)
    val headerKeys = headers.keys()
    val headerName = headerKeys.next()
    val headerValue = headers.opt(headerName) as? String ?: invalid()
    if (!allowedHeaderName.matches(headerName) || headerValue.isBlank() ||
      headerValue.any { it == '\r' || it == '\n' || it.code < 0x20 || it.code == 0x7f }
    ) {
      throw McpServerJsonException("The request header is invalid.")
    }
    return ParsedMcpServer(name, url, headerName, headerValue)
  }

  private fun validateRemoteUrl(url: String) {
    val uri = try {
      URI(url)
    } catch (_: Exception) {
      invalid()
    }
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") unsupported()
    if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null ||
      uri.rawQuery != null || url.any { it.isWhitespace() }
    ) {
      throw McpServerJsonException("Enter a valid remote HTTP(S) URL without embedded credentials or query parameters.")
    }
    if (uri.port > 65535) invalid()
  }

  private fun invalid(): Nothing =
    throw McpServerJsonException("The MCP configuration is invalid. Check its fields and try again.")

  private fun unsupported(): Nothing =
    throw McpServerJsonException("This MCP configuration uses unsupported features. Only remote HTTP(S) servers and one request header per server are supported.")
}
