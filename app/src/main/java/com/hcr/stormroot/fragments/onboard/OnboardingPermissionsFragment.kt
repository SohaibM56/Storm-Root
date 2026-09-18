package com.hcr.stormroot.fragments.onboard

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.hcr.stormroot.R
import com.hcr.stormroot.activities.OnboardingActivity
import com.hcr.stormroot.core.permissions.ActivityRecognitionPermission
import com.hcr.stormroot.core.permissions.OverlayPermission
import com.hcr.stormroot.core.permissions.UsageAccessPermission
import com.hcr.stormroot.databinding.PageOnboarding3Binding

class OnboardingPermissionsFragment : Fragment() {

    private lateinit var binding: PageOnboarding3Binding

    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateOverlayButtonState()
    }

    private val activityRecognitionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        updatePostureButtonState()
    }

    private val usageAccessSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateUsageAccessButtonState()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = PageOnboarding3Binding.inflate(inflater, container, false)
        return binding.root
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.ctaButton.setOnClickListener {
            (activity as? OnboardingActivity)?.goToNextPage()
        }
        binding.overlayPermissionButton.setOnClickListener {
            overlaySettingsLauncher.launch(OverlayPermission.requestIntent(requireContext()))
        }
        binding.posturePermissionButton.setOnClickListener {
            activityRecognitionLauncher.launch(ActivityRecognitionPermission.PERMISSION)
        }
        binding.usageAccessPermissionButton.setOnClickListener {
            usageAccessSettingsLauncher.launch(UsageAccessPermission.requestIntent())
        }
        updateOverlayButtonState()
        updatePostureButtonState()
        updateUsageAccessButtonState()
    }

    override fun onResume() {
        super.onResume()
        updateOverlayButtonState()
        updatePostureButtonState()
        updateUsageAccessButtonState()
    }

    private fun updateOverlayButtonState() {
        applyGrantedState(
            button = binding.overlayPermissionButton,
            granted = OverlayPermission.isGranted(requireContext()),
            defaultTextRes = R.string.permission_1_cta,
            grantedTextRes = R.string.permission_1_cta_granted
        )
    }

    private fun updatePostureButtonState() {
        applyGrantedState(
            button = binding.posturePermissionButton,
            granted = ActivityRecognitionPermission.isGranted(requireContext()),
            defaultTextRes = R.string.permission_2_cta,
            grantedTextRes = R.string.permission_2_cta_granted
        )
    }

    private fun updateUsageAccessButtonState() {
        applyGrantedState(
            button = binding.usageAccessPermissionButton,
            granted = UsageAccessPermission.isGranted(requireContext()),
            defaultTextRes = R.string.permission_3_cta,
            grantedTextRes = R.string.permission_3_cta_granted
        )
    }

    private fun applyGrantedState(button: MaterialButton, granted: Boolean, defaultTextRes: Int, grantedTextRes: Int) {
        button.isEnabled = !granted
        button.text = getString(if (granted) grantedTextRes else defaultTextRes)
        button.icon = ContextCompat.getDrawable(
            requireContext(),
            if (granted) R.drawable.ic_check else R.drawable.ic_plus
        )
        button.backgroundTintList = requireContext().getColorStateList(
            if (granted) R.color.surface_mint_light else R.color.surface_gray_light
        )
        val textColor = requireContext().getColorStateList(if (granted) R.color.nav_active else R.color.text_primary)
        button.setTextColor(textColor)
        button.iconTint = textColor
    }

}
