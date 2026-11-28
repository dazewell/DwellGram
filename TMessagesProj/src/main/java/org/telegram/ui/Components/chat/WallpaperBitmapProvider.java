package org.telegram.ui.Components.chat;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

import org.telegram.messenger.Utilities;
import org.telegram.ui.ChatBackgroundDrawable;
import org.telegram.ui.Components.MotionBackgroundDrawable;
import org.telegram.utils.glass.source.GlassSource;
import org.telegram.utils.glass.source.GlassSourceBitmap;
import org.telegram.utils.glass.source.GlassSourceColor;
import org.telegram.utils.glass.source.GlassSourceWrapped;
import org.telegram.ui.Components.blur3.utils.BitmapMemoizedMetadata;

public class WallpaperBitmapProvider {

    private final GlassSourceColor sourceColor = new GlassSourceColor();
    private final GlassSourceBitmap sourceBitmap = new GlassSourceBitmap();

    // NagramX: a flat-black proxy for an intensity<0 motion wallpaper. Kept as a separate object from
    // sourceColor rather than reused: sourceColor holds a colour wallpaper's fill, this holds a motion
    // wallpaper's flat black, and one field cannot carry both without cross-writing them on a switch.
    private final GlassSourceColor blackSourceColor = new GlassSourceColor();

    // NagramX: the keyboard pans the wallpaper vertically (SizeNotifierFrameLayout adds
    // backgroundTranslationY to the wallpaper's draw y). The glass proxy is a separate bitmap sampled
    // through its own cover matrix, so without this it stays put while the wallpaper slides under it.
    // Only a bare-bitmap wallpaper (photo, imported-theme or single-colour pattern, Monet) opts into the
    // shift (shiftSource) — a motion gradient mesh is deliberately left unshifted, see
    // setBackgroundTranslationY. The source rebuilds its own cover matrix inside
    // setBitmap()/setParentSize() without telling us and setParentSize early-returns on identical dims,
    // so we can never assume whether a rebuild happened: the tracker detects it by value and recomputes
    // base + current shift from scratch, which is idempotent and cannot double-apply or lose the shift.
    private final KeyboardShiftTracker shift = new KeyboardShiftTracker();
    private boolean shiftSource;
    private int backgroundTranslationY;

    private static final Rect tmpRect = new Rect();

    public GlassSource updateSourceFromBackgroundViewDrawable(
        Drawable drawable
    ) {
        final GlassSource result = updateSourceFromBackgroundViewDrawableInner(drawable);
        // NagramX: the branches above may rebuild sourceBitmap's cover matrix (setBitmap on a wallpaper
        // or type switch), which resets it to identity and loses the keyboard shift, so re-assert it.
        applyBackgroundTranslation();
        return result;
    }

    private GlassSource updateSourceFromBackgroundViewDrawableInner(
        Drawable drawable
    ) {
        // NagramX: default off; only the bare-bitmap wallpaper branch below opts the source into the
        // keyboard shift. Reset here (not in each other branch) so the ColorDrawable, motion and
        // canvas-record branches stay untouched and a photo→motion switch clears it.
        shiftSource = false;

        if (drawable instanceof ColorDrawable) {
            final int color = ((ColorDrawable) drawable).getColor();
            sourceColor.setColor(color);
            return sourceColor;
        }

        if (drawable instanceof MotionBackgroundDrawable) {
            final MotionBackgroundDrawable motionDrawable = (MotionBackgroundDrawable) drawable;
            // NagramX: the gradient-only mesh (or flat black when intensity<0) for full-screen surfaces —
            // no pattern, so a low-res proxy upscales invisibly.
            if (motionDrawable.getIntensity() < 0) {
                blackSourceColor.setColor(Color.BLACK);
                return blackSourceColor;
            }
            // NagramX: setBitmap here aliases the drawable's live mesh on purpose. getBitmap() returns
            // currentBitmap, allocated once in the drawable's init and thereafter mutated in place by
            // generateGradient, so the source animates for free. A defensive Bitmap copy here would
            // silently freeze the gradient.
            sourceBitmap.setBitmap(motionDrawable.getBitmap());
            return sourceBitmap;
        }

        if (drawable instanceof BitmapDrawable) {
            final BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
            // NagramX: never alias the drawable's own bitmap — it can be ImageLoader-owned and recycled
            // the moment a chat closes, and a recycled bitmap baked into a glass render node crashes HWUI
            // with no draw() guard to catch it. Draw through the drawable into our own stack-blurred
            // proxy instead.
            final Bitmap blurred = blurredFromBitmap.get(bitmapDrawable.getBitmap());
            sourceBitmap.setBitmap(blurred);
            // NagramX: this is a bare-wallpaper bitmap, so it must track the keyboard pan
            // (SizeNotifierFrameLayout pans the wallpaper by backgroundTranslationY).
            shiftSource = true;
            return sourceBitmap;
        }

        if (drawable instanceof ChatBackgroundDrawable) {
            ChatBackgroundDrawable chatDrawable = (ChatBackgroundDrawable) drawable;
            return updateSourceFromBackgroundViewDrawable(chatDrawable.getDrawable(false));
        }

        if (drawable != null) {
            Canvas canvas = sourceBitmap.beginRecording(120, 160);
            tmpRect.set(drawable.getBounds());
            drawable.setBounds(0, 0, 120, 160);
            drawable.draw(canvas);
            drawable.setBounds(tmpRect);
            sourceBitmap.endRecording();
            sourceBitmap.setBitmap(blurredFromBitmap.get(sourceBitmap.getBitmap()));
        }

        return sourceBitmap;
    }

    /**
     * Sizes the internal bitmap source so it has a valid cover matrix even when it is not the one
     * currently installed on a wrapper. A wallpaper switch installs whichever source the new drawable
     * maps to but does not force a fresh measure pass, so an unsized source would otherwise draw its
     * mesh 1:1 in the top-left corner until an unrelated relayout. The identical-dims early-return in
     * setParentSize makes a redundant call free.
     */
    public void setParentSize(int width, int height, int actionBarHeight) {
        sourceBitmap.setParentSize(width, height, actionBarHeight);
        // NagramX: setParentSize rebuilds the source's cover matrix, dropping the keyboard shift back to
        // identity, so re-assert it (a no-op when the shift is 0).
        applyBackgroundTranslation();
    }

    /**
     * Vertical offset the keyboard pan applies to the wallpaper (SizeNotifierFrameLayout's
     * backgroundTranslationY). The glass proxy is a separate bitmap and must shift by the same amount so
     * what shows through tracks the real wallpaper. Whether it applies depends on the wallpaper type: for
     * a motion wallpaper the source is a separate gradient bitmap (or flat black), left unshifted because
     * a few px of vertical drift is invisible in smooth low-frequency colour. For a bare-bitmap wallpaper
     * (photo, imported-theme or single-colour pattern, Monet) the source is the blurred proxy of that
     * wallpaper, which SizeNotifierFrameLayout pans by the same backgroundTranslationY, so it must be
     * shifted too (shiftSource).
     */
    public void setBackgroundTranslationY(int translationY) {
        backgroundTranslationY = translationY;
        applyBackgroundTranslation();
    }

    private void applyBackgroundTranslation() {
        if (shiftSource) {
            shift.apply(sourceBitmap, backgroundTranslationY);
        }
    }

    /**
     * Keeps a bitmap source's cover matrix at base + a vertical keyboard shift. Instantiated once rather
     * than copied inline: the re-base-by-value detection below is the subtle part and must live in
     * exactly one place.
     */
    private static final class KeyboardShiftTracker {
        private final Matrix baseMatrix = new Matrix();
        private final Matrix shiftedMatrix = new Matrix();
        private final float[] liveValues = new float[9];
        private final float[] appliedValues = new float[9];
        private boolean tracked;

        void apply(GlassSourceBitmap source, int translationY) {
            // getMatrix() hands back the source's live bitmapMatrix, so read its values out rather than
            // mutating it in place. If those values no longer match what we last wrote, the source rebuilt
            // its cover matrix (setBitmap/setParentSize) since our last pass, so adopt the live matrix as
            // the new base; otherwise the base we already hold is still current. Then always recompute
            // base + current shift, so there is no delta arithmetic to double-apply or lose across an
            // early-returned setParentSize.
            final Matrix live = source.getMatrix();
            live.getValues(liveValues);
            if (!tracked || !java.util.Arrays.equals(liveValues, appliedValues)) {
                baseMatrix.set(live);
                tracked = true;
            }
            shiftedMatrix.set(baseMatrix);
            // Sign: the wallpaper draws at y = backgroundTranslationY + ... (SizeNotifierFrameLayout), so
            // a positive translation moves it down; shift the source's output down by the same amount so
            // the glass keeps sampling the matching wallpaper region.
            shiftedMatrix.postTranslate(0, translationY);
            source.setMatrix(shiftedMatrix);
            shiftedMatrix.getValues(appliedValues);
        }
    }

    public int getNavigationBarColor(GlassSource source) {
        if (source instanceof GlassSourceColor) {
            return ((GlassSourceColor) source).getColor();
        }

        if (source instanceof GlassSourceBitmap) {
            final Bitmap bitmap = ((GlassSourceBitmap) source).getBitmap();
            return navbarColorFromBitmap.get(bitmap);
        }

        if (source instanceof GlassSourceWrapped) {
            return getNavigationBarColor(((GlassSourceWrapped) source).getSource());
        }

        return 0;
    }

    public int getStatusBarColor(GlassSource source) {
        if (source instanceof GlassSourceColor) {
            return ((GlassSourceColor) source).getColor();
        }

        if (source instanceof GlassSourceBitmap) {
            final Bitmap bitmap = ((GlassSourceBitmap) source).getBitmap();
            return statusBarColorFromBitmap.get(bitmap);
        }

        if (source instanceof GlassSourceWrapped) {
            return getStatusBarColor(((GlassSourceWrapped) source).getSource());
        }

        return 0;
    }

    private final BitmapMemoizedMetadata<Bitmap> blurredFromBitmap = new BitmapMemoizedMetadata<>(WallpaperBitmapProvider::blurBitmap);
    private final BitmapMemoizedMetadata<Integer> navbarColorFromBitmap = new BitmapMemoizedMetadata<>(WallpaperBitmapProvider::averageBottomColor);
    private final BitmapMemoizedMetadata<Integer> statusBarColorFromBitmap = new BitmapMemoizedMetadata<>(WallpaperBitmapProvider::averageTopColor);


    private static Bitmap blurBitmap(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return null;
        }

        final float scale = Math.max(bitmap.getWidth() / 90f, bitmap.getHeight() / 120f);
        final Bitmap result = Utilities.stackBlurBitmapWithScaleFactor(bitmap, scale);
        result.setHasAlpha(false);
        return result;
    }



    private static int averageTopColor(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return 0;
        }

        final int height = bitmap.getHeight();
        final int width = bitmap.getWidth();
        final int bottom = height / 10;
        return Utilities.averageBitmapColor(bitmap, 0, 0, width, bottom);
    }

    private static int averageBottomColor(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return 0;
        }

        final int height = bitmap.getHeight();
        final int width = bitmap.getWidth();
        final int top = height * 9 / 10;
        return Utilities.averageBitmapColor(bitmap, 0, top, width, height);
    }

}
