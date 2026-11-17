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
    /** How far the MD3 send and mic disc is drawn inside its slot, so a ring of the field shows round it. */
    public static final int SEND_INSET = 3;
    /** The gap between the field row and the tools row below it. */
    public static final int ROW_GAP = 2;
    /** The text's inset from the enter view's edge with two rows. */
    public static final int TEXT_INSET = 16;

    private ComposerGeometry() {
    }

    /** The field row's bottom with two rows, above the tools row and its gap. Read per call: the panel scale is a setting. */
    public static int fieldBottomDp() {
        return ComposerToolbarLayout.height() + ROW_GAP;
    }

    /** The send column's bottom margin while it sits beside the field, centred on the field row's single line. */
    public static int fieldRowColumnBottomDp(int columnDp) {
        return fieldBottomDp() + Math.max(0, (FIELD_ROW - columnDp) / 2);
    }
}
