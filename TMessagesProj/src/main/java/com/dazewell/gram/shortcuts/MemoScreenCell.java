package com.dazewell.gram.shortcuts;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.dazewell.gram.videonote.VideoNoteShortcut;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadioButton;

import java.util.function.IntConsumer;

/**
 * Launcher shortcuts -> the video memo's screen: two phone mocks side by side, the chat recorder and the camera memo's
 * plain camera screen, each with a radio. Shaped like upstream's ChatListCell. The chat mock follows the theme, the camera
 * one is fixed dark, as CameraMemoCover is.
 */
@SuppressLint("ViewConstructor")
public class MemoScreenCell extends LinearLayout {

    private static final int PHONE_WIDTH = 64;
    private static final int PHONE_HEIGHT = 120;
    private static final int CARD_HEIGHT = PHONE_HEIGHT + 24;

    private final Option[] options = new Option[2];

    public MemoScreenCell(Context context, IntConsumer onSelect) {
        super(context);
        setOrientation(HORIZONTAL);
        setPadding(dp(21), dp(10), dp(21), 0);
        for (int i = 0; i < options.length; i++) {
            int screen = i == 0 ? VideoNoteShortcut.SCREEN_CHAT : VideoNoteShortcut.SCREEN_CAMERA;
            Option option = options[i] = new Option(context, screen);
            addView(option, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 0.5f, i == 1 ? 10 : 0, 0, 0, 0));
            option.setOnClickListener(v -> {
                if (option.radio.isChecked()) {
                    return;
                }
                for (Option o : options) {
                    o.radio.setChecked(o == option, true);
                }
                onSelect.accept(screen);
            });
        }
    }

    public void update() {
        int screen = VideoNoteShortcut.getScreen();
        for (Option o : options) {
            o.radio.setChecked(o.screen == screen, false);
            o.invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(10 + CARD_HEIGHT + 34), MeasureSpec.EXACTLY));
    }

    private static class Option extends FrameLayout {

        private final int screen;
        private final RadioButton radio;
        private final RectF rect = new RectF();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint tinyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path clip = new android.graphics.Path();
        private final RectF arc = new RectF();
        private LinearGradient shade; // the round camera's or the viewfinder's, built for the current size
        private final String label;

        Option(Context context, int screen) {
            super(context);
            this.screen = screen;
            label = getString(screen == VideoNoteShortcut.SCREEN_CAMERA ? R.string.VideoNoteShortcutScreenCamera : R.string.VideoNoteShortcutScreenChat);
            setWillNotDraw(false);
            labelPaint.setTextSize(dp(13));
            tinyPaint.setTextSize(dp(7));
            stroke.setStyle(Paint.Style.STROKE);
            radio = new RadioButton(context) {
                @Override
                public void invalidate() {
                    super.invalidate();
                    Option.this.invalidate();
                }
            };
            radio.setSize(dp(20));
            radio.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); // the option speaks for itself
            addView(radio, LayoutHelper.createFrame(22, 22, Gravity.RIGHT | Gravity.TOP, 0, 8, 8, 0));
            radio.setChecked(VideoNoteShortcut.getScreen() == screen, false);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            shade = null; // its geometry moved
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float progress = radio.getProgress();
            radio.setColor(Theme.getColor(Theme.key_radioBackground), Theme.getColor(Theme.key_radioBackgroundChecked));

            // The card: a faint fill, and the accent outline fading in as the radio checks
            int track = Theme.getColor(Theme.key_switchTrack);
            rect.set(0, 0, getMeasuredWidth(), dp(CARD_HEIGHT));
            paint.setColor(withAlpha(track, 31));
            canvas.drawRoundRect(rect, dp(10), dp(10), paint);
            if (progress > 0) {
                stroke.setStrokeWidth(dp(2));
                stroke.setColor(withAlpha(Theme.getColor(Theme.key_radioBackgroundChecked), (int) (255 * progress)));
                rect.inset(dp(1), dp(1));
                canvas.drawRoundRect(rect, dp(9), dp(9), stroke);
            }

            float left = (getMeasuredWidth() - dp(PHONE_WIDTH)) / 2f;
            float top = dp(12);
            rect.set(left, top, left + dp(PHONE_WIDTH), top + dp(PHONE_HEIGHT));
            canvas.save();
            clip.reset();
            clip.addRoundRect(rect, dp(8), dp(8), android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            if (screen == VideoNoteShortcut.SCREEN_CAMERA) {
                drawCamera(canvas, rect);
            } else {
                drawChat(canvas, rect);
            }
            canvas.restore();
            stroke.setStrokeWidth(dp(1.5f));
            stroke.setColor(withAlpha(track, 140));
            canvas.drawRoundRect(rect, dp(8), dp(8), stroke);

            labelPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            canvas.drawText(label, (getMeasuredWidth() - labelPaint.measureText(label)) / 2f, dp(CARD_HEIGHT + 22), labelPaint);
        }

        // The chat with the round recorder up: header, a few bubbles, the dimmed chat behind the circle, the record bar
        private void drawChat(Canvas canvas, RectF phone) {
            float l = phone.left, t = phone.top, r = phone.right, b = phone.bottom, w = phone.width();
            paint.setColor(Theme.getColor(Theme.key_chat_wallpaper));
            canvas.drawRect(phone, paint);

            paint.setColor(Theme.getColor(Theme.key_chat_inBubble));
            canvas.drawRoundRect(l + dp(5), t + dp(24), l + w * 0.62f, t + dp(36), dp(4), dp(4), paint);
            canvas.drawRoundRect(l + dp(5), t + dp(62), l + w * 0.55f, t + dp(72), dp(4), dp(4), paint);
            paint.setColor(Theme.getColor(Theme.key_chat_outBubble));
            canvas.drawRoundRect(r - w * 0.6f, t + dp(40), r - dp(5), t + dp(56), dp(4), dp(4), paint);

            paint.setColor(Theme.getColor(Theme.key_actionBarDefault));
            canvas.drawRect(l, t, r, t + dp(16), paint);
            paint.setColor(withAlpha(Theme.getColor(Theme.key_actionBarDefaultTitle), 200));
            canvas.drawCircle(l + dp(10), t + dp(8), dp(4), paint);
            canvas.drawRoundRect(l + dp(18), t + dp(6), l + w * 0.6f, t + dp(10), dp(2), dp(2), paint);

            // InstantCameraView dims everything behind its circle
            paint.setColor(0x7f000000);
            canvas.drawRect(l, t + dp(16), r, b - dp(16), paint);

            float cx = phone.centerX(), cy = t + dp(16) + (phone.height() - dp(32)) / 2f, radius = w * 0.36f;
            paint.setShader(shade(cx, cy - radius, cx, cy + radius, 0xff6b7380, 0xff2f343b));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setShader(null);
            stroke.setStrokeWidth(dp(1.5f));
            stroke.setColor(0xffffffff);
            arc.set(cx - radius - dp(2), cy - radius - dp(2), cx + radius + dp(2), cy + radius + dp(2));
            canvas.drawArc(arc, -90, 110, false, stroke);

            paint.setColor(Theme.getColor(Theme.key_chat_messagePanelBackground));
            canvas.drawRect(l, b - dp(16), r, b, paint);
            paint.setColor(0xffff3b30);
            canvas.drawCircle(l + dp(7), b - dp(8), dp(2.5f), paint);
            tinyPaint.setColor(Theme.getColor(Theme.key_chat_messagePanelText));
            canvas.drawText("0:12", l + dp(12), b - dp(5.5f), tinyPaint);
            paint.setColor(Theme.getColor(Theme.key_chat_messagePanelVoiceBackground));
            canvas.drawCircle(r - dp(9), b - dp(8), dp(6), paint);
        }

        // CameraMemoCover in small: black, the status line over a viewfinder, a grid whose three middle rows are the crop
        // the round clip sends, and the shutter between two tonal buttons
        private void drawCamera(Canvas canvas, RectF phone) {
            float l = phone.left, t = phone.top, r = phone.right, b = phone.bottom, w = phone.width();
            paint.setColor(0xff000000);
            canvas.drawRect(phone, paint);

            float vfTop = t + dp(14), vfBottom = b - dp(30);
            paint.setShader(shade(l, vfTop, r, vfBottom, 0xff59606b, 0xff23272d));
            canvas.drawRect(l, vfTop, r, vfBottom, paint);
            paint.setShader(null);

            float cell = w / 3f;
            float cropTop = (vfTop + vfBottom) / 2f - cell * 1.5f, cropBottom = cropTop + cell * 3;
            stroke.setStrokeWidth(dp(0.5f));
            stroke.setColor(0x80ffffff);
            for (int i = 1; i < 3; i++) {
                canvas.drawLine(l + cell * i, vfTop, l + cell * i, vfBottom, stroke);
            }
            for (float y = cropTop; y > vfTop; y -= cell) {
                canvas.drawLine(l, y, r, y, stroke);
            }
            for (float y = cropTop + cell; y < vfBottom; y += cell) {
                canvas.drawLine(l, y, r, y, stroke);
            }
            stroke.setStrokeWidth(dp(1));
            stroke.setColor(0xe6ffffff);
            canvas.drawLine(l, cropTop, r, cropTop, stroke);
            canvas.drawLine(l, cropBottom, r, cropBottom, stroke);

            String time = "0:12";
            tinyPaint.setColor(0xffffffff);
            float textWidth = tinyPaint.measureText(time);
            float x = phone.centerX() - (textWidth + dp(6)) / 2f;
            paint.setColor(0xffff3b30);
            canvas.drawCircle(x + dp(2), t + dp(7), dp(2), paint);
            canvas.drawText(time, x + dp(6), t + dp(9.5f), tinyPaint);

            float cy = b - dp(15);
            paint.setColor(0xff2b2e33);
            canvas.drawCircle(phone.centerX() - dp(20), cy, dp(5), paint);
            canvas.drawCircle(phone.centerX() + dp(20), cy, dp(5), paint);
            stroke.setStrokeWidth(dp(1.5f));
            stroke.setColor(0xffffffff);
            canvas.drawCircle(phone.centerX(), cy, dp(9), stroke);
            paint.setColor(0xffff3b30);
            canvas.drawRoundRect(phone.centerX() - dp(3.5f), cy - dp(3.5f), phone.centerX() + dp(3.5f), cy + dp(3.5f), dp(1.5f), dp(1.5f), paint);
        }

        // Each option draws one gradient, with fixed colours and a geometry that only moves on resize
        private LinearGradient shade(float x0, float y0, float x1, float y1, int from, int to) {
            if (shade == null) {
                shade = new LinearGradient(x0, y0, x1, y1, from, to, Shader.TileMode.CLAMP);
            }
            return shade;
        }

        private static int withAlpha(int color, int alpha) {
            return (color & 0x00ffffff) | (Math.max(0, Math.min(255, alpha)) << 24);
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(RadioButton.class.getName());
            info.setCheckable(true);
            info.setChecked(radio.isChecked());
            info.setContentDescription(label);
        }
    }
}
