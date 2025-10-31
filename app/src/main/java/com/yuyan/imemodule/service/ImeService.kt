package com.yuyan.imemodule.service

import android.app.Presentation
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.yuyan.imemodule.keyboard.InputView
import com.yuyan.imemodule.keyboard.container.ClipBoardContainer
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.service.DecodingInfo
import com.yuyan.imemodule.data.emojicon.YuyanEmojiCompat
import android.view.inputmethod.EditorInfo
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.data.theme.ThemeManager.removeOnChangedListener
import com.yuyan.imemodule.view.preference.ManagedPreference
import com.yuyan.imemodule.utils.StringUtils
import android.app.Activity
import com.yuyan.imemodule.keyboard.container.CandidatesContainer
import android.view.inputmethod.InputConnection
import splitties.bitflags.hasFlag
import android.hardware.display.DisplayManager
import android.view.Display
import com.yuyan.imemodule.keyboard.container.T9TextContainer
import com.yuyan.imemodule.keyboard.container.HandwritingContainer
import com.yuyan.imemodule.keyboard.TextKeyboard
import android.content.res.Configuration
import kotlinx.coroutines.*
import com.yuyan.imemodule.utils.KeyboardLoaderUtil
import com.yuyan.imemodule.data.theme.ThemeManager.OnThemeChangeListener
import com.yuyan.imemodule.prefs.AppPrefs.Companion.getInstance
import android.widget.TextView
import android.util.TypedValue
import android.graphics.Color
import android.view.Gravity
import android.content.Context
import android.content.Intent

class ImeService : InputMethodService() {
    private var isWindowShown = false // 键盘窗口是否已显示
    private lateinit var mInputView: InputView
    private var secondaryPresentation: KeyboardPresentation? = null
    private var secondaryInputView: InputView? = null
    private var placeholderView: View? = null
    private var displayManager: DisplayManager? = null
    private val logTag = "YuyanImeDual"
    private var debugOverlayPrimary: TextView? = null

    private var lastDisplayState: EnvironmentSingleton.DisplayState? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            Log.d(logTag, "displayListener onDisplayAdded id=$displayId")
        }
        override fun onDisplayRemoved(displayId: Int) {
            val secId = secondaryPresentation?.display?.displayId
            Log.d(logTag, "displayListener onDisplayRemoved id=$displayId secId=$secId")
            if (secId != null && secId == displayId) {
                // 副屏被移除，确保窗口关闭
                dismissSecondary()
            }
        }
        override fun onDisplayChanged(displayId: Int) {
            val secId = secondaryPresentation?.display?.displayId
            Log.d(logTag, "displayListener onDisplayChanged id=$displayId secId=$secId")
            if (secId != null && secId == displayId) {
                // 旋转或尺寸变化：刷新标签与尺寸（避免频繁重建，尽量轻量更新）
                val dmSecSecSec2 = secondaryPresentation?.context?.resources?.displayMetrics
                if (dmSecSecSec2 != null) {
                    EnvironmentSingleton.instance.initDataWithDisplayMetrics(dmSecSecSec2)
                }
                refreshDebugLabel()
            }
        }
    }
    private val onThemeChangeListener = OnThemeChangeListener { _: Theme? -> if (getActiveInputView() != null) getActiveInputView()!!.updateTheme() }
    private val clipboardUpdateContent = getInstance().internal.clipboardUpdateContent
    private val clipboardUpdateContentListener = ManagedPreference.OnChangeListener<String> { _, value ->
        if(getInstance().clipboard.clipboardSuggestion.getValue()){
            if(value.isNotBlank()) {
                val activeView = getActiveInputView()
                if (activeView != null && activeView.isShown) {
                    if(KeyboardManager.instance.currentContainer is ClipBoardContainer
                        && (KeyboardManager.instance.currentContainer as ClipBoardContainer).getMenuMode() == SkbMenuMode.ClipBoard ){
                        (KeyboardManager.instance.currentContainer as ClipBoardContainer).showClipBoardView(SkbMenuMode.ClipBoard)
                    } else {
                        activeView.showSymbols(arrayOf(value))
                    }
                }
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager?.registerDisplayListener(displayListener, null)
        ThemeManager.addOnChangedListener(onThemeChangeListener)
        clipboardUpdateContent.registerOnChangeListener(clipboardUpdateContentListener)
    }

    private fun buildDebugOverlay(context: Context): TextView {
        val tv = TextView(context)
        tv.apply {
            text = ""
            setTextColor(Color.argb(0, 255, 0, 0))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            gravity = Gravity.CENTER
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            alpha = 1f
            elevation = 100f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }
        return tv
    }

    override fun onCreateInputView(): View {
        placeholderView = View(this)
        return placeholderView!!
    }

    override fun setInputView(view: View) {
        // 包装原主屏输入视图，加入调试 overlay
        val container = FrameLayout(this)
        container.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (view is InputView) {
            mInputView = view
            // 修复：避免重复添加已有父视图导致崩溃
            (view.parent as? ViewGroup)?.removeView(view)
            container.addView(view)
            val overlay = buildDebugOverlay(view.context)
            debugOverlayPrimary = overlay
            container.addView(overlay)
            super.setInputView(container)
        } else {
            super.setInputView(view)
        }
    }

    private fun getActiveInputView(): InputView? = secondaryInputView ?: if (::mInputView.isInitialized) mInputView else null

    @Synchronized
    private fun tryShowSecondary(editorInfo: EditorInfo, restarting: Boolean): Boolean {
        val dm = displayManager ?: getSystemService(DisplayManager::class.java)
        val displays = dm?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        val second = displays?.firstOrNull()
        Log.d(logTag, "tryShowSecondary: displays=${displays?.size ?: 0} second=${second}")
        if (second == null) return false

        // 若已经在同一副屏显示，则避免重复 show，仅刷新输入视图
        val alreadyShowingSame = secondaryPresentation?.isShowing == true &&
                (secondaryPresentation?.display?.displayId == second.displayId)
        if (alreadyShowingSame) {
            Log.d(logTag, "tryShowSecondary: already showing on displayId=${second.displayId}, skip re-show")
            try {
                secondaryInputView?.onStartInputView(editorInfo, restarting)
                refreshDebugLabel()
                return true
            } catch (t: Throwable) {
                Log.e(logTag, "tryShowSecondary: refresh existing failed, will recreate", t)
                // 失败则先关闭再重建
                dismissSecondary()
            }
        }

        val ctx = createDisplayContext(second)
        secondaryPresentation = KeyboardPresentation(ctx, second, this)
        try {
            secondaryPresentation!!.show()
            secondaryInputView = secondaryPresentation!!.inputView
            secondaryInputView!!.onStartInputView(editorInfo, restarting)
            Log.d(logTag, "tryShowSecondary: shown on ${second}")
            refreshDebugLabel()
            return true
        } catch (t: Throwable) {
            Log.e(logTag, "tryShowSecondary: failed", t)
            secondaryPresentation = null
            secondaryInputView = null
            return false
        }
    }

    private fun dismissSecondary() {
        Log.d(logTag, "dismissSecondary")
        secondaryInputView = null
        try { secondaryPresentation?.dismiss() } catch (t: Throwable) { Log.e(logTag, "dismissSecondary error", t) }
        secondaryPresentation = null
    }

    private fun refreshDebugLabel() {
        val isSecondary = secondaryInputView != null
        val forceFull = EnvironmentSingleton.instance.forceFullOnSecondary
        val dmSec = secondaryPresentation?.context?.resources?.displayMetrics
        val secLandscape = dmSec != null && dmSec.heightPixels <= dmSec.widthPixels
        val label: String = if (isSecondary) {
            if (forceFull) {
                "全屏"
            } else {
                if (secLandscape) "副屏横屏" else "副屏竖屏"
            }
        } else {
            "主屏竖屏"
        }
        if (isSecondary) {
            Log.d(logTag, "refreshDebugLabel secondary label=$label forceFull=$forceFull secMetrics=${dmSec?.widthPixels}x${dmSec?.heightPixels}")
            secondaryPresentation?.updateDebugLabel(label)
        } else {
            Log.d(logTag, "refreshDebugLabel primary label=$label")
            debugOverlayPrimary?.text = label
        }
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        Log.d(logTag, "onStartInputView restarting=$restarting")
        YuyanEmojiCompat.setEditorInfo(editorInfo)

        // 先获取主屏的显示尺寸与方向，作为副屏使用判断依据
        val primaryDisplay = try { displayManager?.getDisplay(Display.DEFAULT_DISPLAY) } catch (_: Throwable) { null }
        val primaryDm = try { primaryDisplay?.let { createDisplayContext(it).resources.displayMetrics } } catch (_: Throwable) { null }
            ?: baseContext.resources.displayMetrics
        val primaryLandscape = primaryDm.heightPixels <= primaryDm.widthPixels
        Log.d(logTag, "primary metrics=${primaryDm.widthPixels}x${primaryDm.heightPixels} landscape=$primaryLandscape")

        // 重新补回显示设备相关变量
        val primaryId = try { displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.displayId } catch (_: Throwable) { null } ?: Display.DEFAULT_DISPLAY
        val imeWindowDisplayId = try { this.window?.window?.decorView?.display?.displayId } catch (_: Throwable) { null }
            ?: try { placeholderView?.display?.displayId } catch (_: Throwable) { null }
        val activatedOnPrimary = imeWindowDisplayId == primaryId

        // 计算并设置副屏全屏偏好（仅主屏横屏且非浮动时）
        val prefOn = getInstance().dualScreen.dualForceFullscreenPrimary.getValue()
        val isFloat = EnvironmentSingleton.instance.keyboardModeFloat
        val desiredForceFull = prefOn && primaryLandscape && activatedOnPrimary && !isFloat
        EnvironmentSingleton.instance.forceFullOnSecondary = desiredForceFull
        Log.d(logTag, "activation displayId: primaryId=$primaryId imeWindowDisplayId=${imeWindowDisplayId} activatedOnPrimary=$activatedOnPrimary")
        Log.d(logTag, "forceFull desired=$desiredForceFull prefOn=$prefOn isFloat=$isFloat")

        // 始终先关闭旧的副屏窗口，避免残留
        dismissSecondary()

        val editorPkg = try { editorInfo.packageName } catch (_: Throwable) { null }
        Log.d(logTag, "debug: editorPkg=$editorPkg primaryLandscape=$primaryLandscape activatedOnPrimary=$activatedOnPrimary")

        // 按主屏方向决定副屏显示，仅横屏时尝试副屏
        val shouldShowOnSecondary = (!activatedOnPrimary) || primaryLandscape
        Log.d(logTag, "shouldShowOnSecondary=$shouldShowOnSecondary")
        val shownOnSecondary = if (shouldShowOnSecondary) {
            tryShowSecondary(editorInfo, restarting)
        } else {
            false
        }
        Log.d(logTag, "shownOnSecondary=$shownOnSecondary")

        // 根据实际显示设备重新初始化尺寸
        if (shownOnSecondary) {
            val dmSec2 = secondaryPresentation?.context?.resources?.displayMetrics
            Log.d(logTag, "init with secondary metrics=${dmSec2?.widthPixels}x${dmSec2?.heightPixels}")
            val secLandscape = dmSec2 != null && dmSec2.heightPixels <= dmSec2.widthPixels
            EnvironmentSingleton.instance.currentDisplayState =
                if (EnvironmentSingleton.instance.forceFullOnSecondary) EnvironmentSingleton.DisplayState.SecondaryFullscreen
                else if (secLandscape) EnvironmentSingleton.DisplayState.SecondaryLandscape
                else EnvironmentSingleton.DisplayState.SecondaryPortrait
            if (dmSec2 != null) EnvironmentSingleton.instance.initDataWithDisplayMetrics(dmSec2) else EnvironmentSingleton.instance.initData()
        } else {
            Log.d(logTag, "init with primary metrics=${primaryDm.widthPixels}x${primaryDm.heightPixels}")
            EnvironmentSingleton.instance.currentDisplayState = EnvironmentSingleton.DisplayState.PrimaryPortrait
            EnvironmentSingleton.instance.clearDisplayMetricsOverride()
            EnvironmentSingleton.instance.initDataWithDisplayMetrics(primaryDm)
        }

        if (!shownOnSecondary) {
            if (!::mInputView.isInitialized) {
                mInputView = InputView(baseContext, this)
            }
            setInputView(mInputView)
            Log.d(logTag, "setInputView primary with ${mInputView.javaClass.simpleName}")
            mInputView.onStartInputView(editorInfo, restarting)
        }

        // 如果显示状态发生变化，执行一次“切到另一个键盘再切回”的快速重排
        applyRelayoutIfDisplayChanged()

        // 最后刷新调试标签，确保当前场景被正确显示
        refreshDebugLabel()
    }

    private fun applyRelayoutIfDisplayChanged() {
        val current = EnvironmentSingleton.instance.currentDisplayState
        if (lastDisplayState != current) {
            Log.d(logTag, "display state changed: ${lastDisplayState} -> ${current}, force relayout by toggle")
            try {
                KeyboardManager.instance.forceRelayoutByToggle()
            } catch (t: Throwable) {
                Log.e(logTag, "forceRelayoutByToggle failed", t)
            }
            lastDisplayState = current
        } else {
            // 同一状态下不重排，避免不必要闪烁
            Log.d(logTag, "display state unchanged: ${current}")
        }
    }

    override fun onStartInput(editorInfo: EditorInfo, restarting: Boolean) {
        super.onStartInput(editorInfo, restarting)
        val pkg = try { editorInfo.packageName } catch (_: Throwable) { null }
        Log.d(logTag, "onStartInput: pkg=$pkg restarting=$restarting")
    }

    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        return super.onShowInputRequested(flags, configChange)
    }

    override fun onBindInput() {
        super.onBindInput()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        isWindowShown = true
        getActiveInputView()?.onWindowShown()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        getActiveInputView()?.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesEnd)
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        isWindowShown = false
        getActiveInputView()?.onWindowHidden()
        dismissSecondary()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        getActiveInputView()?.resetToIdleState()
        dismissSecondary()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        getActiveInputView()?.resetToIdleState()
        dismissSecondary()
    }

    override fun onUnbindInput() {
        super.onUnbindInput()
        getActiveInputView()?.resetToIdleState()
        dismissSecondary()
    }

    fun sendDeleteKeyEvent()
    {
        val eventTime = SystemClock.uptimeMillis()
        sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_DEL)
        sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_DEL)
    }

    private fun sendKeyUpEvent(keyEventCode: Int, eventTime: Long){
        val event = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyEventCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0)
        val ic = currentInputConnection
        if(ic!=null) ic.sendKeyEvent(event)
    }

    fun sendEnterKeyEvent(){
        val eventTime = SystemClock.uptimeMillis()
        sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_ENTER)
        sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_ENTER)
    }

    private fun sendDownKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0){
        val event = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyEventCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0)
        val ic = currentInputConnection
        if(ic!=null) ic.sendKeyEvent(event)
    }

    private fun sendUpKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0){
        val event = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyEventCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0)
        val ic = currentInputConnection
        if(ic!=null) ic.sendKeyEvent(event)
    }

    fun sendCombinationKeyEvents(keyCode: Int, shift: Boolean = false) {
        val eventTime = SystemClock.uptimeMillis()
        if (shift) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        sendDownKeyEvent(eventTime, keyCode)
        sendUpKeyEvent(eventTime, keyCode)
        if (shift) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
    }

    fun commitText(text: String) {
        val ic: InputConnection? = currentInputConnection
        ic?.commitText(text, 1)
    }

    fun setComposingText(text: CharSequence) {
        currentInputConnection?.setComposingText(text, 1)
    }

    fun finishComposingText() {
        currentInputConnection?.finishComposingText()
    }

    fun forceHideKeyboard() {
        // 收起系统输入法并清理副屏
        try { requestHideSelf(0) } catch (_: Throwable) {}
        isWindowShown = false
        dismissSecondary()
    }

    fun getTextBeforeCursor(length:Int) : String{
        val ic: InputConnection? = currentInputConnection
        return StringUtils.fromCSDString(ic?.getTextBeforeCursor(length, 0)?.toString())
    }

    fun commitTextEditMenu(id:Int){
        val ic: InputConnection? = currentInputConnection
        ic?.performContextMenuAction(id)
    }

    fun performEditorAction(editorAction:Int){
        val ic: InputConnection? = currentInputConnection
        ic?.performEditorAction(editorAction)
    }

    fun deleteSurroundingText(length:Int){
        val ic: InputConnection? = currentInputConnection
        ic?.deleteSurroundingText(length, 0)
    }

    fun setSelection(start: Int, end: Int){
        val ic: InputConnection? = currentInputConnection
        ic?.setSelection(start, end)
    }

    override fun onComputeInsets(outInsets: InputMethodService.Insets) {
        val activeView = getActiveInputView() ?: return
        // 副屏场景：主屏不受输入法影响，不调整内容区域（避免主屏被全屏输入框覆盖）
        if (secondaryInputView != null) {
            outInsets.apply {
                contentTopInsets = EnvironmentSingleton.instance.mScreenHeight
                visibleTopInsets = EnvironmentSingleton.instance.mScreenHeight
                touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_CONTENT
                touchableRegion.setEmpty()
            }
            Log.d(logTag, "onComputeInsets: secondary active, primary unaffected")
            return
        }
        val (x, y) = intArrayOf(0, 0).also { if(activeView.isAddPhrases) activeView.mAddPhrasesLayout.getLocationInWindow(it) else activeView.mSkbRoot.getLocationInWindow(it) }
        outInsets.apply {
            if(EnvironmentSingleton.instance.keyboardModeFloat) {
                contentTopInsets = EnvironmentSingleton.instance.mScreenHeight
                visibleTopInsets = EnvironmentSingleton.instance.mScreenHeight
                touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
                touchableRegion.set(x, y, x + activeView.mSkbRoot.width, y + activeView.mSkbRoot.height)
            } else {
                contentTopInsets = y
                touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_CONTENT
                touchableRegion.setEmpty()
                visibleTopInsets = y
            }
        }
    }
}