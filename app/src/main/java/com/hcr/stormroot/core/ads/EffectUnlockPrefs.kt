package com.hcr.stormroot.core.ads

import android.content.Context
import androidx.core.content.edit
import com.hcr.stormroot.core.overlay.OverlayService

object EffectUnlockPrefs {
    private const val PREFS_NAME = "effect_unlock_prefs"
    private const val KEY_LAST_SEEN_WALL_CLOCK = "last_seen_wall_clock"
    private const val TAMPER_TOLERANCE_MS = 5_000L
    const val UNLOCK_DURATION_MS = 3 * 24 * 60 * 60 * 1000L

    val FREE_EFFECTS = setOf(
        OverlayService.EFFECT_SOFT_MIST,
        OverlayService.EFFECT_CALM_VINES,
        OverlayService.EFFECT_WARM_GLOW
    )

    val PREMIUM_EFFECTS = setOf(
        OverlayService.EFFECT_BUTTERFLIES,
        OverlayService.EFFECT_FALLING_LEAVES,
        OverlayService.EFFECT_SNOWFALL,
        OverlayService.EFFECT_SUN_RAYS,
        OverlayService.EFFECT_WATER_DROPLETS,
        OverlayService.EFFECT_DEW_WEB,
        OverlayService.EFFECT_STARRY_NIGHT
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun unlockedAtKey(effect: String) = "unlocked_at_$effect"

    private fun checkTamperAndRatchet(context: Context) {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val lastSeen = p.getLong(KEY_LAST_SEEN_WALL_CLOCK, 0L)
        if (lastSeen > 0L && now < lastSeen - TAMPER_TOLERANCE_MS) {
            p.edit {
                PREMIUM_EFFECTS.forEach { remove(unlockedAtKey(it)) }
            }
        }
        p.edit { putLong(KEY_LAST_SEEN_WALL_CLOCK, maxOf(lastSeen, now)) }
    }

    fun isUnlocked(context: Context, effect: String): Boolean {
        if (effect in FREE_EFFECTS) return true
        checkTamperAndRatchet(context)
        val unlockedAt = prefs(context).getLong(unlockedAtKey(effect), 0L)
        if (unlockedAt == 0L) return false
        return System.currentTimeMillis() - unlockedAt < UNLOCK_DURATION_MS
    }

    fun unlock(context: Context, effect: String) {
        val now = System.currentTimeMillis()
        val p = prefs(context)
        p.edit {
            putLong(unlockedAtKey(effect), now)
            putLong(KEY_LAST_SEEN_WALL_CLOCK, maxOf(p.getLong(KEY_LAST_SEEN_WALL_CLOCK, 0L), now))
        }
    }
}
