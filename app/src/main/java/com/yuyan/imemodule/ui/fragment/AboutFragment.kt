package com.yuyan.imemodule.ui.fragment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceFragmentCompat
import com.yuyan.imemodule.BuildConfig
import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.utils.addPreference

class AboutFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(R.string.privacy_policy) {
                findNavController().navigate(R.id.action_aboutFragment_to_privacyPolicyFragment)
            }
            addPreference(R.string.source_code, R.string.github_repo) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CustomConstant.YUYAN_IME_REPO)))
            }
            addPreference(R.string.license, "GPL-3.0 license ") {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CustomConstant.LICENSE_URL)))
            }
            // 取消“应用版本”分组，改为直接展示；隐藏构建哈希与构建时间；版本项取消点击跳转
            addPreference(R.string.version, BuildConfig.versionName)
        }


    }
}


