package com.hcr.stormroot.core.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

object ActivityRecognitionPermission {

    @RequiresApi(Build.VERSION_CODES.Q)
    const val PERMISSION = Manifest.permission.ACTIVITY_RECOGNITION

    fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
    }
}
