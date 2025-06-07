package com.example.proactiiveagentv1.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class PermissionManager(
    private val activity: ComponentActivity,
    private val onPermissionResult: (Boolean) -> Unit
) {
    private val requestPermissionLauncher: ActivityResultLauncher<Array<String>> = 
        activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val hasAudioPermission = permissions[Manifest.permission.RECORD_AUDIO] == true
            onPermissionResult(hasAudioPermission)
        }
    
    fun checkAndRequestAudioPermission() {
        when {
            hasAudioPermission() -> {
                onPermissionResult(true)
            }
            else -> {
                requestPermissionLauncher.launch(
                    arrayOf(Manifest.permission.RECORD_AUDIO)
                )
            }
        }
    }
    
    fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    companion object {
        fun hasAudioPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
} 