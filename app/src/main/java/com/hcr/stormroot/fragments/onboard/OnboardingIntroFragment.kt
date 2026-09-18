package com.hcr.stormroot.fragments.onboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.hcr.stormroot.activities.OnboardingActivity
import com.hcr.stormroot.databinding.PageOnboarding1Binding

class OnboardingIntroFragment : Fragment() {

    private lateinit var binding: PageOnboarding1Binding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = PageOnboarding1Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.ctaButton.setOnClickListener {
            (activity as? OnboardingActivity)?.goToNextPage()
        }
    }

}
