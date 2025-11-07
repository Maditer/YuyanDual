package com.yuyan.imemodule.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.yuyan.imemodule.application.Launcher

/**
 * 权限管理器
 * 负责处理应用所需权限的检查和请求
 */
object PermissionManager {
    
    private const val REQUEST_RECORD_AUDIO = 1001
    
    /**
     * 检查是否有录音权限
     */
    fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            Launcher.instance.context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * 检查是否有音频设置权限
     */
    fun hasModifyAudioSettingsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            Launcher.instance.context,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * 检查语音识别所需的所有权限
     */
    fun hasVoiceRecognitionPermissions(): Boolean {
        val hasRecord = hasRecordAudioPermission()
        val hasModify = hasModifyAudioSettingsPermission()
        val result = hasRecord && hasModify
        Log.d("PermissionManager", "Voice permissions - RecordAudio: $hasRecord, ModifyAudioSettings: $hasModify, Overall: $result")
        return result
    }
    
    /**
     * 获取缺失的权限列表
     */
    fun getMissingPermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        
        if (!hasRecordAudioPermission()) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        
        if (!hasModifyAudioSettingsPermission()) {
            permissions.add(Manifest.permission.MODIFY_AUDIO_SETTINGS)
        }
        
        return permissions.toTypedArray()
    }
    
    /**
     * 请求语音识别权限
     * 需要在 Activity 中调用
     */
    fun requestVoiceRecognitionPermissions(activity: Activity) {
        val missingPermissions = getMissingPermissions()
        
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                missingPermissions,
                REQUEST_RECORD_AUDIO
            )
        }
    }
    
    /**
     * 检查权限请求结果
     */
    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        if (requestCode == REQUEST_RECORD_AUDIO) {
            return grantResults.isNotEmpty() && 
                   grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        }
        return false
    }
    
    /**
     * 显示权限说明对话框的提示文本
     */
    fun getPermissionRationale(): String {
        return "语音识别功能需要录音权限和音频设置权限。这些权限将用于：\n\n" +
               "• 录音权限：录制您的语音并转换为文字\n" +
               "• 音频设置权限：优化音频录制质量\n\n" +
               "请授权以使用语音输入功能。"
    }
    
    /**
     * 权限状态枚举
     */
    enum class PermissionStatus {
        FIRST_REQUEST,        // 首次请求（用户还没授权过）
        DENIED_BUT_CAN_ASK,  // 用户已拒绝过，但未点"不再询问"
        PERMANENTLY_DENIED,   // 用户已永久拒绝（点了"不再询问"）
        GRANTED              // 已授权
    }
    
    /**
     * 获取录音权限状态
     * @param activity 用于调用 shouldShowRequestPermissionRationale 的 Activity
     * @return 权限状态
     */
    fun getRecordAudioPermissionStatus(activity: Activity): PermissionStatus {
        return when {
            hasRecordAudioPermission() -> PermissionStatus.GRANTED
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO) -> PermissionStatus.DENIED_BUT_CAN_ASK
            else -> {
                // 这里需要判断是首次请求还是永久拒绝
                // 通过 SharedPreferences 记录用户是否曾经拒绝过权限
                val prefs = activity.getSharedPreferences("permission_prefs", Context.MODE_PRIVATE)
                val hasRequestedBefore = prefs.getBoolean("has_requested_record_audio_before", false)
                if (hasRequestedBefore) {
                    PermissionStatus.PERMANENTLY_DENIED
                } else {
                    PermissionStatus.FIRST_REQUEST
                }
            }
        }
    }
    
    /**
     * 获取语音识别权限状态（综合多个权限的状态）
     * @param activity 用于调用 shouldShowRequestPermissionRationale 的 Activity
     * @return 权限状态
     */
    fun getVoiceRecognitionPermissionStatus(activity: Activity): PermissionStatus {
        // 只要有一个核心权限（录音权限）需要处理，就以它的状态为准
        return getRecordAudioPermissionStatus(activity)
    }
    
    /**
     * 获取语音识别权限状态（使用 Context，适用于非 Activity 场景）
     * @param context Context
     * @return 权限状态
     */
    fun getVoiceRecognitionPermissionStatus(context: Context): PermissionStatus {
        return when {
            hasVoiceRecognitionPermissions() -> PermissionStatus.GRANTED
            else -> {
                // 在非 Activity 场景下，无法判断 shouldShowRequestPermissionRationale
                // 通过 SharedPreferences 记录来判断权限状态
                val prefs = context.getSharedPreferences("permission_prefs", Context.MODE_PRIVATE)
                val hasRequestedBefore = prefs.getBoolean("has_requested_record_audio_before", false)
                if (hasRequestedBefore) {
                    PermissionStatus.PERMANENTLY_DENIED
                } else {
                    PermissionStatus.FIRST_REQUEST
                }
            }
        }
    }
    
    /**
     * 标记权限已被请求过（用于区分首次请求和永久拒绝）
     * @param context Context
     */
    fun markPermissionAsRequested(context: Context) {
        val prefs = context.getSharedPreferences("permission_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("has_requested_record_audio_before", true).apply()
    }
}