package com.hcr.stormroot.core.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.isEmpty

class OverlayWindowController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: FrameLayout? = null
    private var currentParams: WindowManager.LayoutParams? = null

    // The main overlay window is FLAG_NOT_TOUCHABLE so nudge effects never block interaction
    // with the app underneath — a small "Not now" control needs its own separate, touchable
    // window instead of living inside that click-through layer.
    private var snoozeRootView: FrameLayout? = null

    val isShowing: Boolean get() = rootView != null

    fun setLayer(tag: String, view: View?) {
        val root = ensureRoot()
        root.findViewWithTag<View>(tag)?.let { root.removeView(it) }
        if (view != null) {
            view.tag = tag
            root.addView(view)
        }
        if (root.isEmpty()) {
            hide()
        }
    }

    fun hide() {
        hideSnoozeControl()
        val root = rootView ?: return
        windowManager.removeView(root)
        rootView = null
        currentParams = null
    }

    fun updateBlurBehind(radiusPx: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val root = rootView ?: return
        val params = currentParams ?: return
        if (radiusPx > 0) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            params.blurBehindRadius = radiusPx
        } else {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
            params.blurBehindRadius = 0
        }
        windowManager.updateViewLayout(root, params)
    }

    fun showSnoozeControl(view: View) {
        val root = snoozeRootView ?: FrameLayout(context).also { newRoot ->
            windowManager.addView(newRoot, buildSnoozeLayoutParams())
            snoozeRootView = newRoot
        }
        root.removeAllViews()
        root.addView(view)
    }

    fun hideSnoozeControl() {
        val root = snoozeRootView ?: return
        windowManager.removeView(root)
        snoozeRootView = null
    }

    private fun buildSnoozeLayoutParams(): WindowManager.LayoutParams {
        @Suppress("DEPRECATION")
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (48 * context.resources.displayMetrics.density).toInt()
        }
    }

    private fun ensureRoot(): FrameLayout {
        rootView?.let { return it }
        val params = buildLayoutParams()
        val root = FrameLayout(context)
        windowManager.addView(root, params)
        rootView = root
        currentParams = params
        return root
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        @Suppress("DEPRECATION")
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }
}
