package com.yuyan.imemodule.view.popup

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.ViewOutlineProvider
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.prefs.behavior.PopupMenuMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import splitties.views.gravityCenter
import splitties.views.gravityCenterHorizontal

class PopupPermissionRequestUi(
    bounds: Rect,
    onDismissSelf: PopupContainerUi.() -> Unit = {},
    private val radius: Float,
    private val keyWidth: Int,
    private val title: String,
    private val buttonText: String,
    private val onButtonClick: () -> Unit
) : PopupContainerUi(Launcher.instance.context, bounds, onDismissSelf) {

    override val root = verticalLayout {
        background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(ThemeManager.activeTheme.popupBackgroundColor)
        }
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true

        // 标题文本
        add(textView {
            text = title
            setTextColor(ThemeManager.activeTheme.keyTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, EnvironmentSingleton.instance.keyTextSize * 0.9f)
            gravity = gravityCenter
        }, lParams(matchParent, dp(40f).toInt()) {
            topMargin = dp(8).toInt()
            leftMargin = dp(12).toInt()
            rightMargin = dp(12).toInt()
        })

        // 按钮
        add(textView {
            text = buttonText
            setTextColor(ThemeManager.activeTheme.keyTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, EnvironmentSingleton.instance.keyTextSize.toFloat())
            gravity = gravityCenter
            background = GradientDrawable().apply {
                cornerRadius = radius * 0.8f
                setColor(ThemeManager.activeTheme.popupBackgroundColor)
                setStroke(2, android.graphics.Color.BLACK)
            }
            setOnClickListener {
                onButtonClick()
                onDismissSelf()
            }
        }, lParams(matchParent, dp(36f).toInt()) {
            topMargin = dp(8).toInt()
            bottomMargin = dp(8).toInt()
            leftMargin = dp(8).toInt()
            rightMargin = dp(8).toInt()
        })
    }

    override val offsetY: Int
        get() {
            val totalHeight: Int = root.height + bounds.height() / 2 + Launcher.instance.context.dp(8f).toInt()
            return -totalHeight
        }

    private var isFocused = false

    override fun onChangeFocus(x: Float, y: Float): Boolean {
        val wasFocused = isFocused
        isFocused = x >= 0 && x <= root.width && y >= 0 && y <= root.height
        
        if (wasFocused != isFocused) {
            // 更新按钮的视觉状态
            root.getChildAt(1)?.background = GradientDrawable().apply {
                cornerRadius = radius * 0.8f
                setColor(if (isFocused) ThemeManager.activeTheme.keyPressHighlightColor else ThemeManager.activeTheme.popupBackgroundColor)
                setStroke(2, android.graphics.Color.BLACK)
            }
        }
        
        return isFocused
    }

    override fun onGestureEvent(distanceX: Float) {
        // 不需要处理手势事件
    }

    override fun onTrigger(): Pair<PopupMenuMode, String> {
        if (isFocused) {
            onButtonClick()
        }
        return Pair(PopupMenuMode.None, "")
    }
}