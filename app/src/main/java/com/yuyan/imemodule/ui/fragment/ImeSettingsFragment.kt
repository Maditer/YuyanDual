package com.yuyan.imemodule.ui.fragment

import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.content.res.ColorStateList
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.core.graphics.drawable.DrawableCompat
import androidx.appcompat.content.res.AppCompatResources
import com.yuyan.imemodule.R
import com.yuyan.imemodule.prefs.AppPrefs

class ImeSettingsFragment : PreferenceFragmentCompat() {

    private lateinit var screen: PreferenceScreen

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        screen = preferenceManager.createPreferenceScreen(requireContext())
        preferenceScreen = screen

        // 强制将双屏强制全屏开关置为开启
        AppPrefs.getInstance().dualScreen.dualForceFullscreenPrimary.setValue(true)

        addCategory(R.string.input_methods) {
            addDestinationPreference(R.string.setting_ime_input, R.drawable.ic_menu_language, R.id.action_settingsFragment_to_inputSettingsFragment)
            addDestinationPreference(R.string.ime_settings_handwriting, R.drawable.ic_menu_handwriting, R.id.action_settingsFragment_to_handwritingSettingsFragment)
        }
        addCategory(R.string.keyboard) {
            addDestinationPreference(R.string.theme, R.drawable.ic_menu_theme, R.id.action_settingsFragment_to_themeSettingsFragment)
            addDestinationPreference(R.string.keyboard_feedback, R.drawable.ic_menu_touch, R.id.action_settingsFragment_to_keyboardFeedbackFragment)
            addDestinationPreference(R.string.setting_ime_keyboard, R.drawable.ic_menu_keyboard, R.id.action_settingsFragment_to_keyboardSettingFragment)
            addDestinationPreference(R.string.clipboard, R.drawable.ic_menu_clipboard, R.id.action_settingsFragment_to_clipboardSettingsFragment)
            // 已隐藏“全面屏键盘优化”入口
            // addDestinationPreference(R.string.full_display_keyboard, R.drawable.ic_menu_keyboard_full, R.id.action_settingsFragment_to_fullDisplayKeyboardFragment)
        }
        // 已隐藏“双屏”分组入口
        // addCategory(R.string.setting_dual_screen) {
        //     addDestinationPreference(R.string.setting_dual_screen, R.drawable.ic_menu_dualscreen, R.id.dualScreenSettingsFragment)
        // }
        addCategory(R.string.advanced) {
            addDestinationPreference(R.string.setting_ime_other, R.drawable.ic_menu_more_horiz, R.id.action_settingsFragment_to_otherSettingsFragment)
            addPreference(R.string.about) {
                setIcon(R.drawable.ic_menu_feedback)
                setOnPreferenceClickListener {
                    findNavController().navigate(R.id.action_settingsFragment_to_aboutFragment)
                    true
                }
            }
        }
    }

    private fun addCategory(title: Int, category: PreferenceCategory.() -> Unit) {
        val cat = PreferenceCategory(requireContext()).apply {
            setTitle(title)
            isIconSpaceReserved = false
        }
        screen.addPreference(cat)
        category(cat)
    }

    private fun PreferenceCategory.addDestinationPreference(title: Int, icon: Int, action: Int) {
        val tint = getPrimaryTextColor()
        val drawable = requireContext().getDrawable(icon)?.let { d ->
            val wrap = DrawableCompat.wrap(d)
            DrawableCompat.setTintList(wrap, tint)
            wrap
        }
        val pref = Preference(requireContext()).apply {
            this.icon = drawable
            setTitle(title)
            setOnPreferenceClickListener {
                findNavController().navigate(action)
                true
            }
        }
        addPreference(pref)
    }

    private fun PreferenceCategory.addPreference(title: Int, preference: Preference.() -> Unit) {
        val pref = Preference(requireContext()).apply {
            setTitle(title)
            preference()
            val tint = getPrimaryTextColor()
            val ic = this.icon
            if (ic != null) {
                val wrap = DrawableCompat.wrap(ic)
                DrawableCompat.setTintList(wrap, tint)
                this.icon = wrap
            }
        }
        addPreference(pref)
    }

    private fun getPrimaryTextColor(): ColorStateList {
        val tv = TypedValue()
        val theme = requireContext().theme
        return if (theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)) {
            if (tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
                ColorStateList.valueOf(tv.data)
            } else {
                AppCompatResources.getColorStateList(requireContext(), tv.resourceId)
            }
        } else {
            AppCompatResources.getColorStateList(requireContext(), android.R.color.black)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }
}