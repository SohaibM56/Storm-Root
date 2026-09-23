package com.hcr.stormroot.core.ads

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.hcr.stormroot.R

object RewardedAdManager {
    private const val INITIAL_BACKOFF_MS = 5_000L
    private const val MAX_BACKOFF_MS = 5 * 60_000L

    private var rewardedAd: RewardedAd? = null
    private var isLoading = false
    private var nextRetryAtMillis = 0L
    private var backoffMs = INITIAL_BACKOFF_MS

    fun isReady(): Boolean = rewardedAd != null

    fun preload(context: Context) {
        if (rewardedAd != null || isLoading) return
        if (System.currentTimeMillis() < nextRetryAtMillis) return
        isLoading = true
        val appContext = context.applicationContext
        val adUnitId = appContext.getString(R.string.admob_rewarded_ad_unit_id)
        val request = AdRequest.Builder().build()
        RewardedAd.load(appContext, adUnitId, request, object : RewardedAdLoadCallback() {
            override fun onAdLoaded(ad: RewardedAd) {
                isLoading = false
                rewardedAd = ad
                backoffMs = INITIAL_BACKOFF_MS
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                isLoading = false
                rewardedAd = null
                nextRetryAtMillis = System.currentTimeMillis() + backoffMs
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        })
    }

    fun showAd(activity: Activity, onRewardEarned: () -> Unit, onFailed: () -> Unit) {
        val ad = rewardedAd
        if (ad == null) {
            preload(activity)
            onFailed()
            return
        }
        rewardedAd = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                preload(activity)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                preload(activity)
            }
        }
        ad.show(activity) { onRewardEarned() }
    }
}
