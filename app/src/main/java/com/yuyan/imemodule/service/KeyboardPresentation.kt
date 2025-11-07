package com.yuyan.imemodule.service

import android.app.Presentation
import android.content.Context
import android.content.res.Resources
import android.view.ContextThemeWrapper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.util.TypedValue
import android.util.Log
import com.yuyan.imemodule.R
import com.yuyan.imemodule.keyboard.InputView
import com.yuyan.imemodule.singleton.EnvironmentSingleton

class KeyboardPresentation(
    ctx: Context,
    display: Display,
    private val service: ImeService
) : Presentation(ContextThemeWrapper(ctx, R.style.Theme_AppTheme), display) {
    lateinit var inputView: InputView
    // 调试覆盖层已隐藏
    private var debugOverlay: TextView? = null

    private fun buildDebugOverlay(context: Context): TextView {
        // 调试覆盖层已隐藏
        val tv = TextView(context)
        tv.apply {
            text = ""
            // setTextColor(Color.argb(255, 255, 0, 0))
            // setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            // gravity = Gravity.CENTER
            // isClickable = false
            // isFocusable = false
            // isFocusableInTouchMode = false
            // importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            // alpha = 1f
            // elevation = 100f
            // layoutParams = FrameLayout.LayoutParams(
            //     ViewGroup.LayoutParams.MATCH_PARENT,
            //     ViewGroup.LayoutParams.MATCH_PARENT,
            //     Gravity.CENTER
            // )
        }
        return tv
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Log.d("YuyanImeDual", "KeyboardPresentation.onCreate displayId=${display.displayId} forceFull=${EnvironmentSingleton.instance.forceFullOnSecondary}") // 调试标签已隐藏
        // 在副屏创建时，先用副屏的 DisplayMetrics 刷新一次环境，避免首次构建读主屏尺寸导致裁切
        EnvironmentSingleton.instance.initDataWithDisplayMetrics(context.resources.displayMetrics)
        val dm = context.resources.displayMetrics
        // Log.d("YuyanImeDual", "KeyboardPresentation metrics=${dm.widthPixels}x${dm.heightPixels}") // 调试标签已隐藏
        // 避免副屏窗口抢占焦点导致输入连接中断，仅保留不获取焦点标记
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        // 去除后面内容暗化
        window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        val forceFull = EnvironmentSingleton.instance.forceFullOnSecondary
        if (forceFull) {
            // 主屏激活+开关开启：全屏黑色覆盖，键盘底部对齐
            window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            window?.setGravity(Gravity.BOTTOM)

            val root = FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(Color.BLACK)
            }
            inputView = InputView(context, service).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            }
            root.addView(inputView)
            // 调试覆盖层已隐藏
            // debugOverlay = buildDebugOverlay(context)
            // debugOverlay?.alpha = 1f
            // root.addView(debugOverlay)
            setContentView(root)
        } else {
            // 副屏激活或开关关闭：透明背景、非全屏，仅底部对齐键盘
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            window?.setGravity(Gravity.BOTTOM)

            val root = FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setBackgroundColor(Color.TRANSPARENT)
            }
            inputView = InputView(context, service).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            }
            root.addView(inputView)
            // 调试覆盖层已隐藏
            // debugOverlay = buildDebugOverlay(context)
            // debugOverlay?.alpha = 1f
            // root.addView(debugOverlay)
            setContentView(root)
        }
    }

    fun updateDebugLabel(text: String) {
        // 调试覆盖层已隐藏
        // debugOverlay?.text = text
    }
}