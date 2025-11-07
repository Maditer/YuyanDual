package com.yuyan.imemodule.keyboard

import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.keyboard.container.*
import com.yuyan.imemodule.manager.InputModeSwitcherManager
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.utils.KeyboardLoaderUtil

class KeyboardManager {
    enum class KeyboardType {
        T9, QWERTY, LX17, QWERTYABC, NUMBER, SYMBOL, SETTINGS, HANDWRITING, CANDIDATES, ClipBoard, TEXTEDIT
    }
    private lateinit var mInputView: InputView
    private lateinit var mKeyboardRootView: InputViewParent
    private val keyboards = HashMap<KeyboardType, BaseContainer?>()
    private lateinit var mCurrentKeyboardName: KeyboardType
    var currentContainer: BaseContainer? = null
        private set

    // 兼容旧接口：设置根视图和输入视图
    fun setData(keyboardRootView: InputViewParent, inputView: InputView) {
        mKeyboardRootView = keyboardRootView
        mInputView = inputView
    }

    // 清空缓存键盘，并重建输入视图
    fun clearKeyboard() {
        keyboards.clear()
        if (::mInputView.isInitialized) mInputView.initView(mInputView.context)
    }

    fun init(inputView: InputView, keyboardRootView: InputViewParent) {
        mInputView = inputView
        mKeyboardRootView = keyboardRootView
    }

    fun switchKeyboard(layout: Int = InputModeSwitcherManager.skbLayout) {
        val keyboardName = when (layout) {
            0x1000 -> KeyboardType.QWERTY
            0x4000 -> KeyboardType.QWERTYABC
            0x3000 -> KeyboardType.HANDWRITING
            0x5000 -> KeyboardType.NUMBER
            0x6000 -> KeyboardType.LX17
            0x8000 -> KeyboardType.TEXTEDIT
            else -> KeyboardType.T9
        }
        switchKeyboard(keyboardName)
        if (::mInputView.isInitialized)mInputView.updateCandidateBar()
    }

    fun switchKeyboard(keyboardName: KeyboardType) {
        if (!::mKeyboardRootView.isInitialized) return
        var container = keyboards[keyboardName]
        if (container == null) {
            container = when (keyboardName) {
                KeyboardType.CANDIDATES ->  CandidatesContainer(Launcher.instance.context, mInputView)
                KeyboardType.HANDWRITING -> HandwritingContainer(Launcher.instance.context, mInputView)
                KeyboardType.NUMBER -> NumberContainer(Launcher.instance.context, mInputView)
                KeyboardType.QWERTY -> QwertyContainer(Launcher.instance.context, mInputView, InputModeSwitcherManager.MASK_SKB_LAYOUT_QWERTY_PINYIN)
                KeyboardType.SETTINGS -> SettingsContainer(Launcher.instance.context, mInputView)
                KeyboardType.SYMBOL -> SymbolContainer(Launcher.instance.context, mInputView)
                KeyboardType.QWERTYABC -> QwertyContainer(Launcher.instance.context, mInputView, InputModeSwitcherManager.MASK_SKB_LAYOUT_QWERTY_ABC)
                KeyboardType.LX17 -> T9TextContainer(Launcher.instance.context, mInputView, InputModeSwitcherManager.MASK_SKB_LAYOUT_LX17)
                KeyboardType.ClipBoard -> ClipBoardContainer(Launcher.instance.context, mInputView)
                KeyboardType.TEXTEDIT -> QwertyContainer(Launcher.instance.context, mInputView, InputModeSwitcherManager.MASK_SKB_LAYOUT_TEXTEDIT)
                else ->  T9TextContainer(Launcher.instance.context, mInputView, AppPrefs.getInstance().internal.inputDefaultMode.getValue() and InputModeSwitcherManager.MASK_SKB_LAYOUT)
            }
            container.updateSkbLayout()
            keyboards[keyboardName] = container
        }
        mKeyboardRootView.showView(container)
        // 键盘类型变化时，如果是输入容器，统一调用一次 updateSkbLayout 以刷新按键区域
        (container as? InputBaseContainer)?.updateSkbLayout()
        mCurrentKeyboardName = keyboardName
        currentContainer = container
    }

    // 快速重排：清缓存并按当前布局重建，再刷新候选栏
    fun forceRelayoutByToggle() {
        if (!::mKeyboardRootView.isInitialized) return
        try {
            KeyboardLoaderUtil.instance.clearKeyboardMap()
        } catch (_: Throwable) {}
        clearKeyboard()
        if (::mInputView.isInitialized) mInputView.resetToIdleState()
        switchKeyboard(InputModeSwitcherManager.skbLayout)
        if (::mInputView.isInitialized) mInputView.updateCandidateBar()
    }

    // 当前容器是否为输入键盘容器
    val isInputKeyboard: Boolean
        get() = currentContainer is InputBaseContainer

    companion object {
        private var mInstance: KeyboardManager? = null
        @JvmStatic
        val instance: KeyboardManager
            get() {
                if (null == mInstance) {
                    mInstance = KeyboardManager()
                }
                return mInstance!!
            }
    }
}