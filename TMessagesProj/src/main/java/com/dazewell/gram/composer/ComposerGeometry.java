package com.dazewell.gram.composer;

import com.dazewell.gram.ui.ComposerToolbarLayout;

import org.telegram.ui.Components.ChatActivityEnterView;

/**
 * The composer's shared measurements, in dp: the field row, the tools row under it and the send column beside or in
 * them. The enter view, the fold, the MD3 surface and the layout editor's preview all read these, so a change here moves
 * all of them together. Measurements only one of them uses stay with it.
 */
public final class ComposerGeometry {

    /** The field row's height with one line of text, which is also the send column's square slot. */
    public static final int FIELD_ROW = ChatActivityEnterView.DEFAULT_HEIGHT;
    /**
     * The Glass send disc's diameter while it sits beside the field: the field row less 1dp each side, so the pill's
     * outline stands just proud of it rather than the disc reading a hair larger than the pill.
     */
    public static final int GLASS_BESIDE_DISC = FIELD_ROW - 2;
    /** How far the MD3 send and mic disc is drawn inside its slot, so a ring of the field shows round it. */
    public static final int SEND_INSET = 3;
    /** The row gap slider's range and default, in dp. */
    public static final int ROW_GAP_MAX = 12;
    public static final int ROW_GAP_DEFAULT = 2;
    /** The text's inset from the enter view's edge with two rows. */
    public static final int TEXT_INSET = 16;

    private ComposerGeometry() {
    }

    /** The gap between the field row and the tools row below it, clamped to the slider's range. Read per call: it is a setting. */
    public static int rowGapDp() {
        return Math.max(0, Math.min(ROW_GAP_MAX, xyz.nextalone.nagram.NaConfig.INSTANCE.getComposerRowGap().Int()));
    }

    /** The field row's bottom with two rows, above the tools row and its gap. Read per call: the scale and gap are settings. */
    public static int fieldBottomDp() {
        return ComposerToolbarLayout.height() + rowGapDp();
    }

    /**
     * The send column's bottom margin while it sits beside the field, centred on the field row's single line. A column
     * taller than the row (Glass above 100%) hangs evenly over both of its edges rather than sitting high.
     */
    public static int fieldRowColumnBottomDp(int columnDp) {
        return Math.max(0, fieldBottomDp() + (FIELD_ROW - columnDp) / 2);
    }
}
