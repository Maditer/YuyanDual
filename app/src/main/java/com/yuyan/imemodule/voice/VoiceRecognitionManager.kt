package com.yuyan.imemodule.voice

import android.util.Log
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.permission.PermissionManager
import com.yuyan.imemodule.service.ImeService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 语音识别管理器
 * 单例模式，负责管理语音识别的生命周期
 */
object VoiceRecognitionManager {
    
    private const val TAG = "VoiceRecognitionManager"
    
    // 语音识别器实例
    private var voiceRecognizer: SherpaVoiceRecognizer? = null
    
    // 流式识别相关
    private var lastCommittedText = ""  // 上次提交的文本
    private var isStreaming = false     // 是否正在进行流式识别
    
    // 状态管理
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()
    
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()
    
    // 异步作用域
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // 防止重复初始化
    private val isInitializing = AtomicBoolean(false)
    
    /**
     * 初始化语音识别
     */
    fun initialize(): Boolean {
        Log.d(TAG, "initialize() called - current state: initialized=${_isInitialized.value}, initializing=${isInitializing.get()}")
        
        if (_isInitialized.value) {
            Log.d(TAG, "Voice recognition already initialized")
            return true
        }
        
        if (isInitializing.get()) {
            Log.d(TAG, "Voice recognition is initializing...")
            return false
        }
        
        val hasPermissions = PermissionManager.hasVoiceRecognitionPermissions()
        Log.d(TAG, "Voice recognition permissions: $hasPermissions")
        if (!hasPermissions) {
            Log.e(TAG, "Missing voice recognition permissions")
            return false
        }
        
        return try {
            isInitializing.set(true)
            Log.d(TAG, "Starting initialization...")
            
            // 同步初始化，不使用协程
            Log.d(TAG, "Creating Sherpa voice recognizer...")
            val recognizer = SherpaVoiceRecognizer()
            
            // 设置识别结果监听器
            recognizer.setListener(object : VoiceRecognitionListener {
                override fun onResult(result: VoiceRecognitionResult) {
                    handleRecognitionResult(result)
                }
                
                override fun onStateChanged(state: VoiceRecognitionState) {
                    Log.d(TAG, "Voice recognition state changed: $state")
                }
                
                override fun onError(error: String) {
                    Log.e(TAG, "Voice recognition error: $error")
                    _isInitialized.value = false
                }
            })
            
            // 初始化识别器（同步）
            val initSuccess = recognizer.initialize()
            Log.d(TAG, "SherpaVoiceRecognizer.initialize() result: $initSuccess")
            
            if (initSuccess) {
                voiceRecognizer = recognizer
                _isInitialized.value = true
                Log.i(TAG, "Voice recognition initialized successfully")
                true
            } else {
                Log.e(TAG, "Failed to initialize voice recognizer")
                _isInitialized.value = false
                false
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "❌ UnsatisfiedLinkError during initialization", e)
            _isInitialized.value = false
            false
        } catch (e: NoClassDefFoundError) {
            Log.e(TAG, "❌ NoClassDefFoundError during initialization", e)
            _isInitialized.value = false
            false
        } catch (e: RuntimeException) {
            Log.e(TAG, "❌ RuntimeException during initialization", e)
            _isInitialized.value = false
            false
        } catch (e: Exception) {
            Log.e(TAG, "❌ General exception during initialization", e)
            _isInitialized.value = false
            false
        } finally {
            isInitializing.set(false)
        }
    }
    
    /**
     * 开始语音识别
     */
    fun startRecognition(): Boolean {
        Log.d(TAG, "startRecognition() called")
        
        // 检查用户设置
        val voiceEnabled = AppPrefs.getInstance().voice.voiceInputEnabled.getValue()
        Log.d(TAG, "Voice input enabled in settings: $voiceEnabled")
        if (!voiceEnabled) {
            Log.w(TAG, "Voice input is disabled in settings")
            return false
        }
        
        val recognizer = voiceRecognizer ?: run {
            Log.w(TAG, "Voice recognizer not initialized - voiceRecognizer is null")
            return false
        }
        
        if (_isRecording.value) {
            Log.d(TAG, "Already recording")
            return true
        }
        
        try {
            val success = (voiceRecognizer as SherpaVoiceRecognizer).startRecognition()
            if (success) {
                // 重置流式识别状态
                isStreaming = false
                lastCommittedText = ""
                
                _isRecording.value = true
                Log.i(TAG, "Started voice recognition successfully")
            } else {
                Log.w(TAG, "Failed to start voice recognition")
            }
            return success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recognition", e)
            _isRecording.value = false
            return false
        }
    }
    
    /**
     * 停止语音识别
     */
    fun stopRecognition() {
        if (!_isRecording.value) {
            return
        }
        
        try {
            voiceRecognizer?.stopRecognition()
            _isRecording.value = false
            Log.i(TAG, "Stopped voice recognition")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recognition", e)
        }
    }
    
    /**
     * 处理识别结果
     */
    private fun handleRecognitionResult(result: VoiceRecognitionResult) {
        Log.i(TAG, "📝 Received recognition result: '${result.text}' (partial=${result.isPartial}, endpoint=${result.isEndpoint})")
        
        // 直接在主线程处理，避免协程问题
        try {
            val service = ImeService.getCurrentInstance()
            Log.d(TAG, "🔍 ImeService instance: ${service != null}")
            
            if (service != null && result.text.isNotBlank()) {
                Log.d(TAG, "✅ Service and text are valid, processing result...")
                
                if (result.isPartial) {
                    // 流式识别：实时更新部分结果
                    Log.i(TAG, "🔄 Streaming partial result: '${result.text}'")
                    
                    // 获取输入连接
                    val inputConnection = service.currentInputConnection
                    if (inputConnection != null) {
                        try {
                            // 如果是第一次部分结果，先删除之前提交的文本
                            if (!isStreaming) {
                                isStreaming = true
                                // 记录当前光标位置前的文本，以便后续删除
                                lastCommittedText = inputConnection.getTextBeforeCursor(1000, 0)?.toString() ?: ""
                                Log.d(TAG, "📍 Started streaming, current text: '$lastCommittedText'")
                            }
                            
                            // 删除之前提交的部分结果（从上次记录的位置开始）
                            val textBeforeCursor = inputConnection.getTextBeforeCursor(1000, 0)?.toString() ?: ""
                            if (textBeforeCursor.length > lastCommittedText.length) {
                                val charsToDelete = textBeforeCursor.length - lastCommittedText.length
                                Log.d(TAG, "🗑️ Deleting $charsToDelete characters: '${textBeforeCursor.substring(lastCommittedText.length)}'")
                                for (i in 0 until charsToDelete) {
                                    inputConnection.deleteSurroundingText(1, 0)
                                }
                            }
                            
                            // 提交新的部分结果
                            val committed = inputConnection.commitText(result.text, 1)
                            Log.i(TAG, "✅ Streamed partial text: '${result.text}' (result: $committed)")
                            
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Error streaming partial text", e)
                        }
                    }
                    
                } else if (result.isEndpoint) {
                    // 最终结果：完成流式识别
                    Log.i(TAG, "🎯 Processing final result: '${result.text}'")
                    
                    if (isStreaming) {
                        // 如果之前在流式模式，需要更新到最终结果
                        val inputConnection = service.currentInputConnection
                        if (inputConnection != null) {
                            try {
                                // 删除当前的部分结果
                                val textBeforeCursor = inputConnection.getTextBeforeCursor(1000, 0)?.toString() ?: ""
                                if (textBeforeCursor.length > lastCommittedText.length) {
                                    val charsToDelete = textBeforeCursor.length - lastCommittedText.length
                                    Log.d(TAG, "🗑️ Final cleanup: deleting $charsToDelete characters")
                                    for (i in 0 until charsToDelete) {
                                        inputConnection.deleteSurroundingText(1, 0)
                                    }
                                }
                                
                                // 提交最终结果
                                val textToCommit = processRecognizedText(result.text)
                                val committed = inputConnection.commitText(textToCommit, 1)
                                Log.i(TAG, "✅ Final text committed: '$textToCommit' (result: $committed)")
                                
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ Error committing final text", e)
                            }
                        }
                        
                        // 重置流式状态
                        isStreaming = false
                        lastCommittedText = ""
                        
                    } else {
                        // 非流式模式，直接提交最终结果
                        val autoCommit = AppPrefs.getInstance().voice.voiceInputAutoCommit.getValue()
                        Log.d(TAG, "🔍 Auto commit setting: $autoCommit")
                        
                        if (autoCommit) {
                            val textToCommit = processRecognizedText(result.text)
                            Log.i(TAG, "📝 Text to commit: '$textToCommit'")
                            
                            val inputConnection = service.currentInputConnection
                            Log.d(TAG, "🔍 InputConnection: ${inputConnection != null}")
                            
                            if (inputConnection != null) {
                                try {
                                    val committed = inputConnection.commitText(textToCommit, 1)
                                    Log.i(TAG, "✅ Auto committed text: '$textToCommit' (result: $committed)")
                                } catch (e: Exception) {
                                    Log.e(TAG, "❌ Error committing text", e)
                                }
                            }
                        }
                    }
                }
            } else {
                Log.w(TAG, "⚠️ Cannot process result: service=${service != null}, text.isNotBlank=${result.text.isNotBlank()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling recognition result", e)
        }
    }
    
    /**
     * 处理识别到的文本
     * 可以在这里进行文本后处理，如添加标点、格式化等
     */
    private fun processRecognizedText(text: String): String {
        // 基本文本处理
        var processedText = text.trim()
        
        // 移除重复的空格
        processedText = processedText.replace(Regex("\\s+"), " ")
        
        // 可以添加更多处理逻辑，比如：
        // - 自动添加标点符号
        // - 大小写转换
        // - 特殊字符处理等
        
        return processedText
    }
    

    
    /**
     * 获取当前识别状态
     */
    fun getCurrentState(): VoiceRecognitionState {
        return voiceRecognizer?.let { 
            when {
                !_isInitialized.value -> VoiceRecognitionState.IDLE
                _isRecording.value -> VoiceRecognitionState.RECORDING
                else -> VoiceRecognitionState.READY
            }
        } ?: VoiceRecognitionState.IDLE
    }
    
    /**
     * 销毁语音识别
     */
    fun destroy() {
        scope.launch {
            try {
                voiceRecognizer?.destroy()
                voiceRecognizer = null
                
                _isInitialized.value = false
                _isRecording.value = false
                
                Log.i(TAG, "Voice recognition destroyed")
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying voice recognition", e)
            }
        }
        
        scope.cancel()
    }
    
    /**
     * 获取识别器实例（供外部调用）
     */
    fun getRecognizer(): SherpaVoiceRecognizer? = voiceRecognizer
}