package com.hcr.stormroot.activities

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewpager2.widget.ViewPager2
import com.hcr.stormroot.R
import com.hcr.stormroot.adapters.OnboardingPagerAdapter
import com.hcr.stormroot.core.prefs.OnboardingPrefs
import com.hcr.stormroot.databinding.ActivityOnboardingBinding

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var dots: List<View>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (OnboardingPrefs.isCompleted(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        enableEdgeToEdge()
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        dots = listOf(binding.dot1, binding.dot2, binding.dot3)
        binding.onboardingPager.adapter = OnboardingPagerAdapter(this)
        binding.skip.setOnClickListener {
            OnboardingPrefs.setCompleted(this)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        binding.back.setOnClickListener { goToPreviousPage() }
        binding.onboardingPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePage(position)
            }
        })
        updatePage(0)
    }

    private fun updatePage(position: Int) {
        binding.back.visibility = if (position == 0) View.INVISIBLE else View.VISIBLE
        dots.forEachIndexed { index, dot ->
            val layoutParams = dot.layoutParams
            if (index == position) {
                dot.setBackgroundResource(R.drawable.dot_active)
                layoutParams.width = resources.getDimensionPixelSize(com.intuit.sdp.R.dimen._10sdp)
            } else {
                dot.setBackgroundResource(R.drawable.dot_inactive)
                layoutParams.width = resources.getDimensionPixelSize(com.intuit.sdp.R.dimen._5sdp)
            }
            dot.layoutParams = layoutParams
        }
    }

    fun goToNextPage() {
        val next = binding.onboardingPager.currentItem + 1
        if (next < (binding.onboardingPager.adapter?.itemCount ?: 0)) {
            binding.onboardingPager.currentItem = next
        } else {
            OnboardingPrefs.setCompleted(this)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun goToPreviousPage() {
        val previous = binding.onboardingPager.currentItem - 1
        if (previous >= 0) {
            binding.onboardingPager.currentItem = previous
        }
    }
}
