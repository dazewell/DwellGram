package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Build;
import android.os.Looper;
import android.text.Editable;
import android.text.Layout;
import android.text.Spannable;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.widget.EditText;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.spoilers.SpoilerEffect;
import org.telegram.ui.Components.spoilers.SpoilersClickDetector;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

public class EditTextEffects extends EditText {
    private final static int SPOILER_TIMEOUT = 10000;

    private List<SpoilerEffect> spoilers = new ArrayList<>();
    private Stack<SpoilerEffect> spoilersPool = new Stack<>();
    private ArrayList<QuoteSpan.Block> quoteBlocks = new ArrayList<>();
    private boolean isSpoilersRevealed;
    private boolean shouldRevealSpoilersByTouch = true;
    private SpoilersClickDetector clickDetector;
    public boolean suppressOnTextChanged;
    private Path path = new Path();
    private int selStart, selEnd;
    private float lastRippleX, lastRippleY;
    private boolean postedSpoilerTimeout;
    public int quoteColor;

    private AnimatedEmojiSpan.EmojiGroupedSpans animatedEmojiDrawables;
    private ColorFilter animatedEmojiColorFilter;
    public boolean drawAnimatedEmojiDrawables = true;
    private Layout lastLayout = null;
    private int lastTextLength;

    private Runnable spoilerTimeout = () -> {
        postedSpoilerTimeout = false;
        isSpoilersRevealed = false;
        invalidateSpoilers();
        if (spoilers.isEmpty())
            return;

        spoilers.get(0).setOnRippleEndCallback(() -> post(() -> setSpoilersRevealed(false, true)));
        float rad = (float) Math.sqrt(Math.pow(getWidth(), 2) + Math.pow(getHeight(), 2));
        for (SpoilerEffect eff : spoilers) {
            eff.startRipple(lastRippleX, lastRippleY, rad, true);
        }
    };
    private Rect rect = new Rect();
    private boolean clipToPadding;

    public EditTextEffects(Context context) {
        super(context, null, 0, R.style.EditTextNoBackgroundStyle);

        if (Looper.getMainLooper().getThread() == Thread.currentThread()) {
            clickDetector = new SpoilersClickDetector(this, spoilers, this::onSpoilerClicked);
        }
    }

    private void onSpoilerClicked(SpoilerEffect eff, float x, float y) {
        if (isSpoilersRevealed) return;

        lastRippleX = x;
        lastRippleY = y;

        postedSpoilerTimeout = false;
        removeCallbacks(spoilerTimeout);

        setSpoilersRevealed(true, false);
        eff.setOnRippleEndCallback(() -> post(() -> {
            invalidateSpoilers();
            checkSpoilerTimeout();
        }));

        float rad = (float) Math.sqrt(Math.pow(getWidth(), 2) + Math.pow(getHeight(), 2));
        for (SpoilerEffect ef : spoilers)
            ef.startRipple(x, y, rad);
    }

    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        super.onSelectionChanged(selStart, selEnd);

        if (suppressOnTextChanged)
            return;

        this.selStart = selStart;
        this.selEnd = selEnd;

        checkSpoilerTimeout();
    }

    /**
     * Checks for spoiler timeout to be posted
     */
    private void checkSpoilerTimeout() {
        boolean onSpoiler = false;
        CharSequence cs = getLayout() != null ? getLayout().getText() : null;
        if (cs instanceof Spannable) {
            Spannable e = (Spannable) cs;
            TextStyleSpan[] spans = e.getSpans(0, e.length(), TextStyleSpan.class);
            for (TextStyleSpan span : spans) {
                int ss = e.getSpanStart(span), se = e.getSpanEnd(span);
                if (span.isSpoiler()) {
                    if (ss > selStart && se < selEnd || selStart > ss && selStart < se || selEnd > ss && selEnd < se) {
                        onSpoiler = true;
                        removeCallbacks(spoilerTimeout);
                        postedSpoilerTimeout = false;
                        break;
                    }
                }
            }
        }

        if (isSpoilersRevealed && !onSpoiler && !postedSpoilerTimeout) {
            postedSpoilerTimeout = true;
            postDelayed(spoilerTimeout, SPOILER_TIMEOUT);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        removeCallbacks(spoilerTimeout);
        AnimatedEmojiSpan.release(this, animatedEmojiDrawables);
    }

    public void recycleEmojis() {
        AnimatedEmojiSpan.release(this, animatedEmojiDrawables);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateAnimatedEmoji(true);
        invalidateQuotes(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        invalidateEffects();
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        if (!suppressOnTextChanged) {
            invalidateEffects();

            try {
                Layout layout = getLayout();
                if (text instanceof Spannable && layout != null) {
                    int line = layout.getLineForOffset(start);
                    // NekoX: Fix official bug: Mention crash
                    if (start > layout.getText().length())
                        return;
                    int x = (int) layout.getPrimaryHorizontal(start);
                    int y = (int) ((layout.getLineTop(line) + layout.getLineBottom(line)) / 2f);

                    for (SpoilerEffect eff : spoilers) {
                        if (eff.getBounds().contains(x, y)) {
                            int selOffset = lengthAfter - lengthBefore;
                            selStart += selOffset;
                            selEnd += selOffset;
                            onSpoilerClicked(eff, x, y);
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        updateAnimatedEmoji(true);
        invalidateQuotes(true);
        invalidate();
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        if (!suppressOnTextChanged) {
            isSpoilersRevealed = false;
            if (spoilersPool != null) // Constructor check
                spoilersPool.clear();
        }
        super.setText(text, type);
    }

    private int lastTextColor;
    private Integer emojiColor;
    public void setEmojiColor(Integer emojiColor) {
        this.emojiColor = emojiColor;
        animatedEmojiColorFilter = new PorterDuffColorFilter(emojiColor == null ? lastTextColor : emojiColor, PorterDuff.Mode.SRC_IN);
        invalidate();
    }

    @Override
    public void setTextColor(int color) {
        lastTextColor = color;
        super.setTextColor(color);
        animatedEmojiColorFilter = new PorterDuffColorFilter(emojiColor == null ? color : emojiColor, PorterDuff.Mode.SRC_IN);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        invalidateQuotes(false);
    }

    /**
     * Sets if spoilers should be revealed by touch or not
     */
    public void setShouldRevealSpoilersByTouch(boolean shouldRevealSpoilersByTouch) {
        this.shouldRevealSpoilersByTouch = shouldRevealSpoilersByTouch;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (QuoteSpan.onTouch(event, getPaddingTop() - getScrollY(), quoteBlocks, () -> invalidateQuotes(true))) {
            return true;
        }
        boolean detector = false;
        if (shouldRevealSpoilersByTouch && clickDetector != null && clickDetector.onTouchEvent(event)) {
            int act = event.getActionMasked();
            if (act == MotionEvent.ACTION_UP) {
                MotionEvent c = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0, 0, 0);
                super.dispatchTouchEvent(c);
                c.recycle();
            }
            detector = true;
        }
        return super.dispatchTouchEvent(event) || detector;
    }

    /**
     * Sets if spoiler are already revealed or not
     */
    public void setSpoilersRevealed(boolean spoilersRevealed, boolean notifyEffects) {
        isSpoilersRevealed = spoilersRevealed;
        Spannable text = getText();
        if (text != null) {
            TextStyleSpan[] spans = text.getSpans(0, text.length(), TextStyleSpan.class);
            for (TextStyleSpan span : spans) {
                if (span.isSpoiler()) {
                    span.setSpoilerRevealed(spoilersRevealed);
                }
            }
        }
        suppressOnTextChanged = true;
        setText(text, BufferType.EDITABLE);
        setSelection(selStart, selEnd);
        suppressOnTextChanged = false;

        if (notifyEffects) {
            invalidateSpoilers();
        }
    }

    protected float offsetY;

    public void setOffsetY(float offset) {
        this.offsetY = offset;
        invalidate();
    }

    public float getOffsetY() {
        return offsetY;
    }

    private static Boolean allowHackingTextCanvasCache;
    public static boolean allowHackingTextCanvas() {
        if (allowHackingTextCanvasCache == null) {
            allowHackingTextCanvasCache = Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT_WATCH && (
                Build.MANUFACTURER == null ||
                !Build.MANUFACTURER.toLowerCase().contains("honor") &&
                !Build.MANUFACTURER.toLowerCase().contains("huawei") &&
                !Build.MANUFACTURER.toLowerCase().contains("alps") &&
                !Build.MANUFACTURER.toLowerCase().contains("vivo")
            ) && (
                Build.MODEL == null ||
                !Build.MODEL.toLowerCase().contains("mediapad")
            );
        }
        return allowHackingTextCanvasCache;
    }

    public boolean wrapCanvasToFixClipping = allowHackingTextCanvas();
    private NoClipCanvas wrappedCanvas;

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.save();
        if (clipToPadding && getScrollY() != 0) {
            canvas.clipRect(-AndroidUtilities.dp(3), getScrollY() - super.getExtendedPaddingTop() - offsetY, getMeasuredWidth(), getMeasuredHeight() + getScrollY() + super.getExtendedPaddingBottom() - offsetY);
        }

        final int spoilerPaddingLeft = getPaddingLeft();
        final int spoilerOffsetY = spoilerOffsetY();
        if (!spoilers.isEmpty()) {
            path.rewind();
            for (SpoilerEffect eff : spoilers) {
                Rect bounds = eff.getBounds();
                path.addRect(bounds.left + spoilerPaddingLeft, bounds.top + spoilerOffsetY, bounds.right + spoilerPaddingLeft, bounds.bottom + spoilerOffsetY, Path.Direction.CW);
            }
            canvas.clipPath(path, Region.Op.DIFFERENCE);
        }

        invalidateQuotes(false);
        for (int i = 0; i < quoteBlocks.size(); ++i) {
            quoteBlocks.get(i).draw(canvas, 0, getWidth(), quoteColor, 1f, getPaint());
        }
        updateAnimatedEmoji(false);
        if (wrapCanvasToFixClipping) {
            if (wrappedCanvas == null) {
                wrappedCanvas = new NoClipCanvas();
            }
            wrappedCanvas.canvas = canvas;
            super.onDraw(wrappedCanvas);
        } else {
            super.onDraw(canvas);
        }
        if (drawAnimatedEmojiDrawables && animatedEmojiDrawables != null) {
            canvas.save();
            canvas.translate(getPaddingLeft(), 0);
            AnimatedEmojiSpan.drawAnimatedEmojis(canvas, getLayout(), animatedEmojiDrawables, 0, spoilers, computeVerticalScrollOffset() - AndroidUtilities.dp(6), computeVerticalScrollOffset() + computeVerticalScrollExtent(), 0, 1f, animatedEmojiColorFilter);
            canvas.restore();
        }
        drawRevealedSpoilerSelection(canvas, spoilerOffsetY);
        canvas.restore();

        if (!spoilers.isEmpty()) {
            if (spoilers.get(0).hasRipplePath()) {
                canvas.save();
                canvas.clipPath(path);
                path.rewind();

                spoilers.get(0).getRipplePath(path);
                canvas.clipPath(path);
                canvas.translate(0, -getPaddingTop());
                if (wrapCanvasToFixClipping) {
                    if (wrappedCanvas == null) {
                        wrappedCanvas = new NoClipCanvas();
                    }
                    wrappedCanvas.canvas = canvas;
                    super.onDraw(wrappedCanvas);
                } else {
                    super.onDraw(canvas);
                }
                canvas.restore();
            }

            // NagramX: EditTextBoldCursor zeroes mScrollY for the length of this draw, so getScrollY() reads 0 here and
            // every spoiler below the first screen of a scrolled draft was clipped away, text and all.
            final int spoilerScrollY = computeVerticalScrollOffset();
            rect.set(0, (int) (spoilerScrollY - super.getExtendedPaddingTop() - offsetY), getWidth(), (int) (getMeasuredHeight() + spoilerScrollY + super.getExtendedPaddingBottom() - offsetY));
            canvas.save();
            canvas.clipRect(rect);
            canvas.translate(spoilerPaddingLeft, spoilerOffsetY);
            for (SpoilerEffect eff : spoilers) {
                Rect b = eff.getBounds();
                if (rect.top <= b.bottom && rect.bottom >= b.top || b.top <= rect.bottom && b.bottom >= rect.top) {
                    eff.setColor(eff.insideQuote ? quoteColor : getPaint().getColor());
                    eff.draw(canvas);
                }
            }
            canvas.restore();
        }
    }

    // NagramX: TextView draws a layout shorter than its box lower down, by its gravity. Spoiler bounds are layout
    // coordinates, so in the one-line composer they sat above the text and left the bottom of the line bare.
    private int spoilerOffsetY() {
        final Layout layout = getLayout();
        final int gravity = getGravity() & android.view.Gravity.VERTICAL_GRAVITY_MASK;
        if (layout == null || gravity == android.view.Gravity.TOP) {
            return 0;
        }
        final int free = getMeasuredHeight() - super.getExtendedPaddingTop() - super.getExtendedPaddingBottom() - layout.getHeight();
        return free <= 0 ? 0 : gravity == android.view.Gravity.BOTTOM ? free : free >> 1;
    }

    // NagramX: a revealed spoiler paints a background behind its text, over the selection highlight TextView
    // draws under the text, so a selection inside one could not be seen. Draw that part of the highlight on top.
    private Path spoilerSelectionPath;
    private android.graphics.Paint spoilerSelectionPaint;
    private void drawRevealedSpoilerSelection(Canvas canvas, int offsetY) {
        final Layout layout = getLayout();
        final Editable text = getText();
        final int start = Math.min(getSelectionStart(), getSelectionEnd());
        final int end = Math.max(getSelectionStart(), getSelectionEnd());
        if (!isSpoilersRevealed || !isFocused() || layout == null || text == null || start < 0 || start == end) {
            return;
        }
        if (spoilerSelectionPath == null) {
            spoilerSelectionPath = new Path();
            spoilerSelectionPaint = new android.graphics.Paint();
        }
        spoilerSelectionPaint.setColor(getHighlightColor());
        canvas.save();
        canvas.translate(getPaddingLeft(), offsetY);
        for (TextStyleSpan span : text.getSpans(start, end, TextStyleSpan.class)) {
            final int from = Math.max(start, text.getSpanStart(span));
            final int to = Math.min(end, text.getSpanEnd(span));
            if (span.isSpoiler() && from < to) {
                layout.getSelectionPath(from, to, spoilerSelectionPath);
                canvas.drawPath(spoilerSelectionPath, spoilerSelectionPaint);
            }
        }
        canvas.restore();
    }

    public void updateAnimatedEmoji(boolean force) {
        if (!drawAnimatedEmojiDrawables) {
            return;
        }
        int newTextLength = (getLayout() == null || getLayout().getText() == null) ? 0 : getLayout().getText().length();
        if (force || lastLayout != getLayout() || lastTextLength != newTextLength) {
            clampAnimatedEmojiScale();
            animatedEmojiDrawables = AnimatedEmojiSpan.update(emojiCacheType(), this, animatedEmojiDrawables, getLayout());
            lastLayout = getLayout();
            lastTextLength = newTextLength;
        }
    }

    // NagramX: fields that leave no vertical slack between lines override this so animated emoji, which
    // are drawn 20% larger than the line they are measured into, don't bleed onto the neighbouring line.
    protected float maxAnimatedEmojiScale() {
        return Float.MAX_VALUE;
    }

    private void clampAnimatedEmojiScale() {
        final float max = maxAnimatedEmojiScale();
        if (max == Float.MAX_VALUE) {
            return;
        }
        Editable text = getText();
        if (text == null) {
            return;
        }
        for (AnimatedEmojiSpan span : text.getSpans(0, text.length(), AnimatedEmojiSpan.class)) {
            span.clampScale(max);
        }
    }

    protected int emojiCacheType() {
        return AnimatedEmojiDrawable.getCacheTypeForEnterView();
    }

    private int lastText2Length;
    private Layout lastQuoteLayout;
    private int lastQuoteLayoutWidth;
    private int lastQuoteLineCount;
    private int quoteUpdatesTries;
    private boolean[] quoteUpdateLayout;
    private boolean quoteBlocksUpdating;
    private boolean editedWhileQuoteUpdating;

    public void invalidateQuotes(boolean force) {
        if (quoteBlocksUpdating) {
            editedWhileQuoteUpdating = true;
            return;
        }
        Layout layout = getLayout();
        int newTextLength = (layout == null || layout.getText() == null) ? 0 : layout.getText().length();
        int layoutWidth = layout == null ? 0 : layout.getWidth();
        int lineCount = layout == null ? 0 : layout.getLineCount();
        // NagramX: quote blocks cache line geometry, so reflow without a text edit must re-arm them too.
        if (force || lastText2Length != newTextLength || lastQuoteLayout != layout
                || lastQuoteLayoutWidth != layoutWidth || lastQuoteLineCount != lineCount) {
            quoteUpdatesTries = 2;
            lastText2Length = newTextLength;
        }
        if (quoteUpdatesTries > 0) {
            if (quoteUpdateLayout == null) {
                quoteUpdateLayout = new boolean[1];
            }
            quoteUpdateLayout[0] = false;
            editedWhileQuoteUpdating = false;
            quoteBlocksUpdating = true;
            quoteBlocks = QuoteSpan.updateQuoteBlocks(this, getLayout(), quoteBlocks, quoteUpdateLayout);
            if (editedWhileQuoteUpdating) {
                quoteBlocks = QuoteSpan.updateQuoteBlocks(this, getLayout(), quoteBlocks, quoteUpdateLayout);
            }
            quoteBlocksUpdating = false;
            editedWhileQuoteUpdating = false;
            if (quoteUpdateLayout[0]) {
                resetFontMetricsCache();
            }
            quoteUpdatesTries--;
            layout = getLayout();
            lastQuoteLayout = layout;
            lastText2Length = (layout == null || layout.getText() == null) ? 0 : layout.getText().length();
            lastQuoteLayoutWidth = layout == null ? 0 : layout.getWidth();
            lastQuoteLineCount = layout == null ? 0 : layout.getLineCount();
        }
    }

    // really dirty workaround to reset fontmetrics cache (lineheightspan.chooseheight works only when text is inserted into the respected line)
    protected void resetFontMetricsCache() {
        float originalTextSize = getTextSize();
        setTextSize(TypedValue.COMPLEX_UNIT_PX, originalTextSize + 1);
        setTextSize(TypedValue.COMPLEX_UNIT_PX, originalTextSize);
    }

    public void invalidateEffects() {
        Editable text = getText();
        if (text != null) {
            for (TextStyleSpan span : text.getSpans(0, text.length(), TextStyleSpan.class)) {
                if (span.isSpoiler()) {
                    span.setSpoilerRevealed(isSpoilersRevealed);
                }
            }
        }
        invalidateSpoilers();
    }

    protected void invalidateSpoilers() {
        if (spoilers == null) return; // A null-check for super constructor, because it calls onTextChanged
        spoilersPool.addAll(spoilers);
        spoilers.clear();

        if (isSpoilersRevealed) {
            invalidate();
            return;
        }

        Layout layout = getLayout();
        if (layout != null && layout.getText() instanceof Spannable) {
            if (drawAnimatedEmojiDrawables && animatedEmojiDrawables != null) {
                animatedEmojiDrawables.recordPositions(false);
            }
            SpoilerEffect.addSpoilers(this, spoilersPool, spoilers, quoteBlocks);
            if (drawAnimatedEmojiDrawables && animatedEmojiDrawables != null) {
                animatedEmojiDrawables.recordPositions(true);
            }
        }
        invalidate();
    }

    public void setClipToPadding(boolean clipToPadding) {
        this.clipToPadding = clipToPadding;
    }

    public CharSequence getTextToUse() {
        return QuoteSpan.stripNewlineHacks(getText());
    }
}
