// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A scratch text field for trying out the keyboard. Reached from the [helium314.keyboard.latin.utils.ToolbarKey.TEST]
 * toolbar key. Its whole reason to exist is that some fields (e.g. the settings search box) set
 * [InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS], which suppresses suggestions/autocorrect/multi-word
 * completion, so you cannot feel the real typing experience while tweaking settings. This field
 * deliberately enables suggestions and autocorrect so what you see here matches normal typing.
 */
class TestInputActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }

        val hint = TextView(this).apply {
            setText(R.string.test_field_hint)
            setPadding(0, 0, 0, dp(8))
        }

        val field = EditText(this).apply {
            id = View.generateViewId()
            // suggestions + autocorrect ON (crucially NOT TYPE_TEXT_FLAG_NO_SUGGESTIONS) so the
            // completion/suggestion behaviour matches real text fields
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            gravity = Gravity.TOP or Gravity.START
            setHint(R.string.test_field_placeholder)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        val clear = Button(this).apply {
            setText(R.string.test_field_clear)
            setOnClickListener { field.setText("") }
        }

        root.addView(hint)
        root.addView(field)
        root.addView(clear)
        setContentView(root)

        field.requestFocus()
        field.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
