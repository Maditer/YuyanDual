package com.yuyan.imemodule.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.service.ImeService

object InputMethodUtil {

    @JvmField
    val serviceName: String = ImeService::class.java.name

    @JvmField
    val componentName: String =
        ComponentName(Launcher.instance.context, ImeService::class.java).flattenToShortString()

    fun isEnabled(): Boolean {
        return Launcher.instance.context.inputMethodManager.enabledInputMethodList.any {
            it.packageName == Launcher.instance.context.packageName && it.serviceName == serviceName
        }
    }

    fun isSelected(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Launcher.instance.context.inputMethodManager.currentInputMethodInfo?.let {
                it.packageName == Launcher.instance.context.packageName && it.serviceName == serviceName
            } ?: false
        } else {
            getSecureSettings(Settings.Secure.DEFAULT_INPUT_METHOD) == componentName
        }
    }

    fun startSettingsActivity(context: Context) =
        context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })

    // 优先使用传入的 Activity/Fragment 上下文，避免在某些设备上 application 上下文无法弹出选择器
    fun showPicker(context: Context) = context.inputMethodManager.showInputMethodPicker()

    // 兼容旧调用：回退使用应用上下文
    fun showPicker() = showPicker(Launcher.instance.context)
}