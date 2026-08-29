package com.orailnoor.droiddesk.terminal

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.termux.view.TerminalView

/**
 * 简化版 termux extra keys toolbar:
 *  - ESC, /, -, HOME, ↑, END, PGUP
 *  - TAB, CTRL, ALT, ←, ↓, →, PGDN
 *
 * 点击按钮直接往 PTY 写 ANSI 转义序列 (bytes)，最可靠。
 * CTRL/ALT 是修饰键，单击切换状态。
 */
class TerminalExtraKeysView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    var terminalView: TerminalView? = null

    private var ctrlDown = false
    private var altDown = false
    private val specialKeys = mutableMapOf<String, TextView>()

    private data class KeyDef(val display: String, val key: String, val modifier: Boolean = false)

    private val row1 = listOf(
        KeyDef("ESC", "ESC"),
        KeyDef("/", "/"),
        KeyDef("-", "-"),
        KeyDef("HOME", "HOME"),
        KeyDef("↑", "UP"),
        KeyDef("END", "END"),
        KeyDef("PGUP", "PGUP"),
    )

    private val row2 = listOf(
        KeyDef("TAB", "TAB"),
        KeyDef("CTRL", "CTRL", modifier = true),
        KeyDef("ALT", "ALT", modifier = true),
        KeyDef("←", "LEFT"),
        KeyDef("↓", "DOWN"),
        KeyDef("→", "RIGHT"),
        KeyDef("PGDN", "PGDN"),
    )

    // 第三行：常用 Ctrl 组合 (用于一键中断等)
    private val row3 = listOf(
        KeyDef("^C", "CTRL_C"),  // Ctrl+C → SIGINT
        KeyDef("^D", "CTRL_D"),  // Ctrl+D → EOF
        KeyDef("^Z", "CTRL_Z"),  // Ctrl+Z → SIGTSTP
        KeyDef("^L", "CTRL_L"),  // Ctrl+L → 清屏
        KeyDef("^A", "CTRL_A"),  // 行首
        KeyDef("^E", "CTRL_E"),  // 行尾
        KeyDef("^K", "CTRL_K"),  // 删到行尾
    )

    private val ESC = 0x1B.toByte()

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFF1A1A1A.toInt())
        val pad = dp(4)
        setPadding(pad, pad, pad, pad)
        addView(buildRow(row1))
        addView(buildRow(row2))
        addView(buildRow(row3))
    }

    private fun buildRow(keys: List<KeyDef>): View {
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        for (k in keys) {
            val btn = createKeyButton(k)
            val lp = LayoutParams(0, dp(36), 1f)
            lp.marginEnd = dp(2)
            btn.layoutParams = lp
            row.addView(btn)
            specialKeys[k.key] = btn
        }
        return row
    }

    private fun createKeyButton(def: KeyDef): TextView {
        val tv = TextView(context)
        tv.text = def.display
        tv.gravity = Gravity.CENTER
        tv.setTextColor(Color.WHITE)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        tv.setBackgroundColor(0xFF2A2A2A.toInt())
        tv.isClickable = true
        tv.isFocusable = true
        tv.setOnClickListener { v -> onKeyClick(def, v) }
        return tv
    }

    private fun toggleModifier(key: String) {
        when (key) {
            "CTRL" -> ctrlDown = !ctrlDown
            "ALT" -> altDown = !altDown
        }
        Log.i("ExtraKeys", "toggleModifier $key -> ctrl=$ctrlDown alt=$altDown")
        updateModifierVisual()
        terminalView?.requestFocus()
    }

    private fun updateModifierVisual() {
        specialKeys["CTRL"]?.setBackgroundColor(
            if (ctrlDown) 0xFFE95420.toInt() else 0xFF2A2A2A.toInt()
        )
        specialKeys["ALT"]?.setBackgroundColor(
            if (altDown) 0xFFE95420.toInt() else 0xFF2A2A2A.toInt()
        )
    }

    /** 直接把字节写到底层 PTY。最可靠的方式，绕过所有 emulator 处理。 */
    private fun writeBytes(tv: TerminalView, bytes: ByteArray) {
        val session = tv.mTermSession
        if (session != null) {
            session.write(bytes, 0, bytes.size)
        } else {
            Log.w("ExtraKeys", "no session")
        }
    }

    private fun arrowBytes(letter: Char): ByteArray = when (letter) {
        'A' -> byteArrayOf(ESC, 0x5B, 0x41)  // ESC [ A
        'B' -> byteArrayOf(ESC, 0x5B, 0x42)  // ESC [ B
        'C' -> byteArrayOf(ESC, 0x5B, 0x43)  // ESC [ C
        'D' -> byteArrayOf(ESC, 0x5B, 0x44)  // ESC [ D
        else -> byteArrayOf()
    }

    private fun ctrlArrowBytes(letter: Char): ByteArray = when (letter) {
        'A' -> byteArrayOf(ESC, 0x5B, 0x31, 0x3B, 0x35, 0x41)  // ESC [ 1;5 A
        'B' -> byteArrayOf(ESC, 0x5B, 0x31, 0x3B, 0x35, 0x42)
        'C' -> byteArrayOf(ESC, 0x5B, 0x31, 0x3B, 0x35, 0x43)
        'D' -> byteArrayOf(ESC, 0x5B, 0x31, 0x3B, 0x35, 0x44)
        else -> byteArrayOf()
    }

    private fun altArrowBytes(letter: Char): ByteArray = when (letter) {
        // Alt+Arrow: ESC ESC [ X (readline 跳词)
        'A' -> byteArrayOf(ESC, ESC, 0x5B, 0x41)
        'B' -> byteArrayOf(ESC, ESC, 0x5B, 0x42)
        'C' -> byteArrayOf(ESC, ESC, 0x5B, 0x43)
        'D' -> byteArrayOf(ESC, ESC, 0x5B, 0x44)
        else -> byteArrayOf()
    }

    private fun onKeyClick(def: KeyDef, v: View) {
        Log.i("ExtraKeys", "click key=${def.key} ctrl=$ctrlDown alt=$altDown")
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val tv = terminalView ?: return

        when (def.key) {
            "ESC" -> writeBytes(tv, byteArrayOf(ESC))
            "TAB" -> writeBytes(tv, byteArrayOf(0x09))
            "UP" -> writeBytes(
                tv,
                when {
                    ctrlDown -> ctrlArrowBytes('A')
                    altDown -> altArrowBytes('A')
                    else -> arrowBytes('A')
                }
            )
            "DOWN" -> writeBytes(
                tv,
                when {
                    ctrlDown -> ctrlArrowBytes('B')
                    altDown -> altArrowBytes('B')
                    else -> arrowBytes('B')
                }
            )
            "RIGHT" -> writeBytes(
                tv,
                when {
                    ctrlDown -> ctrlArrowBytes('C')
                    altDown -> altArrowBytes('C')
                    else -> arrowBytes('C')
                }
            )
            "LEFT" -> writeBytes(
                tv,
                when {
                    ctrlDown -> ctrlArrowBytes('D')
                    altDown -> altArrowBytes('D')
                    else -> arrowBytes('D')
                }
            )
            "HOME" -> writeBytes(tv, byteArrayOf(ESC, 0x5B, 0x48))  // ESC [ H
            "END" -> writeBytes(tv, byteArrayOf(ESC, 0x5B, 0x46))   // ESC [ F
            "PGUP" -> writeBytes(tv, byteArrayOf(ESC, 0x5B, 0x35, 0x7E))  // ESC [ 5 ~
            "PGDN" -> writeBytes(tv, byteArrayOf(ESC, 0x5B, 0x36, 0x7E))  // ESC [ 6 ~
            "/" -> tv.inputCodePoint(0, '/'.code, ctrlDown, altDown)
            "-" -> tv.inputCodePoint(0, '-'.code, ctrlDown, altDown)
            "CTRL", "ALT" -> {
                toggleModifier(def.key)
                return
            }
            else -> {
                val ctrlChar = when (def.key) {
                    "CTRL_C" -> 0x03
                    "CTRL_D" -> 0x04
                    "CTRL_Z" -> 0x1A
                    "CTRL_L" -> 0x0C
                    "CTRL_A" -> 0x01
                    "CTRL_E" -> 0x05
                    "CTRL_K" -> 0x0B
                    else -> -1
                }
                if (ctrlChar >= 0) {
                    writeBytes(tv, byteArrayOf(ctrlChar.toByte()))
                    return
                }
                if (def.key.length == 1) {
                    tv.inputCodePoint(0, def.key[0].code, ctrlDown, altDown)
                }
            }
        }
        // 输入字符后自动释放 ALT；CTRL 保持直到用户再点
        if (altDown && def.key !in listOf("CTRL", "ALT")) {
            altDown = false
            updateModifierVisual()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}