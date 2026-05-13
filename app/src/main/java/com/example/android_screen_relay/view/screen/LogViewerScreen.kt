package com.example.android_screen_relay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(onBack: () -> Unit) {
    val logs = LogRepository.logs
    val listState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    
    // Auto-scroll to bottom when logs change
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("System View", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                        Text("${logs.size} Events Captured", fontSize = 12.sp, color = Color.Gray)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { LogRepository.clearLogs() }) {
                        Icon(Icons.Filled.Delete, "Clear", tint = Color.Gray)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.White
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    val path = LogRepository.exportLogsToDownloads(context)
                    if (path != null) {
                        android.widget.Toast.makeText(context, "Saved: $path", android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        android.widget.Toast.makeText(context, "Export Failed", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                containerColor = Color(0xFF2196F3),
                contentColor = Color.White,
                icon = { Icon(Icons.Filled.Download, null) },
                text = { Text("Export JSON") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFFF5F5F7))
        ) {
            if (logs.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp) // Space for FAB
                ) {
                    items(logs) { log ->
                        LogCard(log)
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Info, 
                null, 
                tint = Color.LightGray, 
                modifier = Modifier.size(72.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("No System Events", color = Color.Gray, fontWeight = FontWeight.Medium)
            Text(
                "Waiting for activity...", 
                color = Color.LightGray,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
fun LogCard(log: LogRepository.LogEntry) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header: Component + Time
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Left side (Component + Event) - Flexible width
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    StatusIndicator(log.type)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = buildAnnotatedString {
                            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFF333333))) {
                                append(log.component)
                            }
                            withStyle(style = SpanStyle(color = Color(0xFF666666))) {
                                append(" • ${log.event}")
                            }
                        },
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Spacing
                Spacer(modifier = Modifier.width(8.dp))

                // Right side (Timestamp) - Fixed non-wrapping
                Text(
                    text = LogRepository.getFormattedTimestamp(log.timestamp),
                    fontSize = 12.sp,
                    color = Color.Gray,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    softWrap = false
                )
            }
            
            // Data Payload
            if (log.data.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = Color(0xFFFAFAFA),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        log.data.forEach { (key, value) ->
                            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                Text(
                                    text = "$key: ",
                                    color = Color.Gray,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = value.toString(),
                                    color = Color(0xFF2C2C2C),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
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
fun StatusIndicator(type: LogRepository.LogType) {
    val color = when (type) {
        LogRepository.LogType.INFO -> Color(0xFF2196F3) // Blue
        LogRepository.LogType.ERROR -> Color(0xFFE91E63) // Pink/Red
        LogRepository.LogType.OUTGOING -> Color(0xFF4CAF50) // Green
        LogRepository.LogType.INCOMING -> Color(0xFFFF9800) // Orange
    }
    
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color, shape = androidx.compose.foundation.shape.CircleShape)
    )
}
