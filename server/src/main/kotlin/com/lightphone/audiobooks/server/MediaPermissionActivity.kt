package com.lightphone.audiobooks.server

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Requests READ_MEDIA_AUDIO (the library scan + media provider need it) and
 * finishes. The old companion asked on its launcher activity's first open;
 * the merged build has no companion activity, so the tool launches this via
 * the SDK's permission flow (`LightSdkServer.permissionActivity`) when the
 * library comes back empty. When the permission is already granted the
 * activity finishes immediately — no dialog.
 */
class MediaPermissionActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissions = mutableListOf(
            if (Build.VERSION.SDK_INT >= 33) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            },
        )
        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            finish()
        }
    }
}
