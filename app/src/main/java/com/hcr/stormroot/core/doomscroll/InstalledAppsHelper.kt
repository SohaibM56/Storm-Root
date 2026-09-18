package com.hcr.stormroot.core.doomscroll

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

    /** Returns the cached list immediately if available, otherwise loads it (reusing an
     *  in-flight load if one is already running) and delivers it via [onReady] on the main
     *  thread once ready. First call happens the first time the apps dialog opens; nothing
     *  is scanned at app launch. */
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

    /** Forces a fresh PackageManager scan even if a list is already cached — for an explicit
     *  user-triggered refresh (e.g. pull-to-refresh) after installing/uninstalling an app.
     *  Piggybacks on an in-flight load instead of starting a second one if one is already running. */
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
                        icon = pm.getApplicationIcon(appInfo)
                    )
                }.getOrNull()
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
