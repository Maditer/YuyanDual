package com.yuyan.imemodule.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.TextUtils
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.entity.keyboard.SoftKeyboard
import com.yuyan.imemodule.manager.InputModeSwitcherManager
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.KeyboardSymbolSlideUpMod
import com.yuyan.imemodule.prefs.behavior.PopupMenuMode
import com.yuyan.imemodule.service.DecodingInfo
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.utils.DevicesUtils
import com.yuyan.imemodule.view.popup.PopupComponent
import com.yuyan.imemodule.view.popup.PopupComponent.Companion.get
import com.yuyan.imemodule.voice.VoiceRecognitionManager
import com.yuyan.imemodule.service.ImeService
import android.util.Log
import java.util.LinkedList
import java.util.Queue
import kotlin.math.abs
import kotlin.math.absoluteValue

/**
 * 键盘根布局
 *
 * 由于之前键盘体验问题，当前基于Android内置键盘[android.inputmethodservice.KeyboardView]进行调整开发。
 */
open class BaseKeyboardView(mContext: Context?) : View(mContext) {
    private val popupComponent: PopupComponent = get()
    protected var mSoftKeyboard: SoftKeyboard? = null
    private var mCurrentKey: SoftKey? = null
    private var mGestureDetector: GestureDetector? = null
    private var mLongPressKey = false
    private var mAbortKey = false
    private var mHandler: Handler? = null
    protected var mDrawPending = false
    protected var mService: InputView? = null
    fun setResponseKeyEvent(service: InputView) {
        mService = service
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        initGestureDetector()
        if (mHandler == null) {
            mHandler = object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    when (msg.what) {
                        MSG_REPEAT -> {
                            Log.d(TAG, "MSG_REPEAT received")
                            if (repeatKey()) {
                                val repeat = Message.obtain(this, MSG_REPEAT)
                                sendMessageDelayed(repeat, REPEAT_INTERVAL)
                            }
                        }

                        MSG_LONGPRESS -> {
                            Log.d(TAG, "MSG_LONGPRESS received")
                            openPopupIfRequired()
                        }
                    }
                }
            }
        }
    }

    private fun initGestureDetector() {
        if (mGestureDetector == null) {
            mGestureDetector = GestureDetector(context, object : SimpleOnGestureListener() {
                override fun onScroll(downEvent: MotionEvent?, currentEvent: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                    if(mLongPressKey && mCurrentKey?.getkeyLabel()?.isNotBlank() == true){
                        popupComponent.changeFocus(currentEvent.x - downEvent!!.x, currentEvent.y - downEvent.y)
                    } else {
                        dispatchGestureEvent(downEvent, currentEvent, distanceX, distanceY)
                    }
                    return true
                }
                override fun onDown(e: MotionEvent): Boolean {
                    currentDistanceY = 0f
                    return super.onDown(e)
                }
            })
            mGestureDetector!!.setIsLongpressEnabled(false)
        }
    }

    fun invalidateKey() {
        mDrawPending = true
        invalidate()
    }

    open fun onBufferDraw() {}
    private fun openPopupIfRequired() {
        Log.d(TAG, "openPopupIfRequired() called")
        if(mCurrentKey != null) {
            val softKey = mCurrentKey!!
            Log.d(TAG, "Current key: code=${softKey.code}, label='${softKey.getkeyLabel()}'")
            val keyboardSymbol = ThemeManager.prefs.keyboardSymbol.getValue()
            
            // 优先检查空格键，确保语音识别总是被触发
            if (softKey.code == KeyEvent.KEYCODE_SPACE) {
                Log.d(TAG, "Space key long pressed - triggering voice recognition")
                // 空格键长按触发语音识别
                startVoiceRecognition()
                mLongPressKey = true
            } else if (softKey.getkeyLabel().isNotBlank() && softKey.code != InputModeSwitcherManager.USER_DEF_KEYCODE_EMOJI_8 ) {
                val keyLabel = if (InputModeSwitcherManager.isEnglishLower || (InputModeSwitcherManager.isEnglishUpperCase && !DecodingInfo.isCandidatesListEmpty))
                    softKey.keyLabel.lowercase()  else softKey.keyLabel
                val designPreset = setOf("，", "。", ",", ".")
                val smallLabel = if(designPreset.any { it == keyLabel } || !keyboardSymbol) "" else softKey.getmKeyLabelSmall()
                val bounds = Rect(softKey.mLeft, softKey.mTop, softKey.mRight, softKey.mBottom)
                popupComponent.showKeyboard(keyLabel, smallLabel, bounds)
                mLongPressKey = true
            } else if (softKey.code == InputModeSwitcherManager.USER_DEF_KEYCODE_LANG_2 ||
                softKey.code == InputModeSwitcherManager.USER_DEF_KEYCODE_EMOJI_8 ||
                    softKey.code == InputModeSwitcherManager.USER_DEF_KEYCODE_SHIFT_1 ||
                softKey.code == InputModeSwitcherManager.USER_DEF_KEYCODE_CURSOR_DIRECTION_9 ||
                softKey.code == KeyEvent.KEYCODE_DEL || softKey.code == KeyEvent.KEYCODE_ENTER){
                val bounds = Rect(softKey.mLeft, softKey.mTop, softKey.mRight, softKey.mBottom)
                popupComponent.showKeyboardMenu(softKey, bounds, currentDistanceY)
                mLongPressKey = true
            } else {
                Log.d(TAG, "Other key long pressed - aborting")
                mLongPressKey = true
                mAbortKey = true
                dismissPreview()
            }
        } else {
            Log.w(TAG, "mCurrentKey is null in openPopupIfRequired")
        }
    }

    private var motionEventQueue: Queue<MotionEvent> = LinkedList()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(me: MotionEvent): Boolean {
        var result = false
        if (mGestureDetector!!.onTouchEvent(me)) {
            return true
        }
        when (val action = me.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val actionIndex = me.actionIndex
                val x = me.getX(actionIndex)
                val y = me.getY(actionIndex)
                val now = me.eventTime
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, me.metaState)
                motionEventQueue.offer(down)
                result = onModifiedTouchEvent(me)
                val keyIndex = getKeyIndices(x.toInt(), y.toInt())
                if(keyIndex != null) {
                    DevicesUtils.tryPlayKeyDown(keyIndex.code)
                    DevicesUtils.tryVibrate(this)
                }
                showPreview(keyIndex)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val now = me.eventTime
                val act = if(action == MotionEvent.ACTION_CANCEL)MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP
                while (!motionEventQueue.isEmpty()) {
                    val first = motionEventQueue.poll()
                    if(first!= null) {
                        result = onModifiedTouchEvent(MotionEvent.obtain(now, now, act, first.x, first.y, me.metaState))
                    }
                }
                dismissPreview()
            }
            else -> {
                result = onModifiedTouchEvent(me)
            }
        }
        return result
    }

    private fun onModifiedTouchEvent(me: MotionEvent): Boolean {
        mCurrentKey = getKeyIndices(me.x.toInt(), me.y.toInt())
        when (me.action) {
            MotionEvent.ACTION_DOWN -> {
                mAbortKey = false
                mLongPressKey = false
                if(mCurrentKey != null){
                    Log.d(TAG, "ACTION_DOWN - key: ${mCurrentKey!!.getkeyLabel()}, code: ${mCurrentKey!!.code}")
                    if (mCurrentKey!!.repeatable()) {
                        val msg = mHandler!!.obtainMessage(MSG_REPEAT)
                        mHandler!!.sendMessageDelayed(msg, REPEAT_START_DELAY)
                    }
                    val timeout = AppPrefs.getInstance().keyboardSetting.longPressTimeout.getValue().toLong()
                    Log.d(TAG, "Scheduling long press with timeout: ${timeout}ms")
                    val msg = mHandler!!.obtainMessage(MSG_LONGPRESS)
                    mHandler!!.sendMessageDelayed(msg, timeout)
                } else {
                    Log.d(TAG, "ACTION_DOWN - no key detected")
                }
            }
            MotionEvent.ACTION_UP -> {
                Log.d(TAG, "ACTION_UP - mAbortKey: $mAbortKey, mLongPressKey: $mLongPressKey")
                removeMessages()
                if (!mAbortKey && !mLongPressKey && mCurrentKey != null) {
                    Log.d(TAG, "Triggering normal key press: ${mCurrentKey!!.getkeyLabel()}")
                    mService?.responseKeyEvent(mCurrentKey!!)
                } else {
                    Log.d(TAG, "Not triggering key press - abort: $mAbortKey, longPress: $mLongPressKey")
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeMessages()
            }
        }
        return true
    }

    private var lastEventX:Float = -1f
    private var lastEventY:Float = -1f
    private var currentDistanceY:Float = 0f
    private var currentDistanceX:Float = 0f
    private var lastEventActionIndex:Int = 0
    // 处理手势滑动
    private fun dispatchGestureEvent(downEvent: MotionEvent?, currentEvent: MotionEvent, distanceX: Float, distanceY: Float) : Boolean {
        var result = false
        val currentX = currentEvent.x
        val currentY = currentEvent.y
        currentDistanceX = distanceX
        currentDistanceY = distanceY
        val keyLableSmall = mCurrentKey?.getmKeyLabelSmall()
        if(currentEvent.pointerCount > 1) return false    // 避免多指触控导致上屏
        if(lastEventX < 0 || lastEventActionIndex != currentEvent.actionIndex) {   // 避免多指触控导致符号上屏
            lastEventX = currentX
            lastEventY = currentY
            lastEventActionIndex = currentEvent.actionIndex
            return false
        }
        val relDiffX = abs(currentX - lastEventX)
        val relDiffY = abs(currentY - lastEventY)
        val isVertical = relDiffX * 1.5 < relDiffY  //横向、竖向滑动距离接近时，优先触发左右滑动
        val symbolSlideUp = EnvironmentSingleton.instance.heightForCandidatesArea / when(ThemeManager.prefs.symbolSlideUpMod.getValue()){
            KeyboardSymbolSlideUpMod.SHORT -> 3;KeyboardSymbolSlideUpMod.MEDIUM -> 2;else -> 1
        }
        val spaceSwipeMoveCursorSpeed = AppPrefs.getInstance().keyboardSetting.spaceSwipeMoveCursorSpeed.getValue()
        if (!isVertical && relDiffX > spaceSwipeMoveCursorSpeed) {  // 左右滑动
            val isSwipeKey = mCurrentKey?.code == KeyEvent.KEYCODE_SPACE || mCurrentKey?.code == KeyEvent.KEYCODE_0
            if(mCurrentKey?.code == KeyEvent.KEYCODE_DEL && distanceX > 20){// 左滑删除
                removeMessages()
                mAbortKey = true
                mService?.responseKeyEvent(SoftKey(KeyEvent.KEYCODE_CLEAR))
            } else if (isSwipeKey && AppPrefs.getInstance().keyboardSetting.spaceSwipeMoveCursor.getValue()) {  // 左右滑动
                removeMessages()
                lastEventX = currentX
                lastEventY = currentY
                mAbortKey = true
                mService!!.responseKeyEvent(SoftKey(code = if (distanceX > 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT))
                result = true
            }
        } else if(keyLableSmall?.isNotBlank() == true){
            if (isVertical && distanceY > 0 && relDiffY > symbolSlideUp && ThemeManager.prefs.keyboardSymbol.getValue()){   // 向上滑动
                lastEventX = currentX
                lastEventY = currentY
                lastEventActionIndex = currentEvent.actionIndex
                mLongPressKey = true
                removeMessages()
                mService?.responseLongKeyEvent(Pair(PopupMenuMode.Text, keyLableSmall))
                result = true
            }
        } else {  // 菜单
            if (isVertical && relDiffY > symbolSlideUp * 2) {   // 向上滑动
                lastEventX = currentX
                lastEventY = currentY
                lastEventActionIndex = currentEvent.actionIndex
                mLongPressKey = true
                popupComponent.onGestureEvent(distanceY)
            } else {
                if(downEvent != null) popupComponent.changeFocus(currentEvent.x - downEvent.x, currentEvent.y - downEvent.y)
            }
        }
        return result
    }

    private fun repeatKey(): Boolean {
        if (mCurrentKey != null && mCurrentKey!!.repeatable()) {
            mService?.responseKeyEvent(
                if(mCurrentKey!!.code == InputModeSwitcherManager.USER_DEF_KEYCODE_CURSOR_DIRECTION_9){
                    SoftKey(if(currentDistanceX.absoluteValue >= currentDistanceY.absoluteValue){
                        if(currentDistanceX > 0)  KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                    } else{
                        if(currentDistanceY < 0)  KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                    })
                } else mCurrentKey!!)
        }
        return true
    }

    private fun removeMessages() {
        if (mHandler != null) {
            mHandler!!.removeMessages(MSG_REPEAT)
            mHandler!!.removeMessages(MSG_LONGPRESS)
            mHandler!!.removeMessages(MSG_SHOW_PREVIEW)
        }
    }

    public override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        closing()
    }

    /**
     * 显示短按气泡
     */
    private fun showPreview(key: SoftKey?) {
        mCurrentKey?.onReleased()
        if (key != null) {
            key.onPressed()
            showBalloonText(key)
        } else {
            popupComponent.dismissPopup()
        }
        invalidateKey()
    }

    /**
     * 启动语音识别
     */
    private fun startVoiceRecognition() {
        Log.d(TAG, "startVoiceRecognition() called")
        try {
            // 检查用户设置
            val voiceEnabled = AppPrefs.getInstance().voice.voiceInputEnabled.getValue()
            Log.d(TAG, "Voice input enabled: $voiceEnabled")
            if (!voiceEnabled) {
                Log.w(TAG, "Voice input is disabled in settings")
                val bounds = Rect(mCurrentKey!!.mLeft, mCurrentKey!!.mTop, mCurrentKey!!.mRight, mCurrentKey!!.mBottom)
                popupComponent.showPopup("语音输入已禁用", bounds)
                return
            }
            
            // 检查权限
            val hasPermissions = com.yuyan.imemodule.permission.PermissionManager.hasVoiceRecognitionPermissions()
            Log.d(TAG, "Voice recognition permissions: $hasPermissions")
            if (!hasPermissions) {
                Log.w(TAG, "Missing voice recognition permissions")
                // 尝试动态请求权限
                requestVoicePermissions()
                return
            }
            
            // 初始化语音识别（如果尚未初始化）
            val isInit = VoiceRecognitionManager.isInitialized.value
            Log.d(TAG, "VoiceRecognitionManager initialized: $isInit")
            if (!isInit) {
                Log.d(TAG, "Initializing VoiceRecognitionManager...")
                try {
                    val initResult = VoiceRecognitionManager.initialize()
                    Log.d(TAG, "VoiceRecognitionManager.initialize() result: $initResult")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Exception during VoiceRecognitionManager.initialize()", e)
                    // 初始化失败，显示错误但不影响键盘
                    val bounds = Rect(mCurrentKey!!.mLeft, mCurrentKey!!.mTop, mCurrentKey!!.mRight, mCurrentKey!!.mBottom)
                    popupComponent.showPopup("语音识别初始化失败", bounds)
                    return
                }
            }
            
            // 开始语音识别
            val isInitAfter = VoiceRecognitionManager.isInitialized.value
            Log.d(TAG, "VoiceRecognitionManager initialized after init: $isInitAfter")
            if (isInitAfter) {
                Log.d(TAG, "Starting voice recognition...")
                try {
                    val success = VoiceRecognitionManager.startRecognition()
                    Log.d(TAG, "VoiceRecognitionManager.startRecognition() result: $success")
                    if (success) {
                        // 显示语音识别提示
                        val bounds = Rect(mCurrentKey!!.mLeft, mCurrentKey!!.mTop, mCurrentKey!!.mRight, mCurrentKey!!.mBottom)
                        popupComponent.showPopup("🎤 语音识别中...", bounds)
                    } else {
                        Log.e(TAG, "Failed to start voice recognition")
                        val bounds = Rect(mCurrentKey!!.mLeft, mCurrentKey!!.mTop, mCurrentKey!!.mRight, mCurrentKey!!.mBottom)
                        popupComponent.showPopup("语音识别启动失败", bounds)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Exception during VoiceRecognitionManager.startRecognition()", e)
                    // 启动失败，显示错误但不影响键盘
                    val bounds = Rect(mCurrentKey!!.mLeft, mCurrentKey!!.mTop, mCurrentKey!!.mRight, mCurrentKey!!.mBottom)
                    popupComponent.showPopup("语音识别启动失败", bounds)
                }
            } else {
                Log.e(TAG, "VoiceRecognitionManager not initialized after init call")
                
                // 检查是否是native库缺失问题
                if (!com.yuyan.imemodule.voice.SherpaVoiceRecognizer.isNativeLibraryLoaded) {
                    // 显示更详细的错误信息和解决方案
                    showNativeLibraryMissingPopup()
                } else {
                    // 显示通用初始化失败信息
                    val bounds = Rect(mCurrentKey!!.mLeft, mCurrentKey!!.mTop, mCurrentKey!!.mRight, mCurrentKey!!.mBottom)
                    popupComponent.showPopup("语音识别初始化失败", bounds)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Unexpected exception in startVoiceRecognition", e)
            // 捕获所有异常，确保不会导致键盘消失
            val bounds = Rect(mCurrentKey!!.mLeft, mCurrentKey!!.mTop, mCurrentKey!!.mRight, mCurrentKey!!.mBottom)
            popupComponent.showPopup("语音识别异常", bounds)
        }
    }

    /**
     * 停止语音识别
     */
    private fun stopVoiceRecognition() {
        try {
            VoiceRecognitionManager.stopRecognition()
        } catch (e: Exception) {
            // 忽略停止时的异常
        }
    }

    /**
     * 请求语音权限
     * 根据权限状态采用不同的策略
     */
    private fun requestVoicePermissions() {
        try {
            Log.d(TAG, "Requesting voice permissions with optimized flow...")
            
            val service = ImeService.getCurrentInstance()
            if (service == null) {
                Log.e(TAG, "ImeService instance is null")
                showPermissionGuidePopup()
                return
            }
            
            // 获取权限状态
            val permissionStatus = com.yuyan.imemodule.permission.PermissionManager.getVoiceRecognitionPermissionStatus(service)
            Log.d(TAG, "Permission status: $permissionStatus")
            
            when (permissionStatus) {
                com.yuyan.imemodule.permission.PermissionManager.PermissionStatus.FIRST_REQUEST -> {
                    // 状态一：首次请求（用户还没授权过）
                    // 策略：直接调用 requestPermissions() 弹出系统授权弹窗
                    Log.d(TAG, "First time requesting permissions - showing system dialog")
                    requestPermissionsDirectly(service)
                }
                
                com.yuyan.imemodule.permission.PermissionManager.PermissionStatus.DENIED_BUT_CAN_ASK -> {
                    // 状态二：用户已拒绝过（但未点"不再询问"）
                    // 策略：再次调用 requestPermissions()，直接弹出授权弹窗
                    Log.d(TAG, "Permission denied before but can ask again - showing system dialog")
                    requestPermissionsDirectly(service)
                }
                
                com.yuyan.imemodule.permission.PermissionManager.PermissionStatus.PERMANENTLY_DENIED -> {
                    // 状态三：用户已永久拒绝（点了"不再询问"）
                    // 策略：显示按钮，让用户跳转到设置页面
                    Log.d(TAG, "Permission permanently denied - showing settings guide")
                    showPermissionGuidePopup()
                }
                
                com.yuyan.imemodule.permission.PermissionManager.PermissionStatus.GRANTED -> {
                    // 权限已授予，这种情况不应该发生，因为我们已经检查过了
                    Log.d(TAG, "Permission already granted - this shouldn't happen")
                    // 重新启动语音识别
                    startVoiceRecognition()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting permissions", e)
            showPermissionGuidePopup()
        }
    }
    
    /**
     * 直接请求权限（使用系统弹窗）
     */
    private fun requestPermissionsDirectly(service: ImeService) {
        service.requestVoiceRecognitionPermissions(object : ImeService.PermissionCallback {
            override fun onPermissionGranted() {
                Log.d(TAG, "Permission granted - starting voice recognition")
                // 权限被授予，重新启动语音识别
                post {
                    startVoiceRecognition()
                }
            }
            
            override fun onPermissionDenied() {
                Log.d(TAG, "Permission denied")
                // 权限被拒绝，显示提示
                showPermissionDeniedPopup()
            }
            
            override fun onPermissionPermanentlyDenied() {
                Log.d(TAG, "Permission permanently denied")
                // 权限被永久拒绝，显示设置引导
                showPermissionGuidePopup()
            }
        })
    }
    
    /**
     * 显示权限被拒绝的提示
     */
    private fun showPermissionDeniedPopup() {
        val bounds = Rect(mCurrentKey!!.mLeft, mCurrentKey!!.mTop, mCurrentKey!!.mRight, mCurrentKey!!.mBottom)
        popupComponent.showPopup("🚫 需要录音权限才能使用语音输入", bounds)
    }

    /**
     * 显示权限引导弹窗
     */
    private fun showPermissionGuidePopup() {
        val bounds = Rect(mCurrentKey!!.mLeft, mCurrentKey!!.mTop, mCurrentKey!!.mRight, mCurrentKey!!.mBottom)
        
        // 显示交互式权限请求弹窗
        popupComponent.showPermissionRequestPopup(
            title = "🎤 需要录音权限",
            buttonText = "去设置开启",
            bounds = bounds,
            onButtonClick = {
                openAppPermissionSettings()
            }
        )
    }
    
    /**
     * 显示Native库缺失弹窗
     */
    private fun showNativeLibraryMissingPopup() {
        val bounds = Rect(mCurrentKey!!.mLeft, mCurrentKey!!.mTop, mCurrentKey!!.mRight, mCurrentKey!!.mBottom)
        
        // 显示详细的错误信息弹窗
        popupComponent.showPermissionRequestPopup(
            title = "🎤 语音识别库未安装",
            buttonText = "了解详情",
            bounds = bounds,
            onButtonClick = {
                // 可以打开一个帮助页面或GitHub链接
                // 暂时显示一个更详细的提示
                val detailedBounds = Rect(mCurrentKey!!.mLeft, mCurrentKey!!.mTop, mCurrentKey!!.mRight, mCurrentKey!!.mBottom)
                popupComponent.showPopup(
                    "语音识别功能需要额外的库文件支持。\n" +
                    "请确保包含以下文件：\n" +
                    "• libsherpa-ncnn-jni.so\n" +
                    "• 相关模型文件\n\n" +
                    "联系开发者获取完整版本。",
                    detailedBounds
                )
            }
        )
    }

    /**
     * 打开应用权限设置页面
     */
    private fun openAppPermissionSettings() {
        try {
            val context = context
            val intent = android.content.Intent().apply {
                action = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                data = android.net.Uri.fromParts("package", context.packageName, null)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "Opening app permission settings")
        } catch (e: Exception) {
            Log.e(TAG, "Error opening permission settings", e)
            // 如果无法打开设置页面，显示通用提示
            val bounds = Rect(mCurrentKey!!.mLeft, mCurrentKey!!.mTop, mCurrentKey!!.mRight, mCurrentKey!!.mBottom)
            popupComponent.showPopup("请手动到设置中开启录音权限", bounds)
        }
    }

    /**
     * 隐藏短按气泡
     */
    private fun dismissPreview() {
        // 如果正在进行语音识别，则停止它
        if (VoiceRecognitionManager.isRecording.value) {
            stopVoiceRecognition()
            // 注意：不要设置 mLongPressKey = true，这会干扰正常的触摸事件处理
            // 只需要停止语音识别和隐藏弹窗即可
            popupComponent.dismissPopup()
            return
        }
        
        if (mLongPressKey) {
            mService?.responseLongKeyEvent(popupComponent.triggerFocused())
            mLongPressKey = false
        }
        if (mCurrentKey != null) {
            mCurrentKey!!.onReleased()
            if(mService == null) return
            invalidateKey()
        }
        popupComponent.dismissPopup()
        lastEventX = -1f
    }

    open fun closing() {
        removeMessages()
    }

    private fun showBalloonText(key: SoftKey) {
        val keyboardBalloonShow = AppPrefs.getInstance().keyboardSetting.keyboardBalloonShow.getValue()
        if (keyboardBalloonShow && !TextUtils.isEmpty(key.getkeyLabel())) {
            val bounds = Rect(key.mLeft, key.mTop, key.mRight, key.mBottom)
            popupComponent.showPopup(key.getkeyLabel(), bounds)
        }
    }

    fun getKeyIndices(x: Int, y: Int): SoftKey? {
        return mSoftKeyboard?.mapToKey(x, y)
    }

    open fun setSoftKeyboard(softSkb: SoftKeyboard) {
        mSoftKeyboard = softSkb
    }

    fun getSoftKeyboard(): SoftKeyboard {
        return mSoftKeyboard!!
    }

    companion object {
        private const val TAG = "BaseKeyboardView"
        private const val MSG_SHOW_PREVIEW = 1
        private const val MSG_REPEAT = 3
        private const val MSG_LONGPRESS = 4
        private const val REPEAT_INTERVAL = 50L // ~20 keys per second
        private const val REPEAT_START_DELAY = 400L
    }
}
