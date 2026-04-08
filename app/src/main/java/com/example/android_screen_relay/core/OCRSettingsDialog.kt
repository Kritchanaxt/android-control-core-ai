package com.example.android_screen_relay.core

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.Size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OCRSettingsDialog(
    currentAspectRatio: UiAspectRatio,
    currentResolution: Size?,
    currentAlignment: VerticalAlignment,
    onSettingsChanged: (UiAspectRatio, Size?, VerticalAlignment) -> Unit,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    var selectedAspectRatio by remember { mutableStateOf(currentAspectRatio) }
    var selectedResolution by remember { mutableStateOf(currentResolution) }
    var selectedAlignment by remember { mutableStateOf(currentAlignment) }

    // Logic to get resolutions (simplified)
    val availableResolutions = remember(selectedAspectRatio) {
        getResolutionsForAspectRatio(context, selectedAspectRatio)
    }

    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Camera Settings", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(16.dp))

            // Aspect Ratio
            Text("Aspect Ratio", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                UiAspectRatio.entries.filter { it.isSpecialRatio || it == UiAspectRatio.FULL }.forEach { ratio ->
                    FilterChip(
                        selected = selectedAspectRatio == ratio,
                        onClick = { 
                            selectedAspectRatio = ratio 
                            selectedResolution = null // Reset resolution on ratio change
                        },
                        label = { Text(ratio.shortDescription) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Resolution
            Text("Resolution", style = MaterialTheme.typography.titleMedium)
            
            // Simplified Dropdown for Resolution
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    Text(selectedResolution?.toString() ?: "Best Available")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Best Available") },
                        onClick = { 
                            selectedResolution = null
                            expanded = false 
                        }
                    )
                    availableResolutions.forEach { res ->
                        DropdownMenuItem(
                            text = { Text(res.displayText) },
                            onClick = { 
                                selectedResolution = res.size
                                expanded = false 
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Alignment
            Text("Vertical Alignment", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                VerticalAlignment.entries.forEach { align ->
                    FilterChip(
                        selected = selectedAlignment == align,
                        onClick = { selectedAlignment = align },
                        label = { Text(align.name) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    onSettingsChanged(selectedAspectRatio, selectedResolution, selectedAlignment)
                    onDismissRequest()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Apply")
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun getResolutionsForAspectRatio(context: Context, aspectRatio: UiAspectRatio): List<ResolutionItem> {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val cameraId = try {
        cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    } catch (e: Exception) { null } ?: return emptyList()

    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
    val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
    
    // Sort by pixel count
    val sizes = map.getOutputSizes(android.graphics.ImageFormat.JPEG)
        ?.sortedByDescending { it.width * it.height } ?: emptyList()

    // Filter by ratio
    val tolerance = 0.02f
    val targetRatio = aspectRatio.value
    
    val filteredSizes = if (targetRatio != null) {
        sizes.filter { size ->
            val ratio = size.width.toFloat() / size.height.toFloat()
            kotlin.math.abs(ratio - targetRatio) < tolerance
        }
    } else {
        sizes // Full limits just show all? Or usually 4:3 is sensor default
    }

    return filteredSizes.map { ResolutionItem(it, "${it.width}x${it.height}") }
}
