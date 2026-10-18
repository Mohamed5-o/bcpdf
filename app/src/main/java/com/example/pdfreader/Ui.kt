package com.example.pdfreader

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

object C {
    val bg = Color.rgb(6, 11, 10)
    val panel = Color.rgb(13, 25, 21)
    val panel2 = Color.rgb(20, 38, 31)
    val gold = Color.rgb(232, 190, 75)
    val white = Color.rgb(244, 247, 245)
    val muted = Color.rgb(178, 196, 188)
    val red = Color.rgb(232, 90, 90)
    val line = Color.argb(46, 255, 255, 255)
    var accent = Color.rgb(32, 190, 120)

    val accents = intArrayOf(
        Color.rgb(32, 190, 120), Color.rgb(232, 190, 75), Color.rgb(40, 190, 220), Color.rgb(160, 110, 240)
    )

    fun accentDark(): Int = Color.rgb(
        (Color.red(accent) * 0.30f).toInt(), (Color.green(accent) * 0.30f).toInt(), (Color.blue(accent) * 0.30f).toInt()
    )

    fun accentSoft(alpha: Int): Int = Color.argb(alpha, Color.red(accent), Color.green(accent), Color.blue(accent))
}

class Ui(val ctx: Context, val st: Settings) {
    val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val density = ctx.resources.displayMetrics.density

    fun dp(v: Int): Int = (v * density + 0.5f).toInt()
    fun dpf(v: Float): Float = v * density
    fun sp(v: Float): Float = v * st.textScale()
    val rtl: Boolean get() = st.arabic
    fun t(ar: String, en: String): String = if (st.arabic) ar else en
    fun dir(): Int = if (st.arabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

    fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight)

    fun lpm(w: Int, h: Int, weight: Float = 0f, l: Int = 0, tp: Int = 0, r: Int = 0, b: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).also { it.setMargins(l, tp, r, b) }

    fun rounded(fill: Int, radius: Int = 14, stroke: Int = 0, strokeDp: Int = 1): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(radius).toFloat()
        if (stroke != 0) g.setStroke(dp(strokeDp), stroke)
        return g
    }

    /** Stroke colour for cards/dialogs: glowing accent when visual effects are on. */
    fun glow(): Int = if (st.effects) C.accent else C.line

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    fun txt(
        text: String, size: Float = 14f, color: Int = C.white, bold: Boolean = false,
        center: Boolean = false
    ): TextView {
        val v = TextView(ctx)
        v.text = text
        v.textSize = sp(size)
        v.setTextColor(color)
        if (bold) v.typeface = Typeface.DEFAULT_BOLD
        v.gravity = if (center) Gravity.CENTER else (Gravity.START or Gravity.CENTER_VERTICAL)
        return v
    }

    /** Visual + haptic feedback on press (does not consume the touch, so clicks still work). */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun pressFx(v: View, strength: Float = 0.94f) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    view.animate().cancel()
                    view.animate().scaleX(strength).scaleY(strength).alpha(0.78f).setDuration(70).start()
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    view.animate().cancel()
                    view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(140).start()
                }
            }
            false
        }
    }

    fun btn(label: String, fill: Int, color: Int, stroke: Int = 0, onClick: () -> Unit): TextView {
        val v = txt(label, 14f, color, true, true)
        v.background = rounded(fill, 14, stroke)
        v.setPadding(dp(16), dp(11), dp(16), dp(11))
        v.minimumHeight = dp(48)
        v.setOnClickListener { onClick() }
        pressFx(v)
        return v
    }

    fun primaryBtn(label: String, onClick: () -> Unit): TextView = btn(label, C.accent, Color.BLACK, 0, onClick)
    fun ghostBtn(label: String, onClick: () -> Unit): TextView = btn(label, C.panel2, C.white, C.line, onClick)

    fun icon(kind: String, tint: Int = C.white, sizeDp: Int = 44, onClick: (() -> Unit)? = null): IconView {
        val v = IconView(ctx, kind, tint)
        val sz = if (sizeDp < 48) 48 else sizeDp
        v.flipForRtl = rtl
        v.layoutParams = LinearLayout.LayoutParams(dp(sz), dp(sz))
        val pad = dp(sz / 4)
        v.setPadding(pad, pad, pad, pad)
        if (onClick != null) { v.setOnClickListener { onClick() }; pressFx(v, 0.88f) }
        return v
    }

    fun field(hint: String, value: String = "", multiline: Boolean = false): EditText {
        val e = EditText(ctx)
        e.hint = hint
        e.setText(value)
        e.setTextColor(C.white)
        e.setHintTextColor(C.muted)
        e.textSize = sp(15f)
        e.background = rounded(C.panel2, 12, C.line)
        e.setPadding(dp(12), dp(10), dp(12), dp(10))
        if (multiline) {
            e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            e.minLines = 2
            e.gravity = Gravity.TOP or Gravity.START
        } else {
            e.inputType = InputType.TYPE_CLASS_TEXT
            e.setSingleLine(true)
        }
        return e
    }

    fun toggle(checked: Boolean, onChange: (Boolean) -> Unit): Switch {
        val s = Switch(ctx)
        s.isChecked = checked
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        s.thumbTintList = ColorStateList(states, intArrayOf(C.white, C.muted))
        s.trackTintList = ColorStateList(states, intArrayOf(C.accent, Color.rgb(60, 66, 64)))
        s.setOnCheckedChangeListener { _, v -> onChange(v) }
        return s
    }

    fun seek(max: Int, value: Int, onChange: (Int) -> Unit): SeekBar {
        val s = SeekBar(ctx)
        s.max = max
        s.progress = value
        s.progressTintList = ColorStateList.valueOf(C.accent)
        s.thumbTintList = ColorStateList.valueOf(C.accent)
        s.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        return s
    }

    fun divider(): View {
        val v = View(ctx)
        v.setBackgroundColor(C.line)
        v.layoutParams = LinearLayout.LayoutParams(MATCH, 1)
        return v
    }

    fun spacer(h: Int): View {
        val v = View(ctx)
        v.layoutParams = LinearLayout.LayoutParams(1, dp(h))
        return v
    }

    /** Screen root: dark background, honours system bars. */
    fun screenRoot(): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.VERTICAL
        r.setBackgroundColor(C.bg)
        r.layoutDirection = dir()
        r.fitsSystemWindows = true
        return r
    }

    fun header(title: String, onBack: (() -> Unit)?, trailing: View? = null): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.layoutDirection = dir()
        row.setPadding(dp(6), dp(4), dp(6), dp(4))
        if (onBack != null) row.addView(icon("back", C.white, 48, onBack)) else row.addView(View(ctx), lp(dp(48), dp(48)))
        val t = txt(title, 19f, C.white, true, true)
        row.addView(t, lp(0, WRAP, 1f))
        if (trailing != null) row.addView(trailing) else row.addView(View(ctx), lp(dp(48), dp(48)))
        return row
    }

    fun card(radius: Int = 16): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.background = rounded(C.panel, radius, C.line)
        return c
    }

    /** Modal card dialog. [build] fills the card; use the Dialog to dismiss. */
    fun dialog(cancelable: Boolean = true, full: Boolean = false, build: (LinearLayout, Dialog) -> Unit): Dialog {
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        card.background = rounded(C.panel, 24, glow(), 1)
        card.setPadding(dp(18), dp(18), dp(18), dp(18))
        card.layoutDirection = dir()
        build(card, d)
        val sv = ScrollView(ctx)
        sv.isFillViewport = full
        sv.addView(card, if (full) FrameLayout.LayoutParams(MATCH, MATCH) else FrameLayout.LayoutParams(MATCH, WRAP))
        d.setContentView(sv)
        d.setCancelable(cancelable)
        val w = d.window
        if (w != null) {
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val maxW = dp(520)
            val sw = (ctx.resources.displayMetrics.widthPixels * (if (full) 0.97f else 0.94f)).toInt()
            val hh = if (full) (ctx.resources.displayMetrics.heightPixels * 0.9f).toInt() else WRAP
            w.setLayout(if (sw > maxW) maxW else sw, hh)
            w.setDimAmount(0.72f)
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            w.setWindowAnimations(android.R.style.Animation_Dialog)
        }
        d.show()
        return d
    }

    fun menu(items: List<Pair<String, () -> Unit>>, title: String? = null): Dialog = dialog { card, d ->
        if (title != null) {
            card.addView(txt(title, 17f, C.gold, true, true), lpm(MATCH, WRAP, 0f, 0, 0, 0, dp(8)))
        }
        items.forEachIndexed { i, item ->
            if (i > 0) card.addView(divider())
            val row = txt(item.first, 16f, C.white)
            row.setPadding(dp(8), dp(14), dp(8), dp(14))
            row.setOnClickListener { d.dismiss(); item.second() }
            card.addView(row, lp(MATCH, WRAP))
        }
    }

    fun confirm(title: String, message: String, okLabel: String, danger: Boolean, onOk: () -> Unit): Dialog =
        dialog { card, d ->
            card.addView(txt(title, 18f, C.gold, true, true))
            val m = txt(message, 14f, C.white, false, true)
            card.addView(m, lpm(MATCH, WRAP, 0f, 0, dp(10), 0, dp(16)))
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(ghostBtn(t("إلغاء", "Cancel")) { d.dismiss() }, lpm(0, WRAP, 1f, 0, 0, dp(6), 0))
            val ok = btn(okLabel, if (danger) C.red else C.accent, Color.BLACK) { d.dismiss(); onOk() }
            row.addView(ok, lpm(0, WRAP, 1f, dp(6), 0, 0, 0))
            card.addView(row)
        }

    fun input(title: String, hint: String, initial: String, okLabel: String, onOk: (String) -> Unit): Dialog =
        dialog { card, d ->
            card.addView(txt(title, 18f, C.gold, true, true))
            val f = field(hint, initial)
            card.addView(f, lpm(MATCH, WRAP, 0f, 0, dp(12), 0, dp(14)))
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(ghostBtn(t("إلغاء", "Cancel")) { d.dismiss() }, lpm(0, WRAP, 1f, 0, 0, dp(6), 0))
            row.addView(primaryBtn(okLabel) {
                val v = f.text.toString().trim()
                if (v.isNotEmpty()) { d.dismiss(); onOk(v) }
            }, lpm(0, WRAP, 1f, dp(6), 0, 0, 0))
            card.addView(row)
        }

    /** Settings-style row: title, optional subtitle, optional trailing view. */
    fun settingRow(title: String, subtitle: String? = null, trailing: View? = null, onClick: (() -> Unit)? = null): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(16), dp(12), dp(16), dp(12))
        row.minimumHeight = dp(58)
        row.layoutDirection = dir()
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.addView(txt(title, 15f, C.white, true))
        if (subtitle != null) col.addView(txt(subtitle, 12f, C.muted))
        row.addView(col, lp(0, WRAP, 1f))
        if (trailing != null) row.addView(trailing)
        if (onClick != null) { row.setOnClickListener { onClick() }; pressFx(row, 0.985f) }
        return row
    }

    private fun sectionIcon(title: String): String? = when {
        title.contains("القراءة") || title.contains("Reading") -> "page"
        title.contains("المظهر") || title.contains("Appearance") -> "drop"
        title.contains("التعديل") || title.contains("Editing") -> "edit"
        title.contains("البحث") || title.contains("Search") -> "search"
        title.contains("الملفات") || title.contains("Files") -> "folder"
        title.contains("معلومات") || title.contains("About") -> "info"
        else -> null
    }

    fun section(parent: LinearLayout, title: String, rows: List<View>) {
        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.layoutDirection = dir()
        val ik = sectionIcon(title)
        if (ik != null) {
            val ic = IconView(ctx, ik, C.gold)
            ic.flipForRtl = rtl
            head.addView(ic, LinearLayout.LayoutParams(dp(24), dp(24)))
        }
        head.addView(txt(title, 15f, C.gold, true), lpm(WRAP, WRAP, 0f, dp(8), 0, dp(8), 0))
        parent.addView(head, lpm(MATCH, WRAP, 0f, dp(6), dp(20), dp(6), dp(8)))
        val c = card()
        rows.forEachIndexed { i, v ->
            if (i > 0) c.addView(divider())
            c.addView(v)
        }
        parent.addView(c, lp(MATCH, WRAP))
    }

    fun chevron(): View = icon("chevron", C.muted, 28)
}
