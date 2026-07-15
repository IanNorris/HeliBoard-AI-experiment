// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

/**
 * A horizontally scrollable strip that shows multi-word completion candidates, one per row, with
 * every word individually tappable so the user can "scrub" and accept a continuation up to any word
 * (tapping "last" in "good time last week" commits "good time last").
 *
 * This is the view scaffold for the new completion section that sits above the toolbar / suggestion
 * strip. It is intentionally self-contained and free of the IME's input plumbing: it only renders
 * candidates it is given and reports which word the user tapped through [listener]. Wiring it into
 * the input view hierarchy and feeding it from the [CompletionEngine] is done by the IME layer.
 *
 * Styling here is deliberately minimal; it should later adopt the active keyboard theme colours the
 * same way [helium314.keyboard.latin.suggestions.SuggestionStripView] does.
 */
class CompletionStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    /** Notified when the user taps a word, requesting acceptance up to [wordIndex] (inclusive). */
    fun interface Listener {
        fun onCompletionWordAccepted(candidate: CompletionCandidate, wordIndex: Int)
    }

    var listener: Listener? = null

    private val rows = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    init {
        isFillViewport = true
        addView(rows)
        Settings.getValues().mColors.setBackground(this, ColorType.STRIP_BACKGROUND)
    }

    /**
     * Replace the displayed candidates. Words whose prefix the user has already typed are shown
     * dimmed as a hint of what tapping will keep; everything is tappable. Visibility of the whole
     * strip is controlled by the host (to reserve a stable row), so this does not hide itself.
     */
    fun setCandidates(candidates: List<CompletionCandidate>, currentPrefix: String) {
        rows.removeAllViews()
        for (candidate in candidates) {
            rows.addView(buildRow(candidate, currentPrefix))
        }
    }

    fun clear() = setCandidates(emptyList(), "")

    private fun buildRow(candidate: CompletionCandidate, currentPrefix: String): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        candidate.words.forEachIndexed { index, word ->
            row.addView(buildWord(candidate, index, word, currentPrefix))
        }
        return row
    }

    private fun buildWord(candidate: CompletionCandidate, index: Int, word: String, currentPrefix: String): TextView =
        TextView(context).apply {
            text = word
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            val padH = dp(10)
            val padV = dp(8)
            setPadding(padH, padV, padH, padV)
            isClickable = true
            val colors = Settings.getValues().mColors
            // the first word is the completion of the in-progress word: highlight it; look-ahead words are dimmer
            setTextColor(colors.get(if (index == 0) ColorType.SUGGESTION_VALID_WORD else ColorType.SUGGESTION_TYPED_WORD))
            setOnClickListener { listener?.onCompletionWordAccepted(candidate, index) }
        }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
