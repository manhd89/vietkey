package com.vietsmart.key

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import androidx.core.view.setPadding

// ─────────────────────────────────────────────────────────────────────────────
// fcitx5-style keyboard — Material You, rounded keys, candidate bar
// ─────────────────────────────────────────────────────────────────────────────

class KeyboardView(
    context: Context,
    private val listener: KeyListener
) : LinearLayout(context) {

    interface KeyListener {
        fun onKey(key: String)
        fun onVietToggle(enabled: Boolean)
        fun onInputMethodChange(method: InputMethod)
    }

    // ── State ─────────────────────────────────────────────────────────────
    private var isShifted  = false
    private var isCapsLock = false
    private var isViet     = true
    private var isSymbol   = false
    private var currentIM  = InputMethod.TELEX

    private val keyButtons = mutableListOf<Button>()
    private lateinit var shiftBtn: Button
    private lateinit var vietBtn : Button
    private lateinit var imBtn   : Button

    // ── fcitx5 color palette (light theme matching fcitx5-android) ────────
    //   https://github.com/fcitx5-android/fcitx5-android
    private val clrBg          = Color.parseColor("#EAEEF3")   // keyboard bg
    private val clrKeyBg       = Color.parseColor("#FFFFFF")   // normal key
    private val clrKeyBgPress  = Color.parseColor("#C8D0DC")   // pressed
    private val clrFuncBg      = Color.parseColor("#B4BCC8")   // functional key bg
    private val clrFuncPress   = Color.parseColor("#9BA4B0")
    private val clrAccent      = Color.parseColor("#1E88E5")   // VN active / shift
    private val clrAccentPress = Color.parseColor("#1565C0")
    private val clrTxt         = Color.parseColor("#1A1C1E")
    private val clrTxtFunc     = Color.parseColor("#2D3142")
    private val clrTxtAccent   = Color.WHITE
    private val clrShadow      = Color.parseColor("#22000000")
    private val clrCandidateBg = Color.parseColor("#F5F8FC")
    private val clrDivider     = Color.parseColor("#DDE2EA")

    // ── Layout constants ──────────────────────────────────────────────────
    private val KEY_H       = dp(48)
    private val CAND_H      = dp(40)
    private val KEY_RADIUS  = dp(8).toFloat()
    private val KEY_MH      = dp(3)   // horizontal margin
    private val KEY_MV      = dp(3)   // vertical margin
    private val ROW_PD_H    = dp(4)

    // ── Key layouts ───────────────────────────────────────────────────────
    private val alphaRows = listOf(
        listOf("q","w","e","r","t","y","u","i","o","p"),
        listOf("a","s","d","f","g","h","j","k","l"),
        listOf("z","x","c","v","b","n","m")
    )
    private val symRows = listOf(
        listOf("1","2","3","4","5","6","7","8","9","0"),
        listOf("-","/",":",";","(",")","$","&","@","\""),
        listOf(".","·",",","?","!","'","`","~","^","_")
    )
    private val sym2Rows = listOf(
        listOf("[","]","{","}","#","%","^","*","+","="),
        listOf("_","\\","|","<",">","€","£","¥","•","…"),
        listOf("°","©","®","™","✓","→","←","↑","↓","↔")
    )

    // ── Candidate bar ─────────────────────────────────────────────────────
    private lateinit var candidateBar: LinearLayout

    init {
        orientation = VERTICAL
        setBackgroundColor(clrBg)
        buildBoard()
    }

    // ── Build ─────────────────────────────────────────────────────────────

    private fun buildBoard() {
        removeAllViews()
        keyButtons.clear()

        // Candidate / toolbar bar (fcitx5 style)
        candidateBar = buildCandidateBar()
        addView(candidateBar)

        // Divider
        addView(divider())

        // Key rows
        val rows = if (isSymbol) symRows else alphaRows
        rows.forEachIndexed { idx, row ->
            addView(buildLetterRow(row, idx == 2))
        }
        addView(buildBottomRow())
        addView(bottomSpacer())
    }

    // ── Candidate bar ─────────────────────────────────────────────────────

    private fun buildCandidateBar(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(MATCH_PARENT, CAND_H)
            setBackgroundColor(clrCandidateBg)
            setPadding(dp(8), 0, dp(8), 0)

            // IM selector pill
            val imPill = makePill(imLabel(), clrFuncBg, clrTxtFunc).also { pill ->
                pill.setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    cycleIM()
                    (it as TextView).text = imLabel()
                }
            }
            addView(imPill)

            // Spacer
            addView(spacer())

            // VN/EN toggle pill
            val vnPill = makePill(
                if (isViet) "VN" else "EN",
                if (isViet) clrAccent else clrFuncBg,
                if (isViet) clrTxtAccent else clrTxtFunc
            ).also { pill ->
                vietBtn = pill as Button
                pill.setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    isViet = !isViet
                    (it as Button).text = if (isViet) "VN" else "EN"
                    it.setBackgroundColor(if (isViet) clrAccent else clrFuncBg)
                    it.setTextColor(if (isViet) clrTxtAccent else clrTxtFunc)
                    listener.onVietToggle(isViet)
                }
            }
            addView(vnPill)
        }
    }

    // ── Letter rows ───────────────────────────────────────────────────────

    private fun buildLetterRow(keys: List<String>, isThirdRow: Boolean): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(MATCH_PARENT, KEY_H + KEY_MV * 2).apply {
                setMargins(ROW_PD_H, 0, ROW_PD_H, 0)
            }

            if (isThirdRow) {
                // Shift key
                shiftBtn = makeFuncKey(shiftLabel(), 1.4f,
                    if (isShifted || isCapsLock) clrAccent else clrFuncBg,
                    if (isShifted || isCapsLock) clrTxtAccent else clrTxtFunc
                ).also { btn ->
                    var lastTap = 0L
                    btn.setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        val now = System.currentTimeMillis()
                        when {
                            now - lastTap < 350 -> { isCapsLock = true; isShifted = true }
                            isCapsLock          -> { isCapsLock = false; isShifted = false }
                            else                -> { isShifted = !isShifted; isCapsLock = false }
                        }
                        lastTap = now
                        refreshShift(); refreshLetterKeys()
                    }
                }
                addView(shiftBtn)
            }

            keys.forEach { k -> addView(makeLetterKey(k).also { keyButtons.add(it) }) }

            if (isThirdRow) {
                // Backspace
                addView(makeFuncKey("⌫", 1.4f, clrFuncBg, clrTxtFunc).also { btn ->
                    btn.setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        listener.onKey("⌫")
                    }
                    btn.setOnLongClickListener {
                        repeat(6) { listener.onKey("⌫") }; true
                    }
                })
            }
        }
    }

    // ── Bottom row ────────────────────────────────────────────────────────

    private fun buildBottomRow(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(MATCH_PARENT, KEY_H + KEY_MV * 2).apply {
                setMargins(ROW_PD_H, 0, ROW_PD_H, 0)
            }

            // ?123 / ABC
            addView(makeFuncKey(if (isSymbol) "ABC" else "?123", 1.5f, clrFuncBg, clrTxtFunc).also { btn ->
                btn.textSize = 11f
                btn.setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    isSymbol = !isSymbol; buildBoard()
                }
            })

            // Comma
            addView(makeLetterKey(",", 0.8f))

            // Space bar — fcitx5 shows "Tiếng Việt" or "English"
            addView(makeSpaceKey(if (isViet) "Tiếng Việt" else "English", 3.8f))

            // Period
            addView(makeLetterKey(".", 0.8f))

            // Enter / Return
            addView(makeFuncKey("↵", 1.5f, clrAccent, clrTxtAccent).also { btn ->
                btn.setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    listener.onKey("\n")
                }
            })
        }
    }

    // ── Key factories ─────────────────────────────────────────────────────

    private fun makeLetterKey(rawKey: String, weight: Float = 1f): Button {
        return Button(context).apply {
            text = displayKey(rawKey)
            layoutParams = LayoutParams(0, MATCH_PARENT, weight).apply {
                setMargins(KEY_MH, KEY_MV, KEY_MH, KEY_MV)
            }
            background = roundedKeyDrawable(clrKeyBg, clrKeyBgPress, KEY_RADIUS)
            setTextColor(clrTxt)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(0, 0, 0, 0)
            elevation = dp(2).toFloat()
            stateListAnimator = null
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                val label = displayKey(rawKey)
                listener.onKey(label)
                if (isShifted && !isCapsLock) {
                    isShifted = false; refreshShift(); refreshLetterKeys()
                }
            }
        }
    }

    private fun makeFuncKey(label: String, weight: Float, bg: Int, txt: Int): Button {
        return Button(context).apply {
            text = label
            layoutParams = LayoutParams(0, MATCH_PARENT, weight).apply {
                setMargins(KEY_MH, KEY_MV, KEY_MH, KEY_MV)
            }
            val pressBg = darken(bg, 0.85f)
            background = roundedKeyDrawable(bg, pressBg, KEY_RADIUS)
            setTextColor(txt)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, 0)
            elevation = dp(2).toFloat()
            stateListAnimator = null
        }
    }

    private fun makeSpaceKey(hint: String, weight: Float): Button {
        return Button(context).apply {
            text = hint
            layoutParams = LayoutParams(0, MATCH_PARENT, weight).apply {
                setMargins(KEY_MH, KEY_MV, KEY_MH, KEY_MV)
            }
            background = roundedKeyDrawable(clrKeyBg, clrKeyBgPress, KEY_RADIUS)
            setTextColor(Color.parseColor("#6B7280"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, 0)
            elevation = dp(2).toFloat()
            stateListAnimator = null
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                listener.onKey(" ")
            }
        }
    }

    private fun makePill(label: String, bg: Int, txt: Int): Button {
        return Button(context).apply {
            text = label
            layoutParams = LayoutParams(WRAP_CONTENT, dp(28)).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
            val pill = GradientDrawable().apply {
                color = android.content.res.ColorStateList.valueOf(bg)
                cornerRadius = dp(14).toFloat()
            }
            background = pill
            setTextColor(txt)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(10), 0, dp(10), 0)
            minWidth = 0
            minimumWidth = 0
            stateListAnimator = null
        }
    }

    // ── Drawables ─────────────────────────────────────────────────────────

    private fun roundedKeyDrawable(normal: Int, pressed: Int, radius: Float): StateListDrawable {
        val normalBg = GradientDrawable().apply {
            setColor(normal)
            cornerRadius = radius
        }
        val pressedBg = GradientDrawable().apply {
            setColor(pressed)
            cornerRadius = radius
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressedBg)
            addState(intArrayOf(), normalBg)
        }
    }

    private fun darken(color: Int, factor: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] *= factor
        return Color.HSVToColor(hsv)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun displayKey(raw: String) =
        if (isShifted || isCapsLock) raw.uppercase() else raw

    private fun shiftLabel() = when {
        isCapsLock -> "⇪"
        isShifted  -> "⇧"
        else       -> "⇧"
    }

    private fun imLabel() = when (currentIM) {
        InputMethod.TELEX        -> "Telex"
        InputMethod.VNI          -> "VNI"
        InputMethod.VIQR         -> "VIQR"
        InputMethod.SIMPLE_TELEX -> "Telex+"
    }

    private fun cycleIM() {
        currentIM = when (currentIM) {
            InputMethod.TELEX        -> InputMethod.VNI
            InputMethod.VNI          -> InputMethod.VIQR
            InputMethod.VIQR         -> InputMethod.SIMPLE_TELEX
            InputMethod.SIMPLE_TELEX -> InputMethod.TELEX
        }
        listener.onInputMethodChange(currentIM)
    }

    private fun refreshShift() {
        if (!::shiftBtn.isInitialized) return
        shiftBtn.text = shiftLabel()
        val active = isShifted || isCapsLock
        shiftBtn.background = roundedKeyDrawable(
            if (active) clrAccent else clrFuncBg,
            if (active) clrAccentPress else clrFuncPress,
            KEY_RADIUS
        )
        shiftBtn.setTextColor(if (active) clrTxtAccent else clrTxtFunc)
    }

    private fun refreshLetterKeys() {
        val flat = (if (isSymbol) symRows else alphaRows).flatten()
        keyButtons.forEachIndexed { i, btn ->
            if (i < flat.size) btn.text = displayKey(flat[i])
        }
    }

    private fun divider() = View(context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, dp(1))
        setBackgroundColor(clrDivider)
    }

    private fun spacer() = View(context).apply {
        layoutParams = LayoutParams(0, MATCH_PARENT, 1f)
    }

    private fun bottomSpacer() = View(context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, dp(4))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
