package com.yuyan.imemodule.singleton

import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.theme.ThemeManager.prefs
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.utils.DevicesUtils
import kotlin.math.max
import kotlin.math.min
import android.util.DisplayMetrics

class EnvironmentSingleton private constructor() {
    var systemNavbarWindowsBottom = 0 // 导航栏高度
    var mScreenWidth = 0 // 屏幕的宽度
    var mScreenHeight = 0 // 屏幕的高度
    var inputAreaHeight = 0 // 键盘区域高度
    var inputAreaWidth = 0 // 键盘区域宽度
    var skbWidth = 0 // 键盘按键区域、候选词区域宽度
        private set
    var skbHeight = 0 // 键盘按键区高度
        private set
    var holderWidth = 0 // 单手模式下键盘占位区域宽度
        private set
    var heightForCandidatesArea = 0 // 候选词区域的高度
    var heightForcomposing = 0 // 候选词拼音区域的高度
    var heightForCandidates = 0 // 候选词区域的高度
    var heightForFullDisplayBar = 0 // 智能导航栏高度
    var heightForKeyboardMove = 0 // 悬浮键盘移动条高度
    var keyTextSize = 0 // 正常按键中文本的大小
    var keyTextSmallSize = 0 // 正常按键中文本的大小,小值
    var candidateTextSize = 0f // 候选词字体大小
    var composingTextSize = 0f // 候选词字体大小
    var isLandscape = false //键盘是否横屏
    var keyXMargin = 0 //键盘按键水平间距
    var keyYMargin = 0 //键盘按键垂直间距
    private var keyboardHeightRatio = 0f
    var forceFullOnSecondary = false
    private var lastDisplayMetrics: DisplayMetrics? = null
    // 四种显示状态枚举与当前状态
    enum class DisplayState { PrimaryPortrait, SecondaryPortrait, SecondaryLandscape, SecondaryFullscreen }
    var currentDisplayState: DisplayState = DisplayState.PrimaryPortrait

    init {
        initData()
    }

    fun initData() {
        val resources = Launcher.instance.context.resources
        val dm = lastDisplayMetrics ?: resources.displayMetrics
        reinitWithDisplayMetrics(dm)
    }

    fun initDataWithDisplayMetrics(dm: DisplayMetrics) {
        lastDisplayMetrics = dm
        reinitWithDisplayMetrics(dm)
    }

    fun clearDisplayMetricsOverride() {
        lastDisplayMetrics = null
    }

    private fun reinitWithDisplayMetrics(dm: DisplayMetrics) {
        mScreenWidth = dm.widthPixels
        mScreenHeight = dm.heightPixels
        isLandscape = mScreenHeight <= mScreenWidth
        val screenWidthVertical = min(dm.widthPixels, dm.heightPixels)
        val screenHeightVertical = max(dm.widthPixels, dm.heightPixels)

        // 键盘尺寸计算（不再考虑悬浮或单手占位）
        inputAreaWidth = screenWidthVertical
        holderWidth = 0
        skbWidth = screenWidthVertical

        if(isLandscape){
            inputAreaWidth = mScreenWidth
            skbWidth = inputAreaWidth
        }

        // 根据显示状态选择独立的高度比例；全屏状态默认按候选区以下区域自动填充，当设置自定义比例 (>0) 时优先生效
        val prefsInternal = AppPrefs.getInstance().internal
        val ratio = when (currentDisplayState) {
            DisplayState.PrimaryPortrait -> prefsInternal.heightRatioPrimaryPortrait.getValue()
            DisplayState.SecondaryPortrait -> prefsInternal.heightRatioSecondaryPortrait.getValue()
            DisplayState.SecondaryLandscape -> prefsInternal.heightRatioSecondaryLandscape.getValue()
            DisplayState.SecondaryFullscreen -> {
                val pref = prefsInternal.heightRatioSecondaryFullscreen.getValue()
                if (pref > 0f) pref else 0.7f
            }
        }
        keyboardHeightRatio = ratio

        skbHeight = (screenHeightVertical * keyboardHeightRatio).toInt()

        // 候选栏高度改为基于键盘高度计算，统一使用 0.1 比例
        val candidatesHeightRatio = 0.1f
        heightForcomposing = (skbHeight * candidatesHeightRatio *
                AppPrefs.getInstance().keyboardSetting.candidateTextSize.getValue() / 100f).toInt()
        heightForCandidates = (heightForcomposing * 1.9).toInt()
        heightForCandidatesArea = (heightForcomposing * 2.9).toInt()
        composingTextSize = DevicesUtils.px2sp (heightForcomposing)
        candidateTextSize = DevicesUtils.px2sp (heightForCandidates)
        heightForFullDisplayBar = 0
        heightForKeyboardMove = 0

        val keyboardFontSizeRatio = prefs.keyboardFontSize.getValue()/100f
        keyTextSize = (skbHeight * 0.06f * keyboardFontSizeRatio).toInt()
        keyTextSmallSize = (skbHeight * 0.04f * keyboardFontSizeRatio).toInt()
        keyXMargin = (prefs.keyXMargin.getValue() / 1000f * skbWidth).toInt()
        keyYMargin = (prefs.keyYMargin.getValue() / 1000f * skbHeight).toInt()
        inputAreaHeight = skbHeight + heightForCandidatesArea
    }

    var keyBoardHeightRatio: Float
        get() = keyboardHeightRatio
        set(keyBoardHeightRatio) {
            keyboardHeightRatio = keyBoardHeightRatio
            val internal = AppPrefs.getInstance().internal
            when (currentDisplayState) {
                DisplayState.PrimaryPortrait -> internal.heightRatioPrimaryPortrait.setValue(keyBoardHeightRatio)
                DisplayState.SecondaryPortrait -> internal.heightRatioSecondaryPortrait.setValue(keyBoardHeightRatio)
                DisplayState.SecondaryLandscape -> internal.heightRatioSecondaryLandscape.setValue(keyBoardHeightRatio)
                DisplayState.SecondaryFullscreen -> internal.heightRatioSecondaryFullscreen.setValue(keyBoardHeightRatio)
            }
        }

    var keyboardModeFloat:Boolean = false
        set(isFloatMode) {
            field = isFloatMode
        }

    val skbAreaHeight:Int
        get() = instance.inputAreaHeight + AppPrefs.getInstance().internal.keyboardBottomPadding.getValue() + instance.systemNavbarWindowsBottom

    val leftMarginWidth:Int
        get() = (instance.inputAreaWidth - instance.skbWidth)/2

    companion object {
        private var mInstance: EnvironmentSingleton? = null
        @JvmStatic
        val instance: EnvironmentSingleton
            get() {
                if (null == mInstance) {
                    mInstance = EnvironmentSingleton()
                }
                return mInstance!!
            }
    }
}
