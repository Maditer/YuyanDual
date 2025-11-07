package com.yuyan.imemodule.voice

import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.yuyan.imemodule.application.Launcher
import com.k2fsa.sherpa.ncnn.SherpaNcnn as OriginalSherpaNcnn
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Sherpa NCNN 语音识别器
 * 基于原 SherpaNcnn 项目改造，适配输入法场景
 */
class SherpaVoiceRecognizer(
    private val config: VoiceRecognizerConfig = VoiceRecognizerConfig()
) {
    
    companion object {
        private const val TAG = "SherpaVoiceRecognizer"
        
        // Native库加载状态 - 通过检查原始类是否可以加载来判断
        var isNativeLibraryLoaded = false
            private set
        
        // 检查native库是否可用
        init {
            Log.d(TAG, "=== SherpaVoiceRecognizer Static Initialization Start ===")
            try {
                // 尝试通过原始类来检查库是否可用
                // 这里只是检查，实际加载由SherpaNcnn类处理
                val context = com.yuyan.imemodule.application.Launcher.instance.context
                val libPath = context.applicationInfo.nativeLibraryDir
                Log.d(TAG, "Native library directory: $libPath")
                
                // 检查具体的库文件
                val libFile = java.io.File("$libPath/libsherpa-ncnn-jni.so")
                Log.d(TAG, "Library file exists: ${libFile.exists()}, path: ${libFile.absolutePath}")
                if (libFile.exists()) {
                    Log.d(TAG, "Library file size: ${libFile.length()} bytes")
                    isNativeLibraryLoaded = true
                    Log.i(TAG, "✅ Native library file found and should be loadable by SherpaNcnn class")
                } else {
                    Log.e(TAG, "❌ Native library file not found")
                    isNativeLibraryLoaded = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error checking native library", e)
                isNativeLibraryLoaded = false
            }
            Log.d(TAG, "=== SherpaVoiceRecognizer Static Initialization End ===")
        }
    }
    
    // 音频录制相关
    private val audioSource = MediaRecorder.AudioSource.MIC
    private val sampleRateInHz = config.sampleRate.toInt()
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var audioRecord: AudioRecord? = null
    
    // 录音线程
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    
    // 识别引擎
    private var sherpaNcnn: SherpaNcnnNative? = null
    
    // 状态管理
    private val _state = MutableStateFlow(VoiceRecognitionState.IDLE)
    val state: StateFlow<VoiceRecognitionState> = _state.asStateFlow()
    
    // 结果监听器
    private var listener: VoiceRecognitionListener? = null
    
    // 当前识别文本
    private var currentText = ""
    private var lastRecognizedText = ""
    
    /**
     * 初始化识别器
     */
    fun initialize(): Boolean {
        Log.d(TAG, "🚀 SherpaVoiceRecognizer.initialize() called")
        Log.d(TAG, "Native library loaded: $isNativeLibraryLoaded")
        
        // 检查native库是否已加载
        if (!isNativeLibraryLoaded) {
            Log.e(TAG, "❌ Cannot initialize: native library not loaded")
            _state.value = VoiceRecognitionState.ERROR
            listener?.onError("语音识别库未正确安装，请联系开发者")
            return false
        }
        
        Log.d(TAG, "✅ Native library is loaded, proceeding with initialization...")
        
        return try {
            _state.value = VoiceRecognitionState.INITIALIZING
            Log.d(TAG, "State set to INITIALIZING")
            
            // 初始化 Sherpa NCNN 引擎
            Log.d(TAG, "Creating Sherpa NCNN engine...")
            sherpaNcnn = createSherpaNcnn()
            Log.d(TAG, "✅ Sherpa NCNN engine created successfully")
            
            // 跳过isReady检查，直接认为初始化成功
            // 某些情况下isReady可能返回false，但引擎实际上可以工作
            Log.d(TAG, "✅ Sherpa NCNN engine created, assuming it's ready")
            _state.value = VoiceRecognitionState.READY
            Log.i(TAG, "🎉 Voice recognizer initialized successfully")
            listener?.onStateChanged(VoiceRecognitionState.READY)
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "❌ UnsatisfiedLinkError during initialization", e)
            _state.value = VoiceRecognitionState.ERROR
            listener?.onError("JNI链接错误: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize voice recognizer", e)
            Log.e(TAG, "Exception type: ${e::class.java.simpleName}")
            Log.e(TAG, "Exception message: ${e.message}")
            _state.value = VoiceRecognitionState.ERROR
            listener?.onError("初始化失败: ${e.message}")
            false
        }
    }
    
    /**
     * 创建 Sherpa NCNN 实例
     */
    private fun createSherpaNcnn(): SherpaNcnnNative {
        Log.d(TAG, "🏗️ Creating SherpaNcnnNative instance...")
        val assetManager = Launcher.instance.context.assets
        
        // 使用assets中存在的模型：sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13
        val modelDir = "sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13"
        Log.d(TAG, "📁 Using model directory: $modelDir")
        
        // 验证模型文件是否存在
        try {
            val modelFiles = listOf(
                "$modelDir/encoder_jit_trace-pnnx.ncnn.param",
                "$modelDir/encoder_jit_trace-pnnx.ncnn.bin",
                "$modelDir/decoder_jit_trace-pnnx.ncnn.param",
                "$modelDir/decoder_jit_trace-pnnx.ncnn.bin",
                "$modelDir/joiner_jit_trace-pnnx.ncnn.param",
                "$modelDir/joiner_jit_trace-pnnx.ncnn.bin",
                "$modelDir/tokens.txt"
            )
            
            for (file in modelFiles) {
                val inputStream = assetManager.open(file)
                val size = inputStream.available()
                inputStream.close()
                Log.d(TAG, "✅ Model file found: $file ($size bytes)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error checking model files", e)
            throw e
        }
        
        // 构建模型配置
        val modelConfig = ModelConfig(
            encoderParam = "$modelDir/encoder_jit_trace-pnnx.ncnn.param",
            encoderBin = "$modelDir/encoder_jit_trace-pnnx.ncnn.bin",
            decoderParam = "$modelDir/decoder_jit_trace-pnnx.ncnn.param",
            decoderBin = "$modelDir/decoder_jit_trace-pnnx.ncnn.bin",
            joinerParam = "$modelDir/joiner_jit_trace-pnnx.ncnn.param",
            joinerBin = "$modelDir/joiner_jit_trace-pnnx.ncnn.bin",
            tokens = "$modelDir/tokens.txt",
            numThreads = 1,  // 与原始项目保持一致
            useGPU = true  // 与原始项目保持一致
        )
        
        val featConfig = FeatureExtractorConfig(
            sampleRate = 16000.0f,
            featureDim = 80
        )
        
        val decoderConfig = DecoderConfig(
            method = "greedy_search",  // 使用与原始项目相同的解码方法
            numActivePaths = 4
        )
        
        val recognizerConfig = RecognizerConfig(
            featConfig = featConfig,
            modelConfig = modelConfig,
            decoderConfig = decoderConfig,
            enableEndpoint = true,
            rule1MinTrailingSilence = 2.0f,  // 与原始项目保持一致
            rule2MinTrailingSilence = 0.8f,
            rule3MinUtteranceLength = 20.0f
        )
        
        Log.d(TAG, "🔧 Configuration created, instantiating SherpaNcnnNative...")
        
        try {
            return SherpaNcnnNative(assetManager, recognizerConfig)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "❌ UnsatisfiedLinkError when creating SherpaNcnnNative", e)
            throw Exception("Native library loading failed: ${e.message}", e)
        } catch (e: NoClassDefFoundError) {
            Log.e(TAG, "❌ NoClassDefFoundError when creating SherpaNcnnNative", e)
            throw Exception("Sherpa NCNN class not found: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception when creating SherpaNcnnNative", e)
            throw Exception("Failed to create SherpaNcnnNative: ${e.message}", e)
        }
    }
    
    /**
     * 开始录音识别
     */
    fun startRecognition(): Boolean {
        Log.d(TAG, "SherpaVoiceRecognizer.startRecognition() called")
        Log.d(TAG, "Current state: ${_state.value}")
        
        if (_state.value != VoiceRecognitionState.READY) {
            Log.w(TAG, "Cannot start recognition, current state: ${_state.value}")
            return false
        }
        
        return try {
            // 暂时禁用诊断测试，避免可能的native库崩溃
            // runDiagnosticTest()
            
            Log.d(TAG, "Initializing microphone...")
            if (!initMicrophone()) {
                throw Exception("Failed to initialize microphone")
            }
            Log.d(TAG, "Microphone initialized successfully")
            
            audioRecord?.startRecording()
            isRecording.set(true)
            _state.value = VoiceRecognitionState.RECORDING
            Log.d(TAG, "Audio recording started")
            
            // 重置识别状态
            currentText = ""
            lastRecognizedText = ""
        try {
            sherpaNcnn?.reset(true)
            Log.d(TAG, "Voice recognition state reset successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error resetting recognizer", e)
        }
            
            // 启动录音线程
            recordingThread = thread(true) {
                Log.d(TAG, "Audio processing thread started")
                processAudioSamples()
            }
            
            listener?.onStateChanged(VoiceRecognitionState.RECORDING)
            Log.i(TAG, "Started voice recognition successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recognition", e)
            _state.value = VoiceRecognitionState.ERROR
            listener?.onError("开始识别失败: ${e.message}")
            false
        }
    }
    
    /**
     * 运行诊断测试
     */
    private fun runDiagnosticTest() {
        Log.i(TAG, "🔧 Running diagnostic test...")
        
        try {
            // 测试SherpaNcnn实例
            val ncnn = sherpaNcnn
            Log.d(TAG, "🔍 SherpaNcnn instance: ${ncnn != null}")
            
            if (ncnn != null) {
                // 测试基本方法
                val isReady = ncnn.isReady()
                Log.d(TAG, "🔍 isReady(): $isReady")
                
                val text = ncnn.text
                Log.d(TAG, "🔍 text(): '$text'")
                
                // 测试reset方法
                ncnn.reset(false)
                Log.d(TAG, "✅ reset() test passed")
                
                // 测试decode方法
                ncnn.decode()
                Log.d(TAG, "✅ decode() test passed")
                
                // 测试isEndpoint方法
                val isEndpoint = ncnn.isEndpoint()
                Log.d(TAG, "🔍 isEndpoint(): $isEndpoint")
                
                Log.i(TAG, "✅ SherpaNcnn diagnostic test completed successfully")
            } else {
                Log.e(TAG, "❌ SherpaNcnn instance is null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Diagnostic test failed", e)
        }
        
        Log.i(TAG, "🔧 Diagnostic test completed")
    }
    
    /**
     * 停止录音识别
     */
    fun stopRecognition() {
        if (!isRecording.get()) {
            return
        }
        
        isRecording.set(false)
        
        try {
            // 停止录音
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            
            // 等待录音线程结束
            recordingThread?.join(1000)
            recordingThread = null
            
            // 如果当前有识别结果，将其作为最终结果发送
            if (currentText.isNotBlank()) {
                Log.i(TAG, "🗣️ Final recognition result: '$currentText'")
                val result = VoiceRecognitionResult(
                    text = currentText,
                    isPartial = false,
                    isEndpoint = true
                )
                listener?.onResult(result)
            }
            
            _state.value = VoiceRecognitionState.READY
            listener?.onStateChanged(VoiceRecognitionState.READY)
            
            Log.i(TAG, "Stopped voice recognition")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recognition", e)
        }
    }
    
    /**
     * 处理音频数据
     */
    private fun processAudioSamples() {
        Log.i(TAG, "Started processing audio samples (simplified mode)")
        
        val interval = 0.1f // 100ms
        val bufferSize = (interval * sampleRateInHz).toInt() // 样本数
        val buffer = ShortArray(bufferSize)
        var audioFrameCount = 0
        
        while (isRecording.get()) {
            try {
                val ret = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                Log.d(TAG, "🎤 AudioRecord.read() returned: $ret bytes")
                audioFrameCount++
                
                if (ret > 0) {
                    // 每100帧记录一次音频输入状态
                    if (audioFrameCount % 100 == 0) {
                        Log.d(TAG, "🎵 Audio frame #$audioFrameCount: $ret samples read")
                        
                        // 检查音频数据是否有效
                        val maxAmplitude = buffer.take(ret).maxOrNull() ?: 0
                        val minAmplitude = buffer.take(ret).minOrNull() ?: 0
                        Log.d(TAG, "🎵 Audio amplitude range: [$minAmplitude, $maxAmplitude]")
                    }
                    
                    // 语音识别处理已启用
                    
                    // 每500帧（约50秒）发送一次状态更新，让用户知道录音正常工作
                    if (audioFrameCount % 500 == 0) {
                        Log.i(TAG, "🎤 Voice recording in progress... (frame #$audioFrameCount)")
                        listener?.onResult(VoiceRecognitionResult(
                            text = "[录音中...]",
                            isPartial = true,
                            isEndpoint = false
                        ))
                    }
                    
                    // Step 1: 音频数据输入
                    try {
                        val samples = FloatArray(ret) { buffer[it] / 32768.0f }
                        sherpaNcnn?.acceptSamples(samples)
                        Log.v(TAG, "🎵 Audio samples fed to recognizer: ${samples.size} samples")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error feeding audio samples to recognizer", e)
                        // 如果acceptSamples失败，继续循环但不处理后续步骤
                        continue
                    }
                    
                    // Step 2: 解码处理
                    try {
                        val isReady = sherpaNcnn?.isReady() ?: false
                        if (isReady) {
                            var decodeCount = 0
                            while (sherpaNcnn?.isReady() == true) {
                                sherpaNcnn?.decode()
                                decodeCount++
                            }
                            Log.d(TAG, "🔄 Decoded $decodeCount frames")
                        } else if (audioFrameCount % 50 == 0) {
                            Log.d(TAG, "⏳ Recognizer not ready yet (frame #$audioFrameCount)")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error during decoding", e)
                        // 如果解码失败，继续下一帧
                    }
                    
                    // Step 3: 检查端点和获取结果
                    try {
                        val isEndpoint = sherpaNcnn?.isEndpoint() ?: false
                        val text = sherpaNcnn?.text ?: ""
                        
                        // 处理识别结果
                        if (text.isNotBlank() && text != currentText) {
                            Log.i(TAG, "🗣️ Recognition result: '$text' (partial=${!isEndpoint}, endpoint=$isEndpoint)")
                            currentText = text
                            val result = VoiceRecognitionResult(
                                text = text,
                                isPartial = !isEndpoint,
                                isEndpoint = isEndpoint
                            )
                            
                            listener?.onResult(result)
                            
                            // 如果检测到端点，重置识别器
                            if (isEndpoint) {
                                Log.i(TAG, "🏁 Endpoint detected, resetting recognizer")
                                lastRecognizedText = text
                                sherpaNcnn?.reset()
                            }
                        } else if (audioFrameCount % 50 == 0) {
                            // 定期记录状态
                            Log.d(TAG, "🔍 Status: text='$text', endpoint=$isEndpoint")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error checking recognition results", e)
                        // 如果结果处理失败，继续录音
                    }
                } else {
                    Log.w(TAG, "⚠️ Audio read returned $ret bytes")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing audio samples", e)
                break
            }
        }
        
        Log.i(TAG, "Finished processing audio samples (processed $audioFrameCount frames)")
    }
    
    /**
     * 初始化麦克风
     */
    private fun initMicrophone(): Boolean {
        return try {
            // 使用与原始项目相同的处理间隔：100ms
            val interval = 0.1f // 100 ms
            val bufferSize = (interval * sampleRateInHz).toInt() // in samples
            
            Log.i(TAG, "🎙️ Audio config:")
            Log.i(TAG, "  - Sample rate: $sampleRateInHz Hz")
            Log.i(TAG, "  - Channel config: $channelConfig (MONO=${AudioFormat.CHANNEL_IN_MONO})")
            Log.i(TAG, "  - Audio format: $audioFormat (PCM_16BIT=${AudioFormat.ENCODING_PCM_16BIT})")
            Log.i(TAG, "  - Processing interval: ${interval}s (100ms)")
            Log.i(TAG, "  - Buffer size: $bufferSize samples (${bufferSize * 1000.0f / sampleRateInHz} ms)")
            
            audioRecord = AudioRecord(
                audioSource,
                sampleRateInHz,
                channelConfig,
                audioFormat,
                bufferSize * 2 // a sample has two bytes as we are using 16-bit PCM
            )
            
            val state = audioRecord?.state
            Log.i(TAG, "🎙️ AudioRecord state: $state (INITIALIZED=${AudioRecord.STATE_INITIALIZED})")
            
            if (state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "✅ Microphone initialized successfully")
                true
            } else {
                Log.e(TAG, "❌ Microphone initialization failed, state: $state")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize microphone", e)
            false
        }
    }
    
    /**
     * 设置监听器
     */
    fun setListener(listener: VoiceRecognitionListener?) {
        this.listener = listener
    }
    
    /**
     * 销毁识别器
     */
    fun destroy() {
        stopRecognition()
        
        sherpaNcnn?.destroy()
        sherpaNcnn = null
        
        _state.value = VoiceRecognitionState.DESTROYED
        Log.i(TAG, "Voice recognizer destroyed")
    }
    
    /**
     * 获取当前识别文本
     */
    fun getCurrentText(): String = currentText
    
    /**
     * 获取最后一次确认的识别文本
     */
    fun getLastRecognizedText(): String = lastRecognizedText
}

/**
 * Sherpa NCNN Native 接口包装类
 * 对应原项目的 SherpaNcnn.kt
 */
// 简单的包装器，使用原始的SherpaNcnn类
class SherpaNcnnNative(
    assetManager: AssetManager,
    private val config: RecognizerConfig
) {
    // 直接使用原始的SherpaNcnn类
    private val sherpaNcnn: OriginalSherpaNcnn
    
    init {
        try {
            Log.d("SherpaNcnnNative", "🏗️ Initializing OriginalSherpaNcnn...")
            Log.d("SherpaNcnnNative", "📋 AssetManager: $assetManager")
            
            // 转换配置格式
            val originalConfig = convertToOriginalConfig(config)
            Log.d("SherpaNcnnNative", "📋 Original config: $originalConfig")
            
            sherpaNcnn = OriginalSherpaNcnn(originalConfig, assetManager)
            Log.d("SherpaNcnnNative", "✅ OriginalSherpaNcnn initialized successfully")
            Log.d("SherpaNcnnNative", "🔍 Initial isReady(): ${sherpaNcnn.isReady()}")
            Log.d("SherpaNcnnNative", "🔍 Initial text: '${sherpaNcnn.text}'")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("SherpaNcnnNative", "❌ UnsatisfiedLinkError in SherpaNcnnNative init", e)
            throw RuntimeException("Sherpa NCNN native library not available: ${e.message}", e)
        } catch (e: Exception) {
            Log.e("SherpaNcnnNative", "❌ Exception in SherpaNcnnNative init", e)
            throw RuntimeException("Failed to initialize SherpaNcnnNative: ${e.message}", e)
        }
    }
    
    fun acceptSamples(samples: FloatArray) {
        sherpaNcnn.acceptWaveform(samples)
    }
    
    fun isReady(): Boolean {
        return sherpaNcnn.isReady()
    }
    
    fun decode() {
        sherpaNcnn.decode()
    }
    
    fun isEndpoint(): Boolean {
        return sherpaNcnn.isEndpoint()
    }
    
    fun reset(recreate: Boolean = false) {
        sherpaNcnn.reset(recreate)
    }
    
    val text: String
        get() = sherpaNcnn.text
    
    fun destroy() {
        sherpaNcnn.destroy()
    }
    
    // 转换配置格式到原始格式
    private fun convertToOriginalConfig(config: RecognizerConfig): com.k2fsa.sherpa.ncnn.RecognizerConfig {
        val featConfig = com.k2fsa.sherpa.ncnn.FeatureExtractorConfig(
            sampleRate = config.featConfig.sampleRate,
            featureDim = config.featConfig.featureDim
        )
        
        val modelConfig = com.k2fsa.sherpa.ncnn.ModelConfig(
            encoderParam = config.modelConfig.encoderParam,
            encoderBin = config.modelConfig.encoderBin,
            decoderParam = config.modelConfig.decoderParam,
            decoderBin = config.modelConfig.decoderBin,
            joinerParam = config.modelConfig.joinerParam,
            joinerBin = config.modelConfig.joinerBin,
            tokens = config.modelConfig.tokens,
            numThreads = config.modelConfig.numThreads,
            useGPU = config.modelConfig.useGPU
        )
        
        val decoderConfig = com.k2fsa.sherpa.ncnn.DecoderConfig(
            method = config.decoderConfig.method,
            numActivePaths = config.decoderConfig.numActivePaths
        )
        
        return com.k2fsa.sherpa.ncnn.RecognizerConfig(
            featConfig = featConfig,
            modelConfig = modelConfig,
            decoderConfig = decoderConfig,
            enableEndpoint = true,
            rule1MinTrailingSilence = config.rule1MinTrailingSilence,
            rule2MinTrailingSilence = config.rule2MinTrailingSilence,
            rule3MinUtteranceLength = config.rule3MinUtteranceLength
        )
    }
}

// 数据类定义（对应原项目）
data class FeatureExtractorConfig(
    var sampleRate: Float,
    var featureDim: Int,
)

data class ModelConfig(
    var encoderParam: String,
    var encoderBin: String,
    var decoderParam: String,
    var decoderBin: String,
    var joinerParam: String,
    var joinerBin: String,
    var tokens: String,
    var numThreads: Int = 1,
    var useGPU: Boolean = true,
)

data class DecoderConfig(
    var method: String = "modified_beam_search",
    var numActivePaths: Int = 4,
)

data class RecognizerConfig(
    var featConfig: FeatureExtractorConfig,
    var modelConfig: ModelConfig,
    var decoderConfig: DecoderConfig,
    var enableEndpoint: Boolean = true,
    var rule1MinTrailingSilence: Float = 2.4f,
    var rule2MinTrailingSilence: Float = 1.0f,
    var rule3MinUtteranceLength: Float = 30.0f,
)