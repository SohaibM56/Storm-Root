package com.hcr.stormroot.activities

import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.hcr.stormroot.R
import com.hcr.stormroot.core.ads.RewardedAdManager
import com.hcr.stormroot.core.anchors.AnchorStatus
import com.hcr.stormroot.core.bedtime.BedtimeDriftPrefs
import com.hcr.stormroot.core.doomscroll.DoomscrollPrefs
import com.hcr.stormroot.core.overlay.OverlayService
import com.hcr.stormroot.core.sitting.SittingRootsPrefs
import com.hcr.stormroot.core.stats.StatsStore
import com.hcr.stormroot.databinding.ActivityMainBinding
import com.hcr.stormroot.databinding.ItemBottomTabBinding
import com.hcr.stormroot.fragments.main.InsightsFragment
import com.hcr.stormroot.fragments.main.OverlaysFragment
import com.hcr.stormroot.fragments.main.OverviewFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tabs: List<Pair<ItemBottomTabBinding, Tab>>

    private enum class Tab(val iconRes: Int, val labelRes: Int, val createFragment: () -> Fragment) {
        OVERVIEW(R.drawable.overview_ic, R.string.tab_overview, { OverviewFragment() }),
        OVERLAYS(R.drawable.overlay_ic, R.string.tab_overlays, { OverlaysFragment() }),
        INSIGHTS(R.drawable.insights_ic, R.string.tab_insights, { InsightsFragment() })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        tabs = listOf(
            binding.tabOverview to Tab.OVERVIEW,
            binding.tabOverlays to Tab.OVERLAYS,
            binding.tabInsights to Tab.INSIGHTS
        )

        tabs.forEach { (tabBinding, tab) ->
            tabBinding.icon.setImageResource(tab.iconRes)
            tabBinding.label.setText(tab.labelRes)
            tabBinding.root.setOnClickListener { selectTab(tab) }
        }

        if (savedInstanceState == null) {
            selectTab(Tab.OVERVIEW)
        }

        if (BedtimeDriftPrefs.isEnabled(this)) {
            OverlayService.startMonitor(this)
        }
        if (DoomscrollPrefs.isEnabled(this)) {
            OverlayService.startDoomscrollMonitor(this)
        }
        if (SittingRootsPrefs.isEnabled(this)) {
            OverlayService.startRootsMonitor(this)
        }

        // General-audience content rating for a calm/wellness app — set before initialize() so
        // it applies to the very first ad request rather than only ones after this point.
        MobileAds.setRequestConfiguration(
            RequestConfiguration.Builder()
                .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_G)
                .build()
        )
        MobileAds.initialize(this) { RewardedAdManager.preload(this) }

        updateHeaderBadge()
    }

    override fun onResume() {
        super.onResume()
        updateHeaderBadge()
    }

    private fun updateHeaderBadge() {
        val activeCount = AnchorStatus.activeCount(this)
        val calmPercent = StatsStore.calmPercentForToday(this)
        binding.headerBadge.text = when {
            activeCount == 0 -> getString(R.string.header_badge_getting_started)
            calmPercent >= 70 -> getString(R.string.header_badge_calm_active)
            else -> getString(R.string.header_badge_staying_mindful)
        }
    }

    private fun selectTab(selected: Tab) {
        tabs.forEach { (tabBinding, tab) ->
            val isSelected = tab == selected
            tabBinding.iconFrame.setBackgroundResource(
                if (isSelected) R.drawable.bg_pill_mint_tab else android.R.color.transparent
            )
            val tint = if (isSelected) R.color.nav_active else R.color.nav_inactive
            tabBinding.icon.imageTintList = getColorStateList(tint)
            tabBinding.label.setTextColor(getColorStateList(tint))
        }
        binding.headerSubtitle.setText(selected.labelRes)
        supportFragmentManager.beginTransaction()
            .replace(binding.fragmentContainer.id, selected.createFragment())
            .commit()
        updateHeaderBadge()
    }
}
