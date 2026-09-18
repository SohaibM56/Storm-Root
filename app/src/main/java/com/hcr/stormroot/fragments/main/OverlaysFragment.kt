package com.hcr.stormroot.fragments.main

import android.Manifest
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.android.material.textview.MaterialTextView
import com.hcr.stormroot.R
import com.hcr.stormroot.core.overlay.OverlayService
import com.hcr.stormroot.core.permissions.OverlayPermission
import com.hcr.stormroot.databinding.FragmentOverlaysBinding

class OverlaysFragment : Fragment() {

    private lateinit var binding: FragmentOverlaysBinding
    private var isPreviewActive = false
    private var isFullscreenPreviewActive = false
    private var currentEffect = EffectTab.SOFT_MIST
    private var currentStrength = StrengthTab.BALANCED

    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (OverlayPermission.isGranted(requireContext())) {
            startFullscreenPreview()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* fullscreen preview still works without it, just without a visible notification */ }

    private enum class EffectTab(val captionRes: Int, val serviceEffect: String) {
        SOFT_MIST(R.string.preview_caption_soft_mist, OverlayService.EFFECT_SOFT_MIST),
        CALM_VINES(R.string.preview_caption_calm_vines, OverlayService.EFFECT_CALM_VINES),
        WARM_GLOW(R.string.preview_caption_warm_glow, OverlayService.EFFECT_WARM_GLOW)
    }

    private enum class StrengthTab(val labelRes: Int, val multiplier: Float) {
        SUBTLE(R.string.preview_strength_subtle, 0.4f),
        BALANCED(R.string.preview_strength_balanced, 0.7f),
        DEEP(R.string.preview_strength_deep, 1.0f)
    }

    private val strengthSteps = listOf(StrengthTab.SUBTLE, StrengthTab.BALANCED, StrengthTab.DEEP)

    private data class EffectTabViews(val container: View, val icon: ImageView, val label: MaterialTextView, val tab: EffectTab)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentOverlaysBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val effectTabs = listOf(
            EffectTabViews(binding.effectTabSoftMist, binding.effectTabSoftMistIcon, binding.effectTabSoftMistLabel, EffectTab.SOFT_MIST),
            EffectTabViews(binding.effectTabCalmVines, binding.effectTabCalmVinesIcon, binding.effectTabCalmVinesLabel, EffectTab.CALM_VINES),
            EffectTabViews(binding.effectTabWarmGlow, binding.effectTabWarmGlowIcon, binding.effectTabWarmGlowLabel, EffectTab.WARM_GLOW)
        )
        effectTabs.forEach { views ->
            views.container.setOnClickListener { selectEffectTab(views.tab, effectTabs, animate = true) }
        }
        selectEffectTab(EffectTab.SOFT_MIST, effectTabs, animate = false)

        binding.strengthSeekBar.max = strengthSteps.size - 1
        binding.strengthSeekBar.progress = strengthSteps.indexOf(StrengthTab.BALANCED)
        applyStrengthTab(StrengthTab.BALANCED)
        binding.strengthSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                applyStrengthTab(strengthSteps[progress])
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        binding.previewOverlaysButton.setOnClickListener { toggleMasterPreview() }
        binding.previewFullscreenButton.setOnClickListener { toggleFullscreenMode() }
        binding.previewFullscreenButton.visibility = View.GONE
        updatePreviewLivePill()
        updateFullscreenIcon()
    }

    override fun onDestroyView() {
        clearPreviewViews()
        if (isFullscreenPreviewActive) {
            OverlayService.stopFullscreenPreview(requireContext())
        }
        super.onDestroyView()
    }

    /** The main "Play/Stop Live Preview" button — the only thing that starts a preview
     *  session from scratch. It always lands on the in-app panel; the fullscreen icon
     *  (hidden until a session is active) is how you switch surfaces within that session. */
    private fun toggleMasterPreview() {
        if (isPreviewActive || isFullscreenPreviewActive) {
            stopAllPreviews()
        } else {
            startPreview()
        }
    }

    private fun stopAllPreviews() {
        if (isFullscreenPreviewActive) {
            OverlayService.stopFullscreenPreview(requireContext())
            isFullscreenPreviewActive = false
        }
        if (isPreviewActive) {
            clearPreviewViews()
            isPreviewActive = false
        }
        updateFullscreenIcon()
        updatePreviewButtonText()
        updatePreviewLivePill()
        updateFullscreenButtonVisibility()
    }

    /** Only reachable once a preview session is already active (button is hidden otherwise).
     *  Swaps which surface is showing the currently selected effect — panel or real screen —
     *  it never starts a session on its own. */
    private fun toggleFullscreenMode() {
        if (isFullscreenPreviewActive) {
            OverlayService.stopFullscreenPreview(requireContext())
            isFullscreenPreviewActive = false
            startPreview()
            return
        }
        if (!OverlayPermission.isGranted(requireContext())) {
            overlaySettingsLauncher.launch(OverlayPermission.requestIntent(requireContext()))
            return
        }
        startFullscreenPreview()
    }

    private fun startFullscreenPreview() {
        // Only one preview surface at a time — the real-screen overlay and the in-app
        // panel would otherwise both render simultaneously.
        if (isPreviewActive) {
            clearPreviewViews()
            isPreviewActive = false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        OverlayService.startFullscreenPreview(requireContext(), currentEffect.serviceEffect, currentStrength.multiplier)
        isFullscreenPreviewActive = true
        updateFullscreenIcon()
        updatePreviewButtonText()
        updatePreviewLivePill()
        updateFullscreenButtonVisibility()
    }

    private fun updateFullscreenIcon() {
        val tint = requireContext().getColorStateList(if (isFullscreenPreviewActive) R.color.primary else R.color.text_secondary)
        binding.previewFullscreenIcon.imageTintList = tint
    }

    private fun updateFullscreenButtonVisibility() {
        binding.previewFullscreenButton.visibility = if (isPreviewActive || isFullscreenPreviewActive) View.VISIBLE else View.GONE
    }

    private fun updatePreviewButtonText() {
        val active = isPreviewActive || isFullscreenPreviewActive
        binding.previewOverlaysButton.text = getString(
            if (active) R.string.overlays_preview_button_active else R.string.overlays_preview_button
        )
    }

    private fun startPreview() {
        // Only one preview surface at a time — see startFullscreenPreview().
        if (isFullscreenPreviewActive) {
            OverlayService.stopFullscreenPreview(requireContext())
            isFullscreenPreviewActive = false
        }

        clearPreviewViews()
        val multiplier = currentStrength.multiplier

        when (currentEffect) {
            EffectTab.SOFT_MIST -> {
                binding.previewFogView.visibility = View.VISIBLE
                binding.previewFogView.start()
                binding.previewFogView.intensity = multiplier
            }
            EffectTab.CALM_VINES -> {
                binding.previewRootsView.visibility = View.VISIBLE
                binding.previewRootsView.start()
                binding.previewRootsView.growth = 0.35f + 0.65f * multiplier
            }
            EffectTab.WARM_GLOW -> {
                binding.previewWarmScrim.visibility = View.VISIBLE
                binding.previewStormView.visibility = View.VISIBLE
                binding.previewWarmScrim.alpha = 0.1f + 0.2f * multiplier
                binding.previewStormView.start()
                binding.previewStormView.intensity = multiplier
            }
        }

        isPreviewActive = true
        updateFullscreenIcon()
        updatePreviewButtonText()
        updatePreviewLivePill()
        updateFullscreenButtonVisibility()
    }

    private fun clearPreviewViews() {
        binding.previewFogView.stop()
        binding.previewFogView.visibility = View.GONE

        binding.previewRootsView.stop()
        binding.previewRootsView.growth = 0f
        binding.previewRootsView.visibility = View.GONE

        binding.previewWarmScrim.visibility = View.GONE
        binding.previewWarmScrim.alpha = 0f
        binding.previewStormView.stop()
        binding.previewStormView.visibility = View.GONE
    }

    private fun updatePreviewLivePill() {
        binding.previewLivePill.visibility = if (isPreviewActive || isFullscreenPreviewActive) View.VISIBLE else View.GONE
    }

    private fun updateLivePreviewIfActive() {
        if (isPreviewActive) startPreview()
        if (isFullscreenPreviewActive) startFullscreenPreview()
    }

    private fun selectEffectTab(selected: EffectTab, tabs: List<EffectTabViews>, animate: Boolean) {
        if (animate) {
            TransitionManager.beginDelayedTransition(binding.effectTabs, AutoTransition().setDuration(180))
        }
        tabs.forEach { views ->
            val isSelected = views.tab == selected
            views.container.background = if (isSelected) {
                requireContext().getDrawable(R.drawable.bg_pill_outline_green)
            } else {
                null
            }
            val tint = requireContext().getColorStateList(if (isSelected) R.color.primary else R.color.text_secondary)
            views.icon.imageTintList = tint
            views.label.setTextColor(tint)
            views.label.setTypeface(views.label.typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            if (isSelected && animate) {
                views.container.animate().cancel()
                views.container.scaleX = 0.9f
                views.container.scaleY = 0.9f
                views.container.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
            }
        }
        binding.previewCaption.setText(selected.captionRes)
        currentEffect = selected
        updateLivePreviewIfActive()
    }

    private fun applyStrengthTab(selected: StrengthTab) {
        val labels = listOf(
            binding.strengthLabelSubtle to StrengthTab.SUBTLE,
            binding.strengthLabelBalanced to StrengthTab.BALANCED,
            binding.strengthLabelDeep to StrengthTab.DEEP
        )
        labels.forEach { (view, tab) ->
            val isSelected = tab == selected
            view.setTextColor(requireContext().getColorStateList(if (isSelected) R.color.primary else R.color.text_secondary))
            view.setTypeface(view.typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        }
        currentStrength = selected
        updateLivePreviewIfActive()
    }
}
