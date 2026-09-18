package com.hcr.stormroot.core.anchors

import android.content.Context
import com.hcr.stormroot.core.bedtime.BedtimeDriftPrefs
import com.hcr.stormroot.core.doomscroll.DoomscrollPrefs
import com.hcr.stormroot.core.permissions.ActivityRecognitionPermission
import com.hcr.stormroot.core.permissions.UsageAccessPermission
import com.hcr.stormroot.core.sitting.SittingRootsPrefs

object AnchorStatus {
    fun activeCount(context: Context): Int = listOf(
        BedtimeDriftPrefs.isEnabled(context),
        DoomscrollPrefs.isEnabled(context) && UsageAccessPermission.isGranted(context),
        SittingRootsPrefs.isEnabled(context) && ActivityRecognitionPermission.isGranted(context)
    ).count { it }
}
