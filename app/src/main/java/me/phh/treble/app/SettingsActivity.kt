package me.phh.treble.app

import android.app.FragmentManager
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import android.preference.EditTextPreference
import android.preference.ListPreference
import android.preference.Preference
import android.preference.PreferenceCategory
import android.preference.PreferenceFragment
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import android.app.Fragment
import com.google.android.material.appbar.CollapsingToolbarLayout

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applicationContext.startServiceAsUser(
            Intent(applicationContext, EntryService::class.java), UserHandle.SYSTEM
        )

        fragmentManager.registerFragmentLifecycleCallbacks(pageStyler, false)

        if (savedInstanceState == null) {
            fragmentManager.beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                if (fragmentManager.backStackEntryCount > 0) {
                    fragmentManager.popBackStack()
                } else {
                    finish()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onBackPressed() {
        if (fragmentManager.backStackEntryCount > 0) {
            fragmentManager.popBackStack()
        } else {
            super.onBackPressed()
        }
    }

    // Every settings page inflates fragment_misc_settings; give them all the
    // large collapsing title and segmented row cards from one place.
    private val pageStyler = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
            val collapsing = v.findViewById<CollapsingToolbarLayout>(R.id.collapsing_toolbar) ?: return
            collapsing.title = f.arguments?.getCharSequence(ARG_TITLE)
                ?: if (f is SettingsFragment) mainTitle() else title
            supportActionBar?.setDisplayShowTitleEnabled(false)

            v.findViewById<ListView>(android.R.id.list)?.apply {
                val density = resources.displayMetrics.density
                setPadding(0, (8 * density).toInt(), 0, (32 * density).toInt())
                divider = null
                dividerHeight = 0
                isNestedScrollingEnabled = true
            }
        }

        override fun onFragmentActivityCreated(fm: FragmentManager, f: Fragment, savedInstanceState: Bundle?) =
            groupRows(f)

        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) = groupRows(f)

        private fun groupRows(f: Fragment) {
            val list = f.view?.findViewById<ListView>(android.R.id.list) ?: return
            val adapter = list.adapter ?: return
            if (adapter !is GroupedListAdapter) list.adapter = GroupedListAdapter(adapter)
        }
    }

    private fun mainTitle(): CharSequence =
        getString(if (RogSettings.enabled(this)) R.string.title_main_rog else R.string.title_main_generic)

    class SettingsFragment : PreferenceFragment() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            addPreferencesFromResource(R.xml.pref_headers)

            val context = activity ?: return
            val checkEnabled = EntryService.getEnabledPreferences(context)
            checkEnabled.forEach { (key, isEnabled) ->
                if (!isEnabled) {
                    val preference = findPreference(key)
                    preference?.let { pref ->
                        pref.parent?.removePreference(pref)
                    }
                }
            }

            val setFragments = mapOf(
                "mydevice_settings" to "me.phh.treble.app.MyDeviceSettingsFragment",
                "oneplus_settings" to "me.phh.treble.app.OnePlusSettingsFragment",
                "nubia_settings" to "me.phh.treble.app.NubiaSettingsFragment",
                "vsmart_settings" to "me.phh.treble.app.VsmartSettingsFragment",
                "qualcomm_settings" to "me.phh.treble.app.QualcommSettingsFragment",
                "huawei_settings" to "me.phh.treble.app.HuaweiSettingsFragment",
                "samsung_settings" to "me.phh.treble.app.SamsungSettingsFragment",
                "transsion_settings" to "me.phh.treble.app.TranssionSettingsFragment",
                "lenovo_settings" to "me.phh.treble.app.LenovoSettingsFragment",
                "xiaomi_settings" to "me.phh.treble.app.XiaomiSettingsFragment",
                "oppo_settings" to "me.phh.treble.app.OppoSettingsFragment",
                "asus_settings" to "me.phh.treble.app.AsusSettingsFragment",
                "rog_settings" to "me.phh.treble.app.RogSettingsFragment",
                "doze_settings" to "me.phh.treble.app.DozeSettingsFragment",
                "mediatek_settings" to "me.phh.treble.app.MediatekSettingsFragment",
                "display_settings" to "me.phh.treble.app.DisplaySettingsFragment",
                "audio_settings" to "me.phh.treble.app.AudioSettingsFragment",
                "audiofx_settings" to "me.phh.treble.app.AudioEffectsFragment",
                "telephony_settings" to "me.phh.treble.app.TelephonySettingsFragment",
                "ims_settings" to "me.phh.treble.app.ImsSettingsFragment",
                "camera_settings" to "me.phh.treble.app.CameraSettingsFragment",
                "misc_settings" to "me.phh.treble.app.MiscSettingsFragment",
                "ui_settings" to "me.phh.treble.app.UiSettingsFragment",
                "debug_settings" to "me.phh.treble.app.DebugSettingsFragment"
            )

            for ((preferenceKey, fragmentClassName) in setFragments) {
                findPreference(preferenceKey)?.setOnPreferenceClickListener {
                    val fragment = Class.forName(fragmentClassName).getConstructor().newInstance() as Fragment
                    fragment.arguments = Bundle(it.extras).apply { putCharSequence(ARG_TITLE, it.title) }
                    fragmentManager.beginTransaction()
                    .replace(android.R.id.content, fragment)
                    .addToBackStack(null)
                    .commit()
                    true
                }
            }
        }

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
            return inflater.inflate(R.layout.fragment_misc_settings, container, false)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            (activity as? AppCompatActivity)?.setSupportActionBar(view.findViewById<Toolbar>(R.id.toolbar))
        }
    }

    companion object {
        const val ARG_TITLE = "treble_page_title"

        fun bindPreferenceSummaryToValue(preference: Preference) {
            preference.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { pref, newValue ->
                val stringValue = newValue.toString()
                val defaultSummary = pref.summary
                pref.summary = when (pref) {
                    is ListPreference -> {
                        val index = pref.findIndexOfValue(stringValue)
                        if (index >= 0) pref.entries[index] else defaultSummary
                    }
                    is EditTextPreference -> {
                        if (stringValue.isNotEmpty()) stringValue.toIntOrNull()?.toString() ?: stringValue else defaultSummary
                    }
                    else -> if (stringValue.isNotEmpty()) stringValue else defaultSummary
                }
                true
            }

            val preferenceManager = PreferenceManager.getDefaultSharedPreferences(preference.context)
            val rawValue = preferenceManager.all[preference.key]
            val value = when (rawValue) {
                is String -> rawValue
                else -> {
                    preferenceManager.edit().remove(preference.key).apply()
                    ""
                }
            }
            preference.onPreferenceChangeListener.onPreferenceChange(preference, value)
        }
    }
}
