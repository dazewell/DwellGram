package com.dazewell.gram.composer;

import org.telegram.messenger.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The set of composer toolbar buttons a user is allowed to place, and where each one starts.
 *
 * Contextual controls (the send-as avatar, the bot commands pill, the rich draft delete button, the
 * bot web view) are deliberately absent: they come and go with chat state and own their own slots.
 */
public final class ComposerButtons {

    public static final int ZONE_START = 0;
    public static final int ZONE_MIDDLE = 1;
    public static final int ZONE_END = 2;
    public static final int ZONE_HIDDEN = 3;
    public static final int ZONE_COUNT = 4;

    /** Held by a view the enter view creates and owns. */
    public static final int KIND_CORE = 0;
    /** Applies a text style, so it only enables while there is a selection. */
    public static final int KIND_FORMAT = 1;
    /** Acts on the field without needing a selection. */
    public static final int KIND_TEXT = 2;
    /** Cut/copy/paste: forwarded straight to EditTextCaption's own menu handling, not performMenuAction. */
    public static final int KIND_CLIPBOARD = 3;
    /** Opens its own UI from the toolbar; always enabled, needs neither text nor a selection. */
    public static final int KIND_ACTION = 4;

    /** The leading zone holds at most this many buttons, ordered start to end. */
    public static final int START_CAPACITY = 2;

    public static final String EMOJI = "emoji";
    public static final String ATTACH = "attach";
    public static final String SCHEDULE = "schedule";
    public static final String EXPAND = "expand";
    public static final String RICH = "rich";
    public static final String AI = "ai";
    public static final String SELECT_ALL = "selectall";
    public static final String CUT = "cut";
    public static final String COPY = "copy";
    public static final String PASTE = "paste";
    public static final String TEMPLATES = "templates";

    public static final class Button {
        public final String key;
        public final int titleRes;
        public final int iconRes;
        public final int kind;
        public final int defaultZone;
        /** Menu action forwarded to the edit field, or 0 when the button drives its own view. */
        public final int menuAction;

        Button(String key, int titleRes, int iconRes, int kind, int defaultZone, int menuAction) {
            this.key = key;
            this.titleRes = titleRes;
            this.iconRes = iconRes;
            this.kind = kind;
            this.defaultZone = defaultZone;
            this.menuAction = menuAction;
        }

        public boolean canSitIn(int zone) {
            // Kept as the placement gate call sites already use; unrestricted layout currently allows all zones.
            return true;
        }
    }

    private static final LinkedHashMap<String, Button> REGISTRY = new LinkedHashMap<>();

    private static void register(Button button) {
        if (REGISTRY.put(button.key, button) != null) {
            // A second registration under the same key silently replaces the first, so the button
            // would quietly take the later entry's zone and constraints. Fail while it is obvious.
            throw new IllegalStateException("duplicate composer button: " + button.key);
        }
    }

    static {
        register(new Button(EMOJI, R.string.AccDescrEmojiButton, R.drawable.input_smile_solar, KIND_CORE, ZONE_START, 0));
        register(new Button(RICH, R.string.ArticleEditor, R.drawable.iv_fullscreen, KIND_CORE, ZONE_MIDDLE, 0));
        register(new Button(AI, R.string.AIEditor, R.drawable.input_ai_star, KIND_CORE, ZONE_MIDDLE, 0));

        register(new Button("quote", R.string.Quote, R.drawable.formatting_quote, KIND_FORMAT, ZONE_MIDDLE, R.id.menu_quote));
        register(new Button("spoiler", R.string.Spoiler, R.drawable.formatting_spoiler, KIND_FORMAT, ZONE_MIDDLE, R.id.menu_spoiler));
        register(new Button(SELECT_ALL, R.string.SelectAll, R.drawable.nax_formatting_select_all, KIND_TEXT, ZONE_MIDDLE, 0));
        register(new Button("regular", R.string.Regular, R.drawable.nax_formatting_eraser, KIND_FORMAT, ZONE_MIDDLE, R.id.menu_regular));

        register(new Button(CUT, R.string.Cut, R.drawable.nax_formatting_cut, KIND_CLIPBOARD, ZONE_HIDDEN, android.R.id.cut));
        register(new Button(COPY, R.string.Copy, R.drawable.msg_copy_solar, KIND_CLIPBOARD, ZONE_HIDDEN, android.R.id.copy));
        register(new Button(PASTE, R.string.Paste, R.drawable.baseline_content_paste_24, KIND_CLIPBOARD, ZONE_HIDDEN, android.R.id.paste));
        register(new Button(TEMPLATES, R.string.TemplateMessagesButton, R.drawable.flash_on_solar, KIND_ACTION, ZONE_HIDDEN, 0));

        register(new Button("mono", R.string.Mono, R.drawable.formatting_code, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_mono));
        register(new Button("bold", R.string.Bold, R.drawable.formatting_bold, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_bold));
        register(new Button("italic", R.string.Italic, R.drawable.formatting_italic, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_italic));
        register(new Button("code", R.string.MonoCode, R.drawable.iv_code, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_code));
        register(new Button("strike", R.string.Strike, R.drawable.formatting_strikethrough, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_strike));
        register(new Button("underline", R.string.Underline, R.drawable.formatting_underline, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_underline));
        register(new Button("link", R.string.CreateLink, R.drawable.menu_link_create2, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_link));
        register(new Button("mention", R.string.CreateMention, R.drawable.deproko_baseline_mention_24, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_mention));
        register(new Button("date", R.string.FormattedDate, R.drawable.input_calendar_add_solar, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_date));
        register(new Button("translate", R.string.TranslateMessage, R.drawable.msg_translate_solar, KIND_FORMAT, ZONE_HIDDEN, R.id.menu_translate));

        register(new Button(EXPAND, R.string.ExpandMessageField, R.drawable.nax_composer_expand, KIND_CORE, ZONE_END, 0));
        // Schedule and Attach used to draw full-bleed raster assets; they now render the vector icons
        // the opt-in Solar theme already ships (input_calendar1/2_solar, ayu_input_attach).
        register(new Button(SCHEDULE, R.string.ScheduledMessages, R.drawable.input_calendar_add_solar, KIND_CORE, ZONE_END, 0));
        register(new Button(ATTACH, R.string.AccDescrAttachButton, R.drawable.ayu_input_attach, KIND_CORE, ZONE_END, 0));
    }

    private static final List<Button> ALL = Collections.unmodifiableList(new ArrayList<>(REGISTRY.values()));

    private ComposerButtons() {
    }

    public static List<Button> all() {
        return ALL;
    }

    public static Button get(String key) {
        return key == null ? null : REGISTRY.get(key);
    }

    /**
     * Optical scale per button - the row's whole size correction, in one table.
     *
     * <p>Model E. Each glyph is scaled so its ink bounding box reaches a preferred width of 83.3% of
     * the 24dp canvas (20/24, the band the icons nobody complained about already sit in), then held
     * inside an ink-area band of 14-23% so a dense glyph cannot read as a blob or a sparse one as a
     * smudge, then capped at 92% width and 94% height so nothing runs to the canvas edge. Width leads
     * because in a horizontal row the gap the eye reads is horizontal, and the complaint that started
     * this ("excessive padding") was about that gap. Where the constraints fight, the cap wins and the
     * glyph is left off target rather than given a private exception. Schedule and expand are stated
     * exceptions to that rule - see their comments below for why the model value is not taken and
     * what each costs.
     *
     * <p>Every number here is output from {@code Tools/scripts/IconInk.java} measuring the drawable the
     * button actually draws - not the registry asset, where the two differ - and may only ever be
     * re-measured, never nudged, except for the stated exceptions below. The trailing comment
     * on each line is the measurement it came from:
     * ink area, then ink bounding box, both as a percentage of the canvas. Where that drawable is a
     * {@code CombinedDrawable} layering a differently-tinted accent over the glyph rather than more
     * glyph ink (schedule's badge dot, see its entry below), the accent is measured out of the area
     * term - left in, it drives the area cap and shrinks the whole glyph to fit an accent that was
     * never meant to read as ink.
     *
     * <p>Nothing is baked into an asset any more. Under the toolbar's FIT_CENTER icon box a runtime
     * scale and a {@code <group>} scale are the same transform, so keeping it here makes the row
     * reviewable in one place instead of spread across twenty files, and stops the fork carrying
     * copies of upstream drawables that quietly stop tracking it. Fork an asset only to *move* ink,
     * which a scale cannot do.
     */
    private static final Map<String, Float> ICON_SCALE = new HashMap<>();

    private static void iconScale(String key, float scale) {
        if (ICON_SCALE.put(key, scale) != null) {
            throw new IllegalStateException("duplicate icon scale: " + key);
        }
    }

    static {
        // Measured on the base layer alone (input_calendar1_solar), not the CombinedDrawable union:
        // drawable2 is a badge dot tinted key_chat_recordedVoiceDot against drawable1's
        // key_glass_defaultIcon, a differently-tinted accent rather than glyph ink. It was contributing
        // 4.91pp of ink area on an identical bbox (0.00pp), which alone pushed the union past the area
        // ceiling and shrank the frame the eye actually compares against the paperclip beside it. This
        // base-layer measurement is unchanged and still stands: 25.00% ink, 90.43 x 88.28. That
        // measurement alone implies an intermediate scale of 0.9212 (not the constant below), where the
        // union is still both layers so 29.91% x 0.9212^2 = 25.4% ink; the change from the prior 0.8769
        // to that intermediate 0.9212 was +5% - about 1dp on a 19dp glyph - installed and confirmed on
        // device to look identical to before, i.e. below the threshold of visibility.
        //
        // What that measurement fix could not touch: the model normalises on ink *width* (see the class
        // comment above), and schedule sits in the same bubble as attach, directly beside it. Attach is
        // pinned at the 94% height cap, so it draws unusually tall (22.56dp ink) while narrow (77.93%
        // width); schedule normalised on width alone draws 20.0 x 19.5dp at 0.9212 - matched on width
        // but conspicuously shorter than the one glyph it is compared against in the same bubble.
        // Matching attach's height exactly would need s=1.0665 (a 23.1dp-wide calendar, 16% wider than
        // every other icon in the row), so the two dimensions cannot both be matched for this specific
        // pairing - not a number the table can fix.
        //
        // 0.9767 is an authored exception, in the style of MENU_ICON_SCALE and NOTIFY_ICON_SCALE below:
        // it splits the difference by matching the geometric mean of schedule's drawn ink box to
        // attach's - the glyph it is actually compared against - rather than matching either dimension
        // alone. It scales *from* the corrected 25.00%/90.43x88.28 basis above; it does not replace it.
        //   attach:   box = 24 x 1.0395 = 24.95; ink = 19.44 x 22.56 (77.93% x 90.43%); gm = 20.94
        //   schedule: box = 24 x 0.9767 = 23.44; ink = 21.20 x 20.69 (90.43% x 88.28%); gm = 20.94
        // Accepted cost, knowingly above the model's ceilings: drawn ink width is +6.0% over the
        // model's 20/24 (83.3%) target, and ink area is 23.85% base / 28.53% drawn union - both above
        // the model's 23% area ceiling. This is a deliberate exception, not a re-measurement - do not
        // read 23.85%/28.53% back into the model's band or re-derive 0.9212 from them.
        iconScale(SCHEDULE, 0.9767f);   // authored exception scaled off 25.00% ink, 90.43 x 88.28 base layer - see note above
        iconScale(PASTE, 0.8892f);      // 29.09% ink, 75.00 x 91.70
        iconScale(TEMPLATES, 0.8644f);  // 30.78% ink, 75.00 x 91.70 - held by the area ceiling
        iconScale("date", 0.9292f);     // 25.72% ink, 89.65 x 87.60
        iconScale(COPY, 0.9660f);       // 24.51% ink, 86.23 x 94.53
        iconScale("translate", 0.9212f);// 22.13% ink, 90.43 x 90.43
        iconScale(ATTACH, 1.0395f);     // 21.05% ink, 77.93 x 90.43 - held by the height cap
        iconScale("quote", 1.0475f);    // 20.96% ink, 70.90 x 50.00
        iconScale("regular", 1.0533f);  // 20.73% ink, 75.00 x 78.42
        iconScale("bold", 1.0976f);     // 19.09% ink, 43.36 x 58.40
        iconScale("underline", 1.1211f);// 18.30% ink, 58.40 x 75.00
        iconScale("link", 1.0012f);     // 18.16% ink, 83.20 x 83.20 - measured as drawn, its group is artwork
        iconScale("strike", 0.9988f);   // 16.73% ink, 83.40 x 67.38
        iconScale(CUT, 1.1749f);        // 16.05% ink, 70.90 x 73.05
        iconScale(SELECT_ALL, 1.1403f); // 14.31% ink, 73.05 x 73.05
        iconScale(AI, 1.1782f);         // 12.92% ink, 70.70 x 68.07 - AiButtonDrawable, not input_ai_star
        iconScale("italic", 1.3521f);   // 12.58% ink, 54.20 x 58.40
        iconScale("spoiler", 1.3554f);  // 12.52% ink, 60.64 x 58.40
        iconScale("code", 1.1482f);     // 10.62% ink, 77.73 x 62.50 - held by the area floor
        // The model value would be 1.2405, with 16.1% drawn ink area. Keep the previous 1.1343 scale
        // instead: the accepted cost is 76.2% drawn ink width, below the 83.3% width target, and
        // 13.45% drawn ink area, 0.5pp under the area floor. This button was changed to shorten the
        // horizontal reach of the caps; scaling it to the model would make the glyph taller than
        // before and spend that polish on size instead.
        iconScale(EXPAND, 1.1343f);     // 10.46% ink, 67.19 x 73.44 - authored exception
        iconScale("mono", 1.1031f);     // 10.39% ink, 83.40 x 50.00 - held by the width cap
        iconScale(RICH, 1.2731f);       // 10.18% ink, 65.43 x 65.43
        iconScale("mention", 1.5519f);  // 9.55% ink, 45.90 x 45.90 - sparsest in the row
        // EMOJI is deliberately absent, so it renders at 1f. Its resting glyph is a Lottie composition
        // (frame 0 of R.raw.smile_to_keyboard, reached via setProgress(0)), whose ink bounding box is
        // 80.95% of the canvas wide - 2.4pp inside the 83.3% preferred width, closer to it than several
        // glyphs this table does correct. Its ink *area* is unmeasured: extracting frame-0 bezier
        // geometry from the composition statically is a parser in its own right, and a subtle error in
        // one would be unreviewable. A proxy area from the static input_smile_solar asset was
        // considered and rejected - if the composition is sparser than the static glyph, the proxy
        // shrinks a default-visible button and nobody finds out until it is on a phone. So it stays at
        // 1f, satisfying the invariant that is measured and claiming nothing about the one that isn't.
        // If it reads heavy beside its corrected neighbours, it earns a constant from a real render.
    }

    /**
     * The largest correction any button asks for, over every entry point into the icon box - the
     * keyed table above plus the two resource-keyed constants below it. Derived rather than typed so
     * that measuring a sparser glyph than the current holder (mention, 1.5519) moves the cell floor
     * that depends on it instead of silently starving that glyph of its keyline. Computed once: the
     * table is fully populated by the static initializer above and never written again.
     */
    private static final float MAX_ICON_SCALE = computeMaxIconScale();

    private static float computeMaxIconScale() {
        float max = 1f;
        for (Float scale : ICON_SCALE.values()) {
            max = Math.max(max, scale);
        }
        // Reads two constants declared below it, which is safe only because both are constant
        // variables (JLS 4.12.4) and so are inlined at their use site rather than read from a field
        // the static initializer has not reached yet - the bytecode here is ldc 1.1f, not getstatic.
        // Give either one a non-constant initializer and it silently becomes 0f at this point, so
        // they stay literals.
        return Math.max(max, Math.max(MENU_ICON_SCALE, NOTIFY_ICON_SCALE));
    }

    /**
     * The widest a glyph is ever drawn inside the shared cell, as a factor of the 24dp box. The cell
     * floor in {@link com.dazewell.gram.ui.ComposerToolbarLayout} is checked against this, so no
     * reachable size can shrink a cell below the glyph it has to hold.
     */
    public static float maxIconScale() {
        return MAX_ICON_SCALE;
    }

    /**
     * Optical scale for a button placed by its key. Unlisted keys render at natural size.
     */
    public static float iconScaleForKey(String key) {
        Float scale = key == null ? null : ICON_SCALE.get(key);
        return scale == null ? 1f : scale;
    }

    /**
     * Optical scale for the settings preview, which draws the registry's named asset rather than the
     * drawable the live button builds. For most buttons those are the same file and the preview shares
     * the toolbar's number. For the two that compose their glyph at runtime they are not, and the
     * toolbar's scale would be measured against geometry the preview never shows - ai is the sharp
     * case, since it draws AiButtonDrawable at 12.92% ink while its registry asset input_ai_star is
     * 33.75%, so the toolbar's enlargement applied to the preview asset would inflate it badly.
     */
    public static float previewIconScale(String key) {
        if (AI.equals(key) || SCHEDULE.equals(key)) {
            return 1f;
        }
        return iconScaleForKey(key);
    }

    /**
     * ic_ab_other is the attach button's menu-open state. It sits outside the keyline on purpose: at
     * 17.97% ink width the model would scale it 1.383x into a three-dot column running 94% of the
     * canvas height, and it has nothing to be uneven against - it replaces the attach glyph in place
     * while the menu is open, so its neighbours never change. This is its authored scale.
     */
    private static final float MENU_ICON_SCALE = 1.10f;
    /**
     * The notify toggle is not a composer toolbar button - it is the mute control in the enter view,
     * and it only reaches this resolver because it shares the same icon box. Deliberately left off the
     * row's keyline: correcting it would resize a user-visible icon on a surface this change never
     * looked at. CrossOutDrawable wraps input_notify_on with its own bitmap padding and a slash that
     * overhangs the glyph, so the shared 24dp box would otherwise draw it slightly large.
     */
    private static final float NOTIFY_ICON_SCALE = 0.85f;

    /**
     * Optical scale for a button sized from the drawable it currently shows rather than its registry
     * key - used when a button swaps drawables after construction (attach's resting vector versus its
     * menu-open raster) or is drawn by a wrapper the key can't see (the notify CrossOutDrawable).
     * Resting attach reads the shared table, so both entry points land it in the same place.
     */
    public static float iconScaleForResource(int resource) {
        if (resource == R.drawable.ayu_input_attach || resource == R.drawable.input_attach) {
            return iconScaleForKey(ATTACH);
        }
        if (resource == R.drawable.ic_ab_other) {
            return MENU_ICON_SCALE;
        }
        if (resource == R.drawable.input_notify_on) {
            return NOTIFY_ICON_SCALE;
        }
        return 1f;
    }

    public static boolean isFormat(String key) {
        Button button = get(key);
        return button != null && button.kind == KIND_FORMAT;
    }

    public static boolean isTextAction(String key) {
        Button button = get(key);
        return button != null && button.kind == KIND_TEXT;
    }

    public static boolean isClipboardAction(String key) {
        Button button = get(key);
        return button != null && button.kind == KIND_CLIPBOARD;
    }
}
