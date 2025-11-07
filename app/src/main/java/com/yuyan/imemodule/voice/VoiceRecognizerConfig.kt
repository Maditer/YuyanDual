package com.yuyan.imemodule.voice

/**
 * 语音识别配置类
 */
data class VoiceRecognizerConfig(
    val sampleRate: Float = 16000.0f,
    val featureDim: Int = 80,
    val modelDir: String = "sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13",
    val numThreads: Int = 1,
    val useGPU: Boolean = true,
    val enableEndpoint: Boolean = true,
    val rule1MinTrailingSilence: Float = 2.4f,  // 增加静音时间，避免过早结束
    val rule2MinTrailingSilence: Float = 1.0f,  // 增加静音时间
    val rule3MinUtteranceLength: Float = 30.0f, // 增加最小语句长度
    val decodingMethod: String = "modified_beam_search", // 使用更准确的解码方法
    val numActivePaths: Int = 4
)