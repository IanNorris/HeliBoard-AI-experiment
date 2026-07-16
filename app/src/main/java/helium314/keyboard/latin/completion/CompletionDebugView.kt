// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.HorizontalScrollView
import android.widget.TextView
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

/**
 * A compact developer overlay that shows what the last completion generation actually did: the
 * prompt handed to the model, each candidate with its confidence score and per-candidate token count
 * and time, and the aggregate prompt tokens / prefill vs total ms / tokens-per-second. It exists to
 * make the latency and context tunable empirically (see [CompletionDebug]). Shown only when the
 * debug setting is on; it renders whatever snapshot it is handed by the IME.
 */
class CompletionDebugView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    private val text = TextView(context).apply {
        setTypeface(Typeface.MONOSPACE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setTextColor(Settings.getValues().mColors.get(ColorType.KEY_TEXT))
    }

    init {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(Color.argb(0x33, 0, 0, 0))
        addView(text)
    }

    /** Render the latest generation snapshot, or a waiting message if there is none yet. */
    fun render(snapshot: CompletionDebug.Snapshot?) {
        if (snapshot == null) {
            text.text = "completion debug: waiting for a generation…"
            return
        }
        val sb = StringBuilder()
        sb.append(snapshot.source)
            .append("  prompt=").append(snapshot.promptTokens).append("tok")
            .append("  prefill=").append(snapshot.prefillMs).append("ms")
            .append("  total=").append(snapshot.totalMs).append("ms")
            .append("  ").append(String.format("%.1f", snapshot.tokensPerSecond)).append(" tok/s\n")
        sb.append("in: ").append(snapshot.prompt.replace("\n", "\\n")).append('\n')
        snapshot.candidates.forEachIndexed { i, c ->
            sb.append(i + 1).append(" [")
                .append(String.format("%.2f", c.score)).append(' ')
                .append(c.genTokens).append("t ")
                .append(c.genMs).append("ms] ")
                .append(c.text)
            if (i < snapshot.candidates.lastIndex) sb.append('\n')
        }
        text.text = sb.toString()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
