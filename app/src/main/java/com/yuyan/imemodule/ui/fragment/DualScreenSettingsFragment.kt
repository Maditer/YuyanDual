package com.yuyan.imemodule.ui.fragment

import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.ui.fragment.base.ManagedPreferenceFragment

class DualScreenSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().dualScreen)