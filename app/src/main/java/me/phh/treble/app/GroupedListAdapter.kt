package me.phh.treble.app

import android.database.DataSetObserver
import android.preference.Preference
import android.preference.PreferenceCategory
import android.view.View
import android.view.ViewGroup
import android.widget.ListAdapter
import android.widget.WrapperListAdapter

/**
 * Wraps a PreferenceScreen's adapter so consecutive rows inside a category
 * render as one segmented card, like Android 16 Settings: the first row gets
 * rounded top corners, the last row rounded bottom corners.
 */
class GroupedListAdapter(private val inner: ListAdapter) : WrapperListAdapter {

    private fun breaksGroup(position: Int): Boolean {
        if (position < 0 || position >= inner.count) return true
        val item = inner.getItem(position) as? Preference ?: return true
        return item is PreferenceCategory || item.layoutResource == R.layout.preference_hero
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = inner.getView(position, convertView, parent)
        view.findViewById<View>(R.id.card)?.let { card ->
            val top = breaksGroup(position - 1)
            val bottom = breaksGroup(position + 1)
            card.setBackgroundResource(
                when {
                    top && bottom -> R.drawable.bg_row_single
                    top -> R.drawable.bg_row_top
                    bottom -> R.drawable.bg_row_bottom
                    else -> R.drawable.bg_row_middle
                }
            )
        }
        return view
    }

    override fun getWrappedAdapter(): ListAdapter = inner
    override fun areAllItemsEnabled() = inner.areAllItemsEnabled()
    override fun isEnabled(position: Int) = inner.isEnabled(position)
    override fun registerDataSetObserver(observer: DataSetObserver) = inner.registerDataSetObserver(observer)
    override fun unregisterDataSetObserver(observer: DataSetObserver) = inner.unregisterDataSetObserver(observer)
    override fun getCount() = inner.count
    override fun getItem(position: Int): Any? = inner.getItem(position)
    override fun getItemId(position: Int) = inner.getItemId(position)
    override fun hasStableIds() = inner.hasStableIds()
    override fun getItemViewType(position: Int) = inner.getItemViewType(position)
    override fun getViewTypeCount() = inner.viewTypeCount
    override fun isEmpty() = inner.isEmpty
}
