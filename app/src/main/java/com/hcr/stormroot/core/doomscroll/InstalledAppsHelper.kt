package com.hcr.stormroot.core.doomscroll

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable
)

object InstalledAppsHelper {

    @Volatile
    private var cache: List<InstalledApp>? = null
    private var isLoading = false
    private val pendingCallbacks = mutableListOf<(List<InstalledApp>) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun getOrLoad(context: Context, onReady: (List<InstalledApp>) -> Unit) {
        cache?.let {
            onReady(it)
            return
        }
        pendingCallbacks.add(onReady)
        if (!isLoading) {
            loadInBackground(context)
        }
    }
    fun refresh(context: Context, onReady: (List<InstalledApp>) -> Unit) {
        pendingCallbacks.add(onReady)
        if (!isLoading) {
            loadInBackground(context)
        }
    }

    private fun loadInBackground(context: Context) {
        isLoading = true
        val appContext = context.applicationContext
        Thread {
            val apps = loadLaunchableApps(appContext)
            mainHandler.post {
                cache = apps
                isLoading = false
                val callbacks = pendingCallbacks.toList()
                pendingCallbacks.clear()
                callbacks.forEach { it(apps) }
            }
        }.start()
    }

    fun loadLaunchableApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            .asSequence()
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .mapNotNull { pkg ->
                runCatching {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    InstalledApp(
                        packageName = pkg,
                        label = pm.getApplicationLabel(appInfo).toString(),
                        icon = downscaleIcon(context, pm.getApplicationIcon(appInfo))
                    )
                }.getOrNull()
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    // The picker only ever displays icons at ~28sdp (item_app_checkbox.xml); app icons can be
    // adaptive/full-res drawables far larger than that, and this list is cached in memory for
    // the process lifetime, so bake each icon down to a bounded bitmap once at load time.
    private const val ICON_TARGET_DP = 48
    private fun downscaleIcon(context: Context, icon: Drawable): Drawable {
        val targetPx = (ICON_TARGET_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(targetPx, targetPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        icon.setBounds(0, 0, targetPx, targetPx)
        icon.draw(canvas)
        return BitmapDrawable(context.resources, bitmap)
    }
}
