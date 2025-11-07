package com.yuyan.imemodule.ui.activity

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.yuyan.imemodule.permission.PermissionManager

/**
 * 权限请求 Activity
 * 用于处理输入法服务中的权限请求
 */
class PermissionRequestActivity : Activity() {
    
    companion object {
        private const val TAG = "PermissionRequestActivity"
        const val EXTRA_PERMISSIONS = "permissions"
        private const val PERMISSION_REQUEST_CODE = 1001
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 设置为透明主题，避免显示界面
        setTheme(android.R.style.Theme_Translucent_NoTitleBar)
        
        try {
            // 获取要请求的权限
            val permissions = intent.getStringArrayExtra(EXTRA_PERMISSIONS) ?: emptyArray()
            
            if (permissions.isNotEmpty()) {
                Log.d(TAG, "Requesting permissions: ${permissions.contentToString()}")
                
                // 直接请求权限
                requestPermissions(permissions, PERMISSION_REQUEST_CODE)
            } else {
                Log.w(TAG, "No permissions to request")
                returnResultToService(true)
                finish()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting permissions", e)
            returnResultToService(false)
            finish()
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.isNotEmpty() && 
                           grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
            Log.d(TAG, "Permission request result: $permissions, allGranted: $allGranted")
            
            // 返回结果给 ImeService
            returnResultToService(allGranted)
            
            // 关闭 Activity
            finish()
        }
    }
    
    private fun returnResultToService(granted: Boolean) {
        try {
            // 通过广播或其他方式通知 ImeService
            val intent = Intent("com.yuyan.imemodule.PERMISSION_RESULT").apply {
                putExtra("granted", granted)
            }
            sendBroadcast(intent)
            
            // 也可以直接调用 ImeService 的静态方法（如果有的话）
            // ImeService.handlePermissionResult(granted)
            
            Log.d(TAG, "Permission result sent: granted=$granted")
        } catch (e: Exception) {
            Log.e(TAG, "Error returning permission result", e)
        }
    }
}