package com.yuyan.imemodule.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.yuyan.imemodule.R
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.data.theme.ThemeManager.activeTheme
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.ui.fragment.theme.ThemeListFragment
import com.yuyan.imemodule.ui.fragment.theme.ThemeSettingsFragment
import com.yuyan.imemodule.utils.KeyboardLoaderUtil
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.keyboard.KeyboardPreviewView
import kotlinx.coroutines.launch
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent


class ThemeFragment : Fragment() {

    private lateinit var previewUi: KeyboardPreviewView

    private lateinit var tabLayout: TabLayout

    private lateinit var viewPager: ViewPager2

    private val onThemeChangeListener = ThemeManager.OnThemeChangeListener {
        lifecycleScope.launch {
            EnvironmentSingleton.instance.initData()
            KeyboardLoaderUtil.instance.clearKeyboardMap()
            KeyboardManager.instance.clearKeyboard()
            previewUi.setTheme(it)
        }
    }

    override fun onResume() {
        super.onResume()
        ThemeManager.addOnChangedListener(onThemeChangeListener)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val ctx = requireContext()
        previewUi = KeyboardPreviewView(ctx).apply {
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            setTheme(activeTheme)
        }
        tabLayout = TabLayout(ctx)
        viewPager = ViewPager2(ctx).apply {
            adapter = object : FragmentStateAdapter(this@ThemeFragment) {
                override fun getItemCount(): Int = 2
                override fun createFragment(position: Int): Fragment =
                    if (position == 0) ThemeSettingsFragment() else ThemeListFragment()
            }
        }
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = if (position == 0) ctx.getString(R.string.theme) else ctx.getString(R.string.more)
        }.attach()

        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        return with(ctx) {
            if (isLandscape) {
                // 横屏：左右结构，左侧预览较小，右侧为Tab+设置项较大
                val dm = resources.displayMetrics
                val leftWeight = 0.45f
                val sideMarginPx = dp(12)
                val env = EnvironmentSingleton.instance
                val skbW = if (env.skbWidth > 0) env.skbWidth else (dm.widthPixels * 0.7f).toInt()
                val inputH = if (env.inputAreaHeight > 0) env.inputAreaHeight else (dm.heightPixels * 0.35f).toInt()
                val effectiveLeftWidth = (dm.widthPixels * leftWeight) - sideMarginPx * 2
                val scaleW = effectiveLeftWidth / skbW.toFloat()
                val scaleH = dm.heightPixels / inputH.toFloat()
                var scale = kotlin.math.min(scaleW, scaleH)
                if (!scale.isFinite()) scale = 0.5f
                scale = scale.coerceIn(0.1f, 1.0f)
                previewUi.scaleX = scale
                previewUi.scaleY = scale

                val previewPane = constraintLayout {
                    val previewParams = lParams(skbW, inputH) {
                        centerHorizontally()
                        topOfParent()
                        bottomOfParent()
                    }
                    previewParams.leftMargin = sideMarginPx
                    previewParams.rightMargin = sideMarginPx
                    add(previewUi, previewParams)
                    backgroundColor = styledColor(android.R.attr.colorPrimary)
                    elevation = dp(4f)
                }
                val rightPane = constraintLayout {
                    add(tabLayout, lParams(matchParent, wrapContent) {
                        topOfParent()
                        startOfParent()
                        endOfParent()
                    })
                    // 关键：ViewPager2高度使用0dp以匹配约束，保证Tab下方内容可见且可滚动
                    add(viewPager, lParams(matchParent, 0) {
                        below(tabLayout)
                        startOfParent()
                        endOfParent()
                        bottomOfParent()
                    })
                    backgroundColor = styledColor(android.R.attr.colorBackground)
                }
                // 使用标准LinearLayout构建左右分栏，右侧比左侧更宽
                val root = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    weightSum = 1f
                }
                root.addView(
                    previewPane,
                    android.widget.LinearLayout.LayoutParams(
                        0,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        leftWeight
                    )
                )
                root.addView(
                    rightPane,
                    android.widget.LinearLayout.LayoutParams(
                        0,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        1f - leftWeight
                    )
                )
                root
            } else {
                // 竖屏：保持原上下结构，并使用0.5缩放保证预览不遮挡Tab
                previewUi.scaleX = 0.5f
                previewUi.scaleY = 0.5f

                val previewWrapper = constraintLayout {
                    add(previewUi, lParams(EnvironmentSingleton.instance.skbWidth, EnvironmentSingleton.instance.inputAreaHeight) {
                        topOfParent(dp(-52))
                        startOfParent()
                        endOfParent()
                    })
                    add(tabLayout, lParams(matchParent, wrapContent) {
                        centerHorizontally()
                        bottomOfParent()
                    })
                    backgroundColor = styledColor(android.R.attr.colorPrimary)
                    elevation = dp(4f)
                }
                constraintLayout {
                    add(previewWrapper, lParams(height = wrapContent) {
                        topOfParent()
                        startOfParent()
                        endOfParent()
                    })
                    // 关键：ViewPager2高度改为0dp并使用约束填充剩余空间，保证Tab下方内容可见且可滚动
                    add(viewPager, lParams(matchParent, 0) {
                        below(previewWrapper)
                        startOfParent()
                        endOfParent()
                        bottomOfParent()
                    })
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
    }
}