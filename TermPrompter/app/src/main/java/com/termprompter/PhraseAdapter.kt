package com.termprompter

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Terminal-style phrase list: dim = already read, bright + tinted = current, bright = upcoming. */
class PhraseAdapter : RecyclerView.Adapter<PhraseAdapter.VH>() {
    var items: List<Phrase> = emptyList()
    var current = 0
    var adlib = false
    var textSp = 32f

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = TextView(parent.context)
        tv.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tv.typeface = Typeface.MONOSPACE
        val pad = (parent.resources.displayMetrics.density * 12).toInt()
        tv.setPadding(pad * 2, pad, pad * 2, pad)
        tv.setLineSpacing(0f, 1.15f)
        return VH(tv)
    }

    override fun onBindViewHolder(h: VH, i: Int) {
        val tv = h.tv
        tv.text = items[i].text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
        when {
            i < current -> {
                tv.setTextColor(DIM); tv.setBackgroundColor(Color.TRANSPARENT); tv.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            }
            i == current -> {
                tv.setTextColor(if (adlib) AMBER else NEON)
                tv.setBackgroundColor(if (adlib) AMBER_BG else NEON_BG)
                tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            }
            else -> {
                tv.setTextColor(NEON); tv.setBackgroundColor(Color.TRANSPARENT); tv.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            }
        }
    }

    override fun getItemCount() = items.size

    fun submit(list: List<Phrase>) { items = list; current = 0; adlib = false; notifyDataSetChanged() }

    fun setCurrent(c: Int) {
        val old = current
        current = c
        if (itemCount == 0) return
        val lo = minOf(old, c).coerceIn(0, itemCount - 1)
        val hi = maxOf(old, c).coerceIn(0, itemCount - 1)
        notifyItemRangeChanged(lo, hi - lo + 1)
    }

    fun refreshCurrent() { if (current in 0 until itemCount) notifyItemChanged(current) }

    companion object {
        val NEON = Color.parseColor("#39FF14")
        val DIM = Color.parseColor("#1B6B10")
        val NEON_BG = Color.parseColor("#0D330D")
        val AMBER = Color.parseColor("#FFB000")
        val AMBER_BG = Color.parseColor("#332400")
    }
}
