package com.mbox.store

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat

/** Small builders for the Play-style grouped menu rows used by Profile
 *  and Settings — colored icon tile, title, optional sub-line, and an
 *  optional badge, switch or chevron at the end. */
object Ui {

    fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    fun cardBg(context: Context, radiusDp: Int = 20): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.RECTANGLE
        g.cornerRadius = dp(context, radiusDp).toFloat()
        g.setColor(context.getColor(R.color.surface))
        return g
    }

    fun sectionLabel(context: Context, text: String): TextView {
        val tv = TextView(context)
        tv.text = text
        tv.textSize = 13f
        tv.setTextColor(context.getColor(R.color.text_secondary))
        tv.letterSpacing = 0.08f
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(context, 22)
        lp.bottomMargin = dp(context, 8)
        tv.layoutParams = lp
        return tv
    }

    fun group(context: Context, vararg rows: View): LinearLayout {
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        box.background = cardBg(context)
        for ((i, r) in rows.withIndex()) {
            if (i > 0) box.addView(divider(context))
            box.addView(r)
        }
        return box
    }

    fun divider(context: Context): View {
        val v = View(context)
        v.setBackgroundColor(Color.parseColor("#20272F"))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1
        )
        lp.marginStart = dp(context, 72)
        v.layoutParams = lp
        return v
    }

    fun row(
        context: Context,
        iconRes: Int,
        iconColor: String,
        title: String,
        sub: String? = null,
        badge: String? = null,
        chevron: Boolean = true,
        onClick: (() -> Unit)? = null
    ): View = rowShell(context, iconRes, iconColor, title, sub) { end ->
        if (badge != null) {
            val b = TextView(context)
            b.text = badge
            b.textSize = 14f
            b.setTextColor(context.getColor(R.color.text_secondary))
            end.addView(b)
        }
        if (chevron) {
            val c = TextView(context)
            c.text = "›"
            c.textSize = 20f
            c.setTextColor(Color.parseColor("#5D6871"))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(context, 8)
            c.layoutParams = lp
            end.addView(c)
        }
    }.also { if (onClick != null) it.setOnClickListener { onClick() } }

    fun switchRow(
        context: Context,
        iconRes: Int,
        iconColor: String,
        title: String,
        sub: String? = null,
        checked: Boolean,
        enabled: Boolean = true,
        onChange: (Boolean) -> Unit
    ): View {
        lateinit var shell: View
        shell = rowShell(context, iconRes, iconColor, title, sub) { end ->
            val sw = SwitchCompat(context)
            sw.isChecked = checked
            sw.isEnabled = enabled
            sw.setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
            end.addView(sw)
        }
        shell.alpha = if (enabled) 1f else 0.55f
        return shell
    }

    private fun rowShell(
        context: Context,
        iconRes: Int,
        iconColor: String,
        title: String,
        sub: String?,
        endBuilder: (LinearLayout) -> Unit
    ): View {
        val root = LinearLayout(context)
        root.orientation = LinearLayout.HORIZONTAL
        root.gravity = Gravity.CENTER_VERTICAL
        root.setPadding(
            dp(context, 18), dp(context, 15),
            dp(context, 18), dp(context, 15)
        )
        root.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val tile = FrameLayout(context)
        val tileLp = LinearLayout.LayoutParams(dp(context, 40), dp(context, 40))
        tile.layoutParams = tileLp
        val color = Color.parseColor(iconColor)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = dp(context, 12).toFloat()
        bg.setColor(Color.argb(36, Color.red(color), Color.green(color), Color.blue(color)))
        tile.background = bg
        val iv = ImageView(context)
        iv.setImageResource(iconRes)
        iv.setColorFilter(color)
        val ivLp = FrameLayout.LayoutParams(dp(context, 22), dp(context, 22))
        ivLp.gravity = Gravity.CENTER
        iv.layoutParams = ivLp
        tile.addView(iv)
        root.addView(tile)

        val texts = LinearLayout(context)
        texts.orientation = LinearLayout.VERTICAL
        val tLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
        tLp.weight = 1f
        tLp.marginStart = dp(context, 16)
        texts.layoutParams = tLp
        val titleTv = TextView(context)
        titleTv.text = title
        titleTv.textSize = 15.5f
        titleTv.setTextColor(context.getColor(R.color.text_primary))
        texts.addView(titleTv)
        if (sub != null) {
            val subTv = TextView(context)
            subTv.text = sub
            subTv.textSize = 12.5f
            subTv.setTextColor(context.getColor(R.color.text_secondary))
            val sLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            sLp.topMargin = dp(context, 2)
            subTv.layoutParams = sLp
            texts.addView(subTv)
        }
        root.addView(texts)

        val end = LinearLayout(context)
        end.orientation = LinearLayout.HORIZONTAL
        end.gravity = Gravity.CENTER_VERTICAL
        root.addView(end)
        endBuilder(end)
        return root
    }
}
