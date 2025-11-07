package com.yuyan.imemodule.prefs.behavior

import com.yuyan.imemodule.view.preference.ManagedPreference

/**
 * 语音识别语言模式
 */
enum class VoiceLanguageMode {
    /**
     * 中英双语
     */
    BILINGUAL,
    
    /**
     * 仅中文
     */
    CHINESE_ONLY,
    
    /**
     * 仅英文
     */
    ENGLISH_ONLY;

    companion object : ManagedPreference.StringLikeCodec<VoiceLanguageMode> {
        override fun decode(raw: String): VoiceLanguageMode =
            VoiceLanguageMode.valueOf(raw)
    }
}