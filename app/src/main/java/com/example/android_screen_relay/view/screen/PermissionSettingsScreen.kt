package com.example.android_screen_relay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WebAsset
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var isOverlayEnabled by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var isBatteryOptimized by remember { mutableStateOf(checkBatteryOptimization(context)) }
    
    // Check Notification Permission (Android 13+)
    var isNotificationGranted by remember { 
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        ) 
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            isNotificationGranted = isGranted
        }
    )

    // Resume check to update toggles when returning from settings
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isOverlayEnabled = Settings.canDrawOverlays(context)
                isBatteryOptimized = checkBatteryOptimization(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    isNotificationGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Permission settings", 
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.White
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFFF5F7FA))
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Card 1: Floating Windows
            PermissionDetailCard(
                title = "Floating Windows",
                description = "How to: Settings > Privacy/Security > Floating Windows > Enable Relay.",
                icon = Icons.Filled.WebAsset,
                isGranted = isOverlayEnabled,
                buttonText = "to Settings",
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                }
            )
            
            // Card 1.5: Notifications (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionDetailCard(
                    title = "Allow Notifications",
                    description = "Required to show 'On Air' status in notification bar.",
                    icon = Icons.Filled.Notifications,
                    isGranted = isNotificationGranted,
                    buttonText = "to Settings",
                    onClick = {
                        if (!isNotificationGranted) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            // Open App Notification Settings
                             val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                             intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                             intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                             context.startActivity(intent)
                        }
                    }
                )
            }

            // Card 2: Allow background running
            PermissionDetailCard(
                title = "Allow background running",
                description = "Step 1. Settings > Apps > App Management > Relay > App battery management.",
                icon = Icons.Filled.PowerSettingsNew,
                isGranted = isBatteryOptimized,
                buttonText = "to Settings",
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (!isBatteryOptimized) {
                            // First time: Request to ignore optimization directly (Popup)
                            try {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                intent.data = Uri.parse("package:${context.packageName}")
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // Fallback
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                intent.data = Uri.parse("package:${context.packageName}")
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                context.startActivity(intent)
                            }
                        } else {
                            // Second time (Manage): Open App Info to allow user to disable/change
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            intent.data = Uri.parse("package:${context.packageName}")
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(intent)
                        }
                    }
                }
            )
        }
    }
}

@Composable
fun PermissionDetailCard(
    title: String,
    description: String,
    icon: ImageVector, // Using Icon for now as placeholder for the image in screenshots
    isGranted: Boolean,
    buttonText: String,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFF333333),
                    modifier = Modifier.weight(1f)
                )
                
                Button(
                    onClick = onClick,
                    colors = ButtonDefaults.buttonColors(
                        // Dark Blue requested
                        containerColor = Color(0xFF0056D2), 
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.wrapContentWidth().height(32.dp)
                ) {
                    Text(
                        buttonText, 
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                lineHeight = 20.sp
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Visual Placeholder (Gray Box simulating the image in reference)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(
                        color = Color(0xFFF0F0F0),
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Mimic the UI in the screenshot slightly better
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                     // Fake modal
                     Card(
                        modifier = Modifier.size(width = 140.dp, height = 100.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                     ) {
                         Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                             if(isGranted) {
                                 Icon(Icons.Filled.PowerSettingsNew, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(32.dp))
                                 Text("ON", color = Color(0xFF4CAF50), modifier = Modifier.padding(top=48.dp), fontWeight = FontWeight.Bold)
                             } else {
                                Icon(icon, null, tint = Color.Gray, modifier = Modifier.size(32.dp))
                             }
                         }
                     }
                     Spacer(modifier = Modifier.height(16.dp))
                     Text("Setting Preview", color = Color.Gray)
                }
            }
        }
    }
}
