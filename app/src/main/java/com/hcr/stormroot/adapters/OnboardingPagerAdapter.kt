package com.hcr.stormroot.adapters

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.hcr.stormroot.fragments.onboard.OnboardingIntroFragment
import com.hcr.stormroot.fragments.onboard.OnboardingLayersFragment
import com.hcr.stormroot.fragments.onboard.OnboardingPermissionsFragment

class OnboardingPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> OnboardingIntroFragment()
        1 -> OnboardingLayersFragment()
        else -> OnboardingPermissionsFragment()
    }
}
