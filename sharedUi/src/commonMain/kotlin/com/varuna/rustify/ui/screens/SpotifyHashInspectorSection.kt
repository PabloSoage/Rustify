// sharedUi/src/commonMain/kotlin/com/varuna/rustify/ui/screens/SpotifyHashInspectorSection.kt
package com.varuna.rustify.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.varuna.rustify.bridge.NativeEngine
import com.varuna.rustify.platform.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun SpotifyHashInspectorSection() {
    val coroutineScope = rememberCoroutineScope()

    // Map of operationName -> sha256 hash (empty = not cached)
    var hashes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isRefreshing by remember { mutableStateOf(false) }
    var lastRefreshed by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    fun loadHashes() {
        try {
            val raw = NativeEngine.getSpotifyHashesNative()
            val json = Json.parseToJsonElement(raw).jsonObject
            val map = mutableMapOf<String, String>()
            json.forEach { (key, elem) ->
                map[key] = elem.jsonPrimitive.content
            }
            hashes = map.toList().sortedBy { it.first }.toMap()
        } catch (_: Exception) {
            hashes = emptyMap()
        }
    }

    LaunchedEffect(Unit) { loadHashes() }

    Spacer(modifier = Modifier.height(24.dp))
    Text(
        "Spotify GQL Hashes",
        color = Color(0xFF1DB954),
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    val cachedCount = hashes.count { it.value.isNotBlank() }
                    Text(
                        "$cachedCount / ${hashes.size} hashes cached",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (lastRefreshed.isNotEmpty()) {
                        Text(
                            "Last refresh: $lastRefreshed",
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Refresh button
                    IconButton(
                        onClick = {
                            if (!isRefreshing) {
                                isRefreshing = true
                                coroutineScope.launch(Dispatchers.IO) {
                                    NativeEngine.warmupSpotifyHashesNative()
                                    // Wait for warmup to propagate (it's async in Rust)
                                    delay(3500)
                                    loadHashes()
                                    val totalSec = currentTimeMillis() / 1000
                                    val s = (totalSec % 60).toString().padStart(2, '0')
                                    val m = ((totalSec / 60) % 60).toString().padStart(2, '0')
                                    val h = ((totalSec / 3600) % 24).toString().padStart(2, '0')
                                    withContext(Dispatchers.Main) {
                                        lastRefreshed = "$h:$m:$s"
                                        isRefreshing = false
                                    }
                                }
                            }
                        }
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color(0xFF1DB954),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh hashes",
                                tint = Color(0xFF1DB954)
                            )
                        }
                    }
                    // Expand/collapse toggle
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(
                            if (expanded) "Hide" else "Show",
                            color = Color(0xFF1DB954),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    if (hashes.isEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "No hashes cached. Tap refresh to scrape them from Spotify.",
                            color = Color.Gray,
                            fontSize = 13.sp
                        )
                    } else {
                        Spacer(Modifier.height(12.dp))
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp)
                        ) {
                            items(hashes.entries.toList(), key = { it.key }) { entry ->
                                HashEntryRow(
                                    operationName = entry.key,
                                    hash = entry.value
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HashEntryRow(operationName: String, hash: String) {
    var showFullHash by remember { mutableStateOf(false) }
    val isCached = hash.isNotBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (isCached) showFullHash = !showFullHash }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            operationName,
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        if (isCached) {
            val displayHash = if (showFullHash) hash else hash.take(8) + "…"
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF2A2A2A))
                    .border(1.dp, Color(0xFF1DB954).copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    displayHash,
                    color = Color(0xFF1DB954),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF2A2A2A))
                    .border(1.dp, Color(0xFFEF5350).copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    "missing",
                    color = Color(0xFFEF5350),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
            }
        }
    }
}
