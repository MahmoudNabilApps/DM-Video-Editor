package com.DM.VideoEditor

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Applies [WindowInsetsCompat] padding for edge-to-edge (API 26–35+).
 * Uses status bar, navigation bar, and display cutout only — **not** mandatorySystemGestures,
 * which can report very large horizontal insets on some devices and shrinks toolbars badly.
 */
object EdgeToEdgeInsets {

    private val barsAndCutout: Int =
        WindowInsetsCompat.Type.statusBars() or
            WindowInsetsCompat.Type.navigationBars() or
            WindowInsetsCompat.Type.displayCutout()

    /**
     * Top bar: keep content below status bar / notch; respect horizontal safe areas.
     */
    fun applyTopBarInsets(view: View) {
        val pl = view.paddingLeft
        val pt = view.paddingTop
        val pr = view.paddingRight
        val pb = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val r = insets.getInsets(barsAndCutout)
            v.updatePadding(
                left = pl + r.left,
                top = pt + r.top,
                right = pr + r.right,
                bottom = pb
            )
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /**
     * Bottom bar (tabs, categories): keep tappable targets above navigation / gesture bar.
     */
    fun applyBottomBarInsets(view: View) {
        val pl = view.paddingLeft
        val pt = view.paddingTop
        val pr = view.paddingRight
        val pb = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val r = insets.getInsets(barsAndCutout)
            v.updatePadding(
                left = pl + r.left,
                top = pt,
                right = pr + r.right,
                bottom = pb + r.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /**
     * Row above bottom bar: horizontal safe area only (bottom inset applied on bar below).
     */
    fun applyHorizontalInsets(view: View) {
        val pl = view.paddingLeft
        val pt = view.paddingTop
        val pr = view.paddingRight
        val pb = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val r = insets.getInsets(barsAndCutout)
            v.updatePadding(
                left = pl + r.left,
                top = pt,
                right = pr + r.right,
                bottom = pb
            )
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
