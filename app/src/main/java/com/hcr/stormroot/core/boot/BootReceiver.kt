package com.hcr.stormroot.core.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hcr.stormroot.core.bedtime.BedtimeDriftPrefs
import com.hcr.stormroot.core.doomscroll.DoomscrollPrefs
import com.hcr.stormroot.core.overlay.OverlayService
import com.hcr.stormroot.core.sitting.SittingRootsPrefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        if (BedtimeDriftPrefs.isEnabled(context)) {
            OverlayService.startMonitor(context)
        }
        if (DoomscrollPrefs.isEnabled(context)) {
            OverlayService.startDoomscrollMonitor(context)
        }
        if (SittingRootsPrefs.isEnabled(context)) {
            OverlayService.startRootsMonitor(context)
        }
    }
}
