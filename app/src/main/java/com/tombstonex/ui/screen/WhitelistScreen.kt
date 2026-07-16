package com.tombstonex.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun WhitelistScreen() {
    var whiteApps by remember { mutableStateOf(listOf("com.android.systemui")) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newApp by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Button(
            onClick = { showAddDialog = true },
            modifier = Modifier.padding(16.dp)
        ) {
            Text("添加白名单")
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            items(whiteApps) { app ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(app)
                        TextButton(
                            onClick = {
                                whiteApps = whiteApps.filter { it != app }
                            }
                        ) {
                            Text("移除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加白名单") },
            text = {
                OutlinedTextField(
                    value = newApp,
                    onValueChange = { newApp = it },
                    label = { Text("包名") },
                    placeholder = { Text("com.example.app") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newApp.isNotBlank()) {
                            whiteApps = whiteApps + newApp.trim()
                            newApp = ""
                            showAddDialog = false
                        }
                    }
                ) {
                    Text("添加")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}