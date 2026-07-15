// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.completion

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import kotlin.math.max

/**
 * The multi-word completion panel that sits above the suggestion strip.
 *
 * Candidates are shown as rows of individually tappable word "chips": tapping a word accepts the
 * continuation up to and including that word (tapping "last" in "good time last week" commits
 * "good time last"). Each word is a distinct chip so it reads as a per-word button rather than an
 * all-or-nothing line. A long candidate wraps its chips onto further lines (flowing to fill the
 * width) instead of scrolling off-screen.
 *
 * The panel is collapsible via an up/down chevron (like the suggestion strip's toolbar-expand key):
 * collapsed it is a single row showing just the best candidate to save screen space; expanded it
 * shows every candidate, wrapping as needed, up to [MAX_EXPANDED_ROWS] rows (then scrolls). The
 * expanded/collapsed choice is remembered in preferences. The host controls overall visibility and
 * reads getHeight() for window insets, so this view only decides its own measured height.
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

    /** Height of one suggestion row; the panel reserves multiples of this. */
    private val rowHeight = resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_height)

    private var expanded = context.prefs()
        .getBoolean(Settings.PREF_COMPLETION_PANEL_EXPANDED, Defaults.PREF_COMPLETION_PANEL_EXPANDED)

    private var candidates: List<CompletionCandidate> = emptyList()
    private var currentPrefix: String = ""

    // Collapsed header: the best candidate on one horizontally-scrollable line, plus the chevron.
    private val collapsedContent = HorizontalScrollView(context).apply {
        isFillViewport = false
        overScrollMode = OVER_SCROLL_NEVER
        layoutParams = LayoutParams(0, rowHeight, 1f)
    }

    // Expanded content: all candidates (each flow-wrapped), vertically scrollable if tall.
    private val expandedRows = LinearLayout(context).apply {
        orientation = VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }
    private val expandedScroll = ScrollView(context).apply {
        overScrollMode = OVER_SCROLL_NEVER
        addView(expandedRows)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
    }

    private val toggleButton = ImageButton(context).apply {
        setBackgroundColor(0)
        scaleType = ImageView.ScaleType.CENTER
        layoutParams = LayoutParams(rowHeight, rowHeight)
        setOnClickListener { setExpanded(!expanded) }
    }

    // Header row holds the collapsed preview (or, when expanded, the first line still) and the chevron.
    private val header = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, rowHeight)
        addView(collapsedContent)
        addView(toggleButton)
    }

    init {
        orientation = VERTICAL
        addView(header)
        addView(expandedScroll)
        Settings.getValues().mColors.setBackground(this, ColorType.STRIP_BACKGROUND)
        applyExpandedState()
    }

    private fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        context.prefs().edit { putBoolean(Settings.PREF_COMPLETION_PANEL_EXPANDED, value) }
        applyExpandedState()
        render()
        requestLayout()
    }

    private fun applyExpandedState() {
        expandedScroll.visibility = if (expanded) View.VISIBLE else View.GONE
        toggleButton.setImageResource(if (expanded) R.drawable.ic_dpad_down else R.drawable.ic_dpad_up)
        toggleButton.setColorFilter(Settings.getValues().mColors.get(ColorType.TOOL_BAR_EXPAND_KEY))
        toggleButton.contentDescription = resources.getString(
            if (expanded) R.string.completion_panel_collapse else R.string.completion_panel_expand
        )
    }

    // Height = one header row when collapsed; header + wrapped content (capped) when expanded.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!expanded) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY))
            return
        }
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val innerWidth = width - paddingLeft - paddingRight
        var contentHeight = 0
        val wSpec = MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.EXACTLY)
        for (i in 0 until expandedRows.childCount) {
            val child = expandedRows.getChildAt(i)
            child.measure(wSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            contentHeight += child.measuredHeight
        }
        val maxContent = rowHeight * (MAX_EXPANDED_ROWS - 1) // minus the header row
        val total = rowHeight + contentHeight.coerceAtMost(maxContent)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(total, MeasureSpec.EXACTLY))
    }

    /**
     * Replace the displayed candidates. Words whose prefix the user has already typed are the first
     * chip of each candidate; everything is tappable. Visibility of the whole panel is controlled by
     * the host (to reserve a stable row), so this does not hide itself.
     */
    fun setCandidates(candidates: List<CompletionCandidate>, currentPrefix: String) {
        this.candidates = candidates
        this.currentPrefix = currentPrefix
        render()
    }

    fun clear() = setCandidates(emptyList(), "")

    private fun render() {
        // collapsed line: the best candidate (or a placeholder)
        collapsedContent.removeAllViews()
        if (candidates.isEmpty()) {
            collapsedContent.addView(buildPlaceholder())
        } else {
            collapsedContent.addView(buildFlow(candidates.first(), singleLine = true))
        }
        // expanded content: every candidate, each flow-wrapped across lines
        expandedRows.removeAllViews()
        if (expanded) {
            if (candidates.isEmpty()) {
                expandedRows.addView(buildPlaceholder())
            } else {
                for (candidate in candidates) {
                    expandedRows.addView(buildFlow(candidate, singleLine = false))
                }
            }
        }
    }

    /** Dim hint shown when there are no candidates, so the reserved panel doesn't look broken. */
    private fun buildPlaceholder(): TextView =
        TextView(context).apply {
            text = resources.getString(R.string.completion_strip_placeholder)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            val colors = Settings.getValues().mColors
            setTextColor(ColorUtils.setAlphaComponent(colors.get(ColorType.SUGGESTION_TYPED_WORD), 0x80))
        }

    /**
     * Build the chips for [candidate]. When [singleLine] the chips are laid out horizontally (for the
     * collapsed preview inside a horizontal scroller); otherwise they flow onto multiple lines.
     */
    private fun buildFlow(candidate: CompletionCandidate, singleLine: Boolean): ViewGroup {
        val container: ViewGroup = if (singleLine) {
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        } else {
            FlowLayout(context, hGap = dp(6), vGap = dp(6)).apply {
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
        }
        candidate.words.forEachIndexed { index, word ->
            container.addView(buildChip(candidate, index, word))
        }
        return container
    }

    private fun buildChip(candidate: CompletionCandidate, index: Int, word: String): TextView =
        TextView(context).apply {
            text = word
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true
            val colors = Settings.getValues().mColors
            // chip background makes it read as an individual button (not part of an all-or-nothing line)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(colors.get(ColorType.MORE_SUGGESTIONS_WORD_BACKGROUND))
            }
            // first word completes the in-progress word: highlight it; look-ahead words are dimmer
            setTextColor(colors.get(if (index == 0) ColorType.SUGGESTION_VALID_WORD else ColorType.SUGGESTION_TYPED_WORD))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (index > 0) lp.leftMargin = dp(6)
            layoutParams = lp
            setOnClickListener { listener?.onCompletionWordAccepted(candidate, index) }
        }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    /** A simple left-to-right flow container that wraps children onto new lines when they overflow. */
    private class FlowLayout(
        context: Context,
        private val hGap: Int,
        private val vGap: Int,
    ) : ViewGroup(context) {

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val limit = width - paddingRight
            var x = paddingLeft
            var y = paddingTop
            var lineHeight = 0
            val childWSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST)
            val childHSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE) continue
                child.measure(childWSpec, childHSpec)
                val cw = child.measuredWidth
                val ch = child.measuredHeight
                if (x > paddingLeft && x + cw > limit) { // wrap to next line
                    x = paddingLeft
                    y += lineHeight + vGap
                    lineHeight = 0
                }
                x += cw + hGap
                lineHeight = max(lineHeight, ch)
            }
            val totalHeight = y + lineHeight + paddingBottom
            setMeasuredDimension(width, resolveSize(totalHeight, heightMeasureSpec))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val limit = width - paddingRight
            var x = paddingLeft
            var y = paddingTop
            var lineHeight = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE) continue
                val cw = child.measuredWidth
                val ch = child.measuredHeight
                if (x > paddingLeft && x + cw > limit) {
                    x = paddingLeft
                    y += lineHeight + vGap
                    lineHeight = 0
                }
                child.layout(x, y, x + cw, y + ch)
                x += cw + hGap
                lineHeight = max(lineHeight, ch)
            }
        }
    }

    companion object {
        /** Max rows the expanded panel occupies before it scrolls (includes the header row). */
        const val MAX_EXPANDED_ROWS = 5
    }
}
