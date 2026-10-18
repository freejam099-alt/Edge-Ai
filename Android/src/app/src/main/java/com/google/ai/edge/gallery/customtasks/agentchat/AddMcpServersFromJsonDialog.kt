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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.net.URI

@Composable
internal fun AddMcpServerChoiceDialog(
  onDismiss: () -> Unit,
  onAddByUrl: () -> Unit,
  onImportJson: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Add MCP server") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Choose how to add a server.")
        OutlinedButton(onClick = onAddByUrl, modifier = Modifier.fillMaxWidth()) {
          Text("Add by URL")
        }
        Button(onClick = onImportJson, modifier = Modifier.fillMaxWidth()) {
          Text("Import JSON")
        }
      }
    },
    confirmButton = {},
    dismissButton = {},
  )
}

@Composable
internal fun AddMcpServersFromJsonDialog(
  mcpManagerViewModel: McpManagerViewModel,
  onDismissRequest: () -> Unit,
) {
  val uiState by mcpManagerViewModel.uiState.collectAsState()
  val loading = uiState.loadingMcpServer
  var json by remember { mutableStateOf("") }
  var importing by remember { mutableStateOf(false) }
  var approvedExternalHosts by remember { mutableStateOf(false) }
  var showExternalHostWarning by remember { mutableStateOf(false) }
  var localError by remember { mutableStateOf<String?>(null) }
  var imported by remember { mutableStateOf(false) }
  var importedCount by remember { mutableStateOf(0) }

  val dismiss: () -> Unit = {
    if (!loading) {
      mcpManagerViewModel.clearError()
      onDismissRequest()
    }
  }

  LaunchedEffect(loading, uiState.importSuccess, uiState.error) {
    if (importing && !loading) {
      if (uiState.importSuccess) {
        imported = true
        importing = false
      } else if (uiState.error != null) {
        importing = false
      }
    }
  }

  Dialog(onDismissRequest = { dismiss() }) {
    Card(shape = MaterialTheme.shapes.large) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text("Import MCP servers from JSON", style = MaterialTheme.typography.titleMedium)
        if (!imported) {
          Text(
            "Paste a remote MCP configuration. Only HTTP(S) URLs and at most one request header per server are supported. stdio commands are never executed.",
            style = MaterialTheme.typography.bodySmall,
          )
          OutlinedTextField(
            value = json,
            onValueChange = {
              json = it
              localError = null
              mcpManagerViewModel.clearError()
            },
            modifier = Modifier.fillMaxWidth().height(230.dp),
            label = { Text("JSON configuration") },
            placeholder = {
              Text("{\"mcpServers\":{\"server\":{\"url\":\"https://example.com/mcp\"}}}")
            },
            minLines = 6,
            maxLines = 10,
            enabled = !loading,
          )
          Text(
            "Authentication values in headers are stored as server credentials. Keep this configuration private.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          (localError ?: uiState.error)?.let { message ->
            Text(
              text = message,
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodySmall,
              maxLines = 3,
              overflow = TextOverflow.Ellipsis,
            )
          }
        } else {
          Text("Imported $importedCount MCP server(s).", color = MaterialTheme.colorScheme.primary)
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          if (loading && importing) {
            CircularProgressIndicator(modifier = Modifier.width(22.dp), strokeWidth = 2.dp)
          } else if (imported) {
            Button(onClick = dismiss) { Text("Done") }
          } else {
            OutlinedButton(onClick = dismiss) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(
              enabled = json.isNotBlank(),
              onClick = {
                val parsed = try {
                  McpServerJsonParser.parse(json)
                } catch (e: McpServerJsonException) {
                  localError = e.message
                  null
                }
                if (parsed != null) {
                  importedCount = parsed.size
                  val hasExternalHost = parsed.any { !isMcpHostApprovedForImport(it.url) }
                  if (hasExternalHost && !approvedExternalHosts) {
                    showExternalHostWarning = true
                  } else {
                    localError = null
                    mcpManagerViewModel.clearError()
                    importing = true
                    mcpManagerViewModel.importMcpServersFromJson(json)
                  }
                }
              },
            ) { Text("Import") }
          }
        }
      }
    }
  }

  if (showExternalHostWarning) {
    AlertDialog(
      onDismissRequest = { showExternalHostWarning = false },
      title = { Text("Connect to external MCP server?") },
      text = {
        Text("This configuration includes a host outside the Google APIs domain. Only continue if you trust the server and its authentication settings.")
      },
      confirmButton = {
        Button(
          onClick = {
            showExternalHostWarning = false
            approvedExternalHosts = true
            localError = null
            mcpManagerViewModel.clearError()
            importing = true
            mcpManagerViewModel.importMcpServersFromJson(json)
          }
        ) { Text("Continue") }
      },
      dismissButton = {
        OutlinedButton(onClick = { showExternalHostWarning = false }) { Text("Cancel") }
      },
    )
  }
}

private fun isMcpHostApprovedForImport(url: String): Boolean {
  return try {
    val host = URI(url).host?.lowercase() ?: return false
    host == "googleapis.com" || host.endsWith(".googleapis.com")
  } catch (_: Exception) {
    false
  }
}
