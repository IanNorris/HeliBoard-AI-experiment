// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

/**
 * The multi-word completion panel that sits above the suggestion strip.
 *
 * It shows up to [MAX_ROWS] candidates, one per row. Each word is an individually tappable chip:
 * tapping a word accepts the continuation up to and including that word (tapping "last" in
 * "good time last week" commits "good time last"). Long candidates are NOT wrapped - each row is
 * its own horizontally scrollable strip, so the user can scroll a long continuation left/right
 * without it eating vertical space, and the panel height stays fixed.
 *
 * A prominent divider separates the words. The panel's overall visibility is owned by the host
 * (LatinIME, driven by the toolbar toggle button), which also reads getHeight() for window insets;
 * this view only lays out its fixed [MAX_ROWS]-row height and renders whatever candidates it is given.
 */
class CompletionStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Notified when the user taps a word, requesting acceptance up to [wordIndex] (inclusive). */
    fun interface Listener {
        fun onCompletionWordAccepted(candidate: CompletionCandidate, wordIndex: Int)
    }

    var listener: Listener? = null

    /** Height of one suggestion row; the panel reserves [MAX_ROWS] of these. */
    private val rowHeight = resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_height)

    init {
        orientation = VERTICAL
        Settings.getValues().mColors.setBackground(this, ColorType.STRIP_BACKGROUND)
    }

    // Reserve a stable height of MAX_ROWS rows so the panel doesn't jitter the IME window as the
    // number of candidates changes; each row scrolls horizontally within this fixed area.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(rowHeight * MAX_ROWS, MeasureSpec.EXACTLY))
    }

    /**
     * Replace the displayed candidates. The first word of each candidate completes the in-progress
     * word; everything is tappable. Visibility of the whole panel is controlled by the host, so this
     * does not hide itself.
     */
    fun setCandidates(candidates: List<CompletionCandidate>, currentPrefix: String) {
        removeAllViews()
        if (candidates.isEmpty()) {
            addView(buildPlaceholderRow())
            return
        }
        for (candidate in candidates.take(MAX_ROWS)) {
            addView(buildRow(candidate))
        }
    }

    fun clear() = setCandidates(emptyList(), "")

    /** Dim hint shown when there are no candidates, so the reserved panel doesn't look broken. */
    private fun buildPlaceholderRow(): View =
        TextView(context).apply {
            text = resources.getString(R.string.completion_strip_placeholder)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, rowHeight)
            val colors = Settings.getValues().mColors
            setTextColor(ColorUtils.setAlphaComponent(colors.get(ColorType.SUGGESTION_TYPED_WORD), 0x80))
        }

    /** One candidate as an independently horizontally-scrollable row of word chips + dividers. */
    private fun buildRow(candidate: CompletionCandidate): View {
        val inner = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
        }
        candidate.words.forEachIndexed { index, word ->
            if (index > 0) inner.addView(buildDivider())
            inner.addView(buildWord(candidate, index, word))
        }
        return HorizontalScrollView(context).apply {
            isFillViewport = false
            overScrollMode = OVER_SCROLL_NEVER
            isHorizontalScrollBarEnabled = false
            addView(inner)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, rowHeight)
        }
    }

    /** A prominent vertical separator between words. */
    private fun buildDivider(): View =
        View(context).apply {
            val colors = Settings.getValues().mColors
            setBackgroundColor(ColorUtils.setAlphaComponent(colors.get(ColorType.SUGGESTION_TYPED_WORD), 0x99))
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(22)).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(2)
                marginEnd = dp(2)
            }
        }

    private fun buildWord(candidate: CompletionCandidate, index: Int, word: String): TextView =
        TextView(context).apply {
            text = word
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            val colors = Settings.getValues().mColors
            // first word completes the in-progress word: highlight it; look-ahead words are dimmer
            setTextColor(colors.get(if (index == 0) ColorType.SUGGESTION_VALID_WORD else ColorType.SUGGESTION_TYPED_WORD))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT
            )
            setOnClickListener { listener?.onCompletionWordAccepted(candidate, index) }
        }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    companion object {
        /** Number of candidate rows the panel reserves and shows. */
        const val MAX_ROWS = 3
    }
}
