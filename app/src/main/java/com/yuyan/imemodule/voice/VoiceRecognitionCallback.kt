package com.yuyan.imemodule.voice

/**
 * 语音识别状态枚举
 */
enum class VoiceRecognitionState {
    IDLE,           // 空闲状态
    INITIALIZING,   // 初始化中
    READY,          // 准备就绪
    RECORDING,      // 正在录音
    PROCESSING,     // 处理中
    ERROR,          // 错误状态
    DESTROYED       // 已销毁
}

/**
 * 语音识别结果
 */
data class VoiceRecognitionResult(
    val text: String,           // 识别的文本
    val isPartial: Boolean = false,  // 是否为部分结果
    val isEndpoint: Boolean = false, // 是否为完整结果
    val confidence: Float = 0.0f     // 置信度
)

/**
 * 语音识别监听器
 */
interface VoiceRecognitionListener {
    /**
     * 状态变化回调
     */
    fun onStateChanged(state: VoiceRecognitionState) {}
    
    /**
     * 识别结果回调
     */
    fun onResult(result: VoiceRecognitionResult) {}
    
    /**
     * 错误回调
     */
    fun onError(error: String) {}
    
    /**
     * 音量变化回调
     */
    fun onVolumeChanged(volume: Int) {}
}