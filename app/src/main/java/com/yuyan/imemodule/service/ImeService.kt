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
import android.content.BroadcastReceiver
import android.content.IntentFilter
import com.yuyan.imemodule.voice.VoiceRecognitionManager

class ImeService : InputMethodService() {
    private var isWindowShown = false // 键盘窗口是否已显示
    private lateinit var mInputView: InputView
    private var secondaryPresentation: KeyboardPresentation? = null
    private var secondaryInputView: InputView? = null
    private var placeholderView: View? = null
    private var displayManager: DisplayManager? = null
    private val logTag = "YuyanImeDual"
    // 调试覆盖层已隐藏
    private var debugOverlayPrimary: TextView? = null
    private var debugContextInfo: String = ""

    private var lastDisplayState: EnvironmentSingleton.DisplayState? = null
    
    // 权限请求结果广播接收器
    private val permissionResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.yuyan.imemodule.PERMISSION_RESULT") {
                val granted = intent.getBooleanExtra("granted", false)
                handlePermissionResult(granted)
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            // Log.d(logTag, "displayListener onDisplayAdded id=$displayId") // 调试标签已隐藏
        }
        override fun onDisplayRemoved(displayId: Int) {
            val secId = secondaryPresentation?.display?.displayId
            // Log.d(logTag, "displayListener onDisplayRemoved id=$displayId secId=$secId") // 调试标签已隐藏
            if (secId != null && secId == displayId) {
                // 副屏被移除，确保窗口关闭
                dismissSecondary()
            }
        }
        override fun onDisplayChanged(displayId: Int) {
            val secId = secondaryPresentation?.display?.displayId
            // Log.d(logTag, "displayListener onDisplayChanged id=$displayId secId=$secId") // 调试标签已隐藏
            if (secId != null && secId == displayId) {
                // 旋转或尺寸变化：刷新标签与尺寸（避免频繁重建，尽量轻量更新）
                val dmSecSecSec2 = secondaryPresentation?.context?.resources?.displayMetrics
                if (dmSecSecSec2 != null) {
                    EnvironmentSingleton.instance.initDataWithDisplayMetrics(dmSecSecSec2)
                }
                // refreshDebugLabel() // 调试标签已隐藏
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
            // 调试覆盖层已隐藏
            // val overlay = buildDebugOverlay(view.context)
            // debugOverlayPrimary = overlay
            // container.addView(overlay)
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
        // Log.d(logTag, "tryShowSecondary: displays=${displays?.size ?: 0} second=${second}") // 调试标签已隐藏
        if (second == null) return false

        // 若已经在同一副屏显示，则避免重复 show，仅刷新输入视图
        val alreadyShowingSame = secondaryPresentation?.isShowing == true &&
                (secondaryPresentation?.display?.displayId == second.displayId)
        if (alreadyShowingSame) {
            // Log.d(logTag, "tryShowSecondary: already showing on displayId=${second.displayId}, skip re-show") // 调试标签已隐藏
            try {
                secondaryInputView?.onStartInputView(editorInfo, restarting)
                // refreshDebugLabel() // 调试标签已隐藏
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
            // Log.d(logTag, "tryShowSecondary: shown on ${second}") // 调试标签已隐藏
            // refreshDebugLabel() // 调试标签已隐藏
            return true
        } catch (t: Throwable) {
            Log.e(logTag, "tryShowSecondary: failed", t)
            secondaryPresentation = null
            secondaryInputView = null
            return false
        }
    }

    private fun dismissSecondary() {
        // Log.d(logTag, "dismissSecondary") // 调试标签已隐藏
        secondaryInputView = null
        try { secondaryPresentation?.dismiss() } catch (t: Throwable) { Log.e(logTag, "dismissSecondary error", t) }
        secondaryPresentation = null
    }

    private fun refreshDebugLabel() {
        val state = EnvironmentSingleton.instance.currentDisplayState
        val label = when (state) {
            // 调试标签已隐藏
            EnvironmentSingleton.DisplayState.SecondaryFullscreen -> "全屏"
            EnvironmentSingleton.DisplayState.SecondaryLandscape -> "副屏横屏"
            EnvironmentSingleton.DisplayState.SecondaryPortrait -> "副屏竖屏"
            EnvironmentSingleton.DisplayState.PrimaryPortrait -> "主屏竖屏"
        }
        val info = buildString {
            append(label)
            if (debugContextInfo.isNotEmpty()) {
                append('\n')
                append(debugContextInfo)
            }
        }
        if (secondaryPresentation != null && state == EnvironmentSingleton.DisplayState.SecondaryFullscreen) {
            val dmSec = secondaryPresentation?.context?.resources?.displayMetrics
            // Log.d(logTag, "refreshDebugLabel secondary(full) label=$label secMetrics=${dmSec?.widthPixels}x${dmSec?.heightPixels}") // 调试标签已隐藏
            // 调试覆盖层已隐藏
            // secondaryPresentation?.updateDebugLabel(info)
        } else {
            // Log.d(logTag, "refreshDebugLabel primary(normal) label=$label") // 调试标签已隐藏
            // 调试覆盖层已隐藏
            // debugOverlayPrimary?.text = info
        }
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        // Log.d(logTag, "onStartInputView restarting=$restarting") // 调试标签已隐藏
        YuyanEmojiCompat.setEditorInfo(editorInfo)

        // 先获取主屏的显示尺寸与方向，作为副屏使用判断依据
        val currentWindowDisplay = try { window?.window?.decorView?.display } catch (_: Throwable) { null }
        val currentWindowMetrics = try {
            currentWindowDisplay?.let { createDisplayContext(it).resources.displayMetrics }
        } catch (_: Throwable) { null } ?: baseContext.resources.displayMetrics
        val currentResources = try {
            currentWindowDisplay?.let { createDisplayContext(it).resources }
        } catch (_: Throwable) { null } ?: resources
        val configuration = currentResources.configuration
        // 调试信息已隐藏
        debugContextInfo = buildString {
            // append("windowDisplay=")
            // append(currentWindowDisplay?.displayId ?: "unknown")
            // append(" | metrics=")
            // append(currentWindowMetrics.widthPixels)
            // append("x")
            // append(currentWindowMetrics.heightPixels)
            // append(" density=")
            // append(currentWindowMetrics.densityDpi)
            // append(" | orientation=")
            // append(configuration.orientation)
            // append(" | screenLayout=0x")
            // append(configuration.screenLayout.toString(16))
        }

        // 始终先关闭旧的副屏窗口，避免残留
        dismissSecondary()

        val editorPkg = try { editorInfo.packageName } catch (_: Throwable) { null }
        // Log.d(logTag, "debug: editorPkg=$editorPkg rawMetrics=${currentWindowMetrics.widthPixels}x${currentWindowMetrics.heightPixels}") // 调试标签已隐藏

        // 简化状态判断：基于当前窗口的原始显示尺寸推断显示状态
        val widthPx = currentWindowMetrics.widthPixels
        val heightPx = currentWindowMetrics.heightPixels
        val inferredState = when {
            widthPx >= 1920 -> EnvironmentSingleton.DisplayState.SecondaryFullscreen
            widthPx in 1081..1919 -> EnvironmentSingleton.DisplayState.SecondaryLandscape
            heightPx > 1600 -> EnvironmentSingleton.DisplayState.PrimaryPortrait
            else -> EnvironmentSingleton.DisplayState.SecondaryPortrait
        }
        EnvironmentSingleton.instance.currentDisplayState = inferredState
        EnvironmentSingleton.instance.forceFullOnSecondary = inferredState == EnvironmentSingleton.DisplayState.SecondaryFullscreen
        // Log.d(logTag, "inferredState=$inferredState from width=$widthPx height=$heightPx") // 调试标签已隐藏

        val shouldUsePresentation = inferredState == EnvironmentSingleton.DisplayState.SecondaryFullscreen
        val shownOnSecondary = if (shouldUsePresentation) {
            tryShowSecondary(editorInfo, restarting)
        } else {
            false
        }
        // Log.d(logTag, "shownOnSecondary=$shownOnSecondary") // 调试标签已隐藏

        // 设置输入视图（正常 IME 窗口）
        if (!shownOnSecondary) {
            if (!::mInputView.isInitialized) {
                mInputView = InputView(baseContext, this)
            }
            setInputView(mInputView)
            // Log.d(logTag, "setInputView normal with ${mInputView.javaClass.simpleName}") // 调试标签已隐藏
            mInputView.onStartInputView(editorInfo, restarting)
        }

        // 根据当前实际显示设备刷新环境尺寸与显示状态
        val targetMetrics = if (shownOnSecondary) {
            val dmSec2 = secondaryPresentation?.context?.resources?.displayMetrics
            // Log.d(logTag, "init with secondary metrics=${dmSec2?.widthPixels}x${dmSec2?.heightPixels}") // 调试标签已隐藏
            dmSec2 ?: currentWindowMetrics
        } else {
            currentWindowMetrics
        }
        EnvironmentSingleton.instance.initDataWithDisplayMetrics(targetMetrics)

        // 如果显示状态发生变化，执行一次“切到另一个键盘再切回”的快速重排
        applyRelayoutIfDisplayChanged()

        // 最后刷新调试标签，确保当前场景被正确显示
        // refreshDebugLabel() // 调试标签已隐藏
    }

    private fun applyRelayoutIfDisplayChanged() {
        val current = EnvironmentSingleton.instance.currentDisplayState
        if (lastDisplayState != current) {
            // Log.d(logTag, "display state changed: ${lastDisplayState} -> ${current}, force relayout by toggle") // 调试标签已隐藏
            try {
                KeyboardManager.instance.forceRelayoutByToggle()
            } catch (t: Throwable) {
                Log.e(logTag, "forceRelayoutByToggle failed", t)
            }
            lastDisplayState = current
        } else {
            // 同一状态下不重排，避免不必要闪烁
            // Log.d(logTag, "display state unchanged: ${current}") // 调试标签已隐藏
        }
    }

    override fun onStartInput(editorInfo: EditorInfo, restarting: Boolean) {
        super.onStartInput(editorInfo, restarting)
        val pkg = try { editorInfo.packageName } catch (_: Throwable) { null }
        // Log.d(logTag, "onStartInput: pkg=$pkg restarting=$restarting") // 调试标签已隐藏
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
        val ic = currentInputConnection
        val info = currentInputEditorInfo
        if (info == null || ic == null) {
            val t = SystemClock.uptimeMillis()
            sendDownKeyEvent(t, KeyEvent.KEYCODE_ENTER)
            sendUpKeyEvent(t, KeyEvent.KEYCODE_ENTER)
            return
        }
        if ((info.inputType and android.text.InputType.TYPE_MASK_CLASS) == android.text.InputType.TYPE_NULL
            || info.imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_ENTER_ACTION)) {
            val t = SystemClock.uptimeMillis()
            sendDownKeyEvent(t, KeyEvent.KEYCODE_ENTER)
            sendUpKeyEvent(t, KeyEvent.KEYCODE_ENTER)
        } else if (!info.actionLabel.isNullOrEmpty() && info.actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(info.actionId)
        } else {
            when (val action = info.imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_UNSPECIFIED, EditorInfo.IME_ACTION_NONE -> {
                    val t = SystemClock.uptimeMillis()
                    sendDownKeyEvent(t, KeyEvent.KEYCODE_ENTER)
                    sendUpKeyEvent(t, KeyEvent.KEYCODE_ENTER)
                }
                else -> ic.performEditorAction(action)
            }
        }
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
            // Log.d(logTag, "onComputeInsets: secondary active, primary unaffected") // 调试标签已隐藏
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
    
    /**
     * 初始化语音识别
     */
    private fun initializeVoiceRecognition() {
        try {
            // 在后台线程初始化语音识别
            GlobalScope.launch(Dispatchers.IO) {
                VoiceRecognitionManager.initialize()
            }
        } catch (e: Exception) {
            Log.e("ImeService", "Failed to initialize voice recognition", e)
        }
    }
    
    /**
     * 权限请求回调接口
     */
    interface PermissionCallback {
        fun onPermissionGranted()
        fun onPermissionDenied()
        fun onPermissionPermanentlyDenied()
    }
    
    private var currentPermissionCallback: PermissionCallback? = null
    
    /**
     * 请求语音识别权限
     * @param callback 权限请求结果回调
     */
    fun requestVoiceRecognitionPermissions(callback: PermissionCallback? = null) {
        currentPermissionCallback = callback
        
        try {
            // 标记权限已被请求过（用于区分首次请求和永久拒绝）
            com.yuyan.imemodule.permission.PermissionManager.markPermissionAsRequested(this)
            
            // 获取缺失的权限
            val missingPermissions = com.yuyan.imemodule.permission.PermissionManager.getMissingPermissions()
            
            if (missingPermissions.isNotEmpty()) {
                // 由于 InputMethodService 不是 Activity，我们需要通过特殊方式处理权限请求
                // 这里使用启动一个透明 Activity 的方式来处理权限请求
                try {
                    val intent = android.content.Intent().apply {
                        setClass(this@ImeService, Class.forName("com.yuyan.imemodule.ui.activity.PermissionRequestActivity"))
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("permissions", missingPermissions)
                    }
                    startActivity(intent)
                } catch (e: ClassNotFoundException) {
                    Log.e("ImeService", "PermissionRequestActivity not found", e)
                    callback?.onPermissionDenied()
                }
            } else {
                // 权限已授予
                callback?.onPermissionGranted()
            }
        } catch (e: Exception) {
            Log.e("ImeService", "Error requesting permissions", e)
            callback?.onPermissionDenied()
        }
    }
    
    /**
     * 处理权限请求结果（由 PermissionRequestActivity 调用）
     */
    fun handlePermissionResult(granted: Boolean) {
        val callback = currentPermissionCallback
        currentPermissionCallback = null
        
        if (granted) {
            callback?.onPermissionGranted()
        } else {
            // 判断是否是永久拒绝
            val status = com.yuyan.imemodule.permission.PermissionManager.getVoiceRecognitionPermissionStatus(this)
            if (status == com.yuyan.imemodule.permission.PermissionManager.PermissionStatus.PERMANENTLY_DENIED) {
                callback?.onPermissionPermanentlyDenied()
            } else {
                callback?.onPermissionDenied()
            }
        }
    }

    /**
     * 获取当前服务实例（供语音识别使用）
     */
    companion object {
        @Volatile
        private var currentInstance: ImeService? = null
        
        fun getCurrentInstance(): ImeService? = currentInstance
    }
    
    override fun onCreate() {
        super.onCreate()
        currentInstance = this
        
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager?.registerDisplayListener(displayListener, null)
        ThemeManager.addOnChangedListener(onThemeChangeListener)
        clipboardUpdateContent.registerOnChangeListener(clipboardUpdateContentListener)
        
        // 注册权限请求结果广播接收器
        registerReceiver(permissionResultReceiver, IntentFilter("com.yuyan.imemodule.PERMISSION_RESULT"))
        
        // 初始化语音识别
        initializeVoiceRecognition()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        currentInstance = null
        
        // 销毁语音识别
        try {
            VoiceRecognitionManager.destroy()
        } catch (e: Exception) {
            Log.e("ImeService", "Failed to destroy voice recognition", e)
        }
        
        // 取消显示监听器
        displayManager?.unregisterDisplayListener(displayListener)
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        clipboardUpdateContent.unregisterOnChangeListener(clipboardUpdateContentListener)
        
        // 注销权限请求结果广播接收器
        try {
            unregisterReceiver(permissionResultReceiver)
        } catch (e: Exception) {
            Log.e("ImeService", "Failed to unregister permission receiver", e)
        }
    }
}