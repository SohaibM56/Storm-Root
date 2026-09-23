package com.hcr.stormroot.ui.dialogs

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.hcr.stormroot.R
import com.hcr.stormroot.core.ads.EffectUnlockPrefs
import com.hcr.stormroot.core.ads.NetworkStatus
import com.hcr.stormroot.core.ads.RewardedAdManager
import com.hcr.stormroot.databinding.DialogUnlockEffectBinding

object EffectUnlockFlow {

    private const val POLL_INTERVAL_MS = 1_000L
    private const val MAX_POLL_ATTEMPTS = 15

    fun show(activity: Activity, effect: String, onUnlocked: () -> Unit, onCancelled: () -> Unit = {}) {
        val binding = DialogUnlockEffectBinding.inflate(LayoutInflater.from(activity))
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(binding.root)
        dialog.setCanceledOnTouchOutside(false)
        val handler = Handler(Looper.getMainLooper())

        binding.unlockEffectTitle.text = activity.getString(
            R.string.dialog_unlock_effect_title_format,
            activity.getString(OverlayEffectOptions.labelRes(effect))
        )

        fun resetButton() {
            binding.watchAdButton.isEnabled = true
            binding.watchAdButton.text = activity.getString(R.string.dialog_unlock_effect_watch_ad)
        }

        // A tap that fails because no ad is preloaded yet used to just toast and leave the button
        // untouched, so the user had no idea whether retrying immediately would help. This polls
        // RewardedAdManager (which is now retrying its own load with backoff) and flips the
        // button back the moment an ad actually becomes available, instead of a blind retry loop.
        fun pollForAdReady(attemptsLeft: Int) {
            handler.postDelayed({
                if (!dialog.isShowing) return@postDelayed
                when {
                    RewardedAdManager.isReady() -> resetButton()
                    attemptsLeft > 0 -> pollForAdReady(attemptsLeft - 1)
                    else -> {
                        resetButton()
                        Toast.makeText(activity, R.string.dialog_unlock_effect_ad_not_ready, Toast.LENGTH_SHORT).show()
                    }
                }
            }, POLL_INTERVAL_MS)
        }

        var unlocked = false
        dialog.setOnCancelListener { if (!unlocked) onCancelled() }
        dialog.setOnDismissListener { handler.removeCallbacksAndMessages(null) }
        binding.closeButton.setOnClickListener { dialog.cancel() }
        binding.cancelButton.setOnClickListener { dialog.cancel() }
        binding.watchAdButton.setOnClickListener {
            if (!NetworkStatus.isOnline(activity)) {
                Toast.makeText(activity, R.string.dialog_unlock_effect_no_internet, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            RewardedAdManager.showAd(
                activity,
                onRewardEarned = {
                    EffectUnlockPrefs.unlock(activity, effect)
                    unlocked = true
                    dialog.dismiss()
                    onUnlocked()
                },
                onFailed = {
                    binding.watchAdButton.isEnabled = false
                    binding.watchAdButton.text = activity.getString(R.string.dialog_unlock_effect_loading)
                    pollForAdReady(MAX_POLL_ATTEMPTS)
                }
            )
        }

        dialog.show()
    }
}
