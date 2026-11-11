package com.fadcam.ui;

/** Keeps the user's portrait density while using the extra width of a rotated window. */
final class GalleryLayoutPolicy {
    private GalleryLayoutPolicy() {}

    static int columns(int portraitColumns, int availableWidth, int shortEdgeWidth) {
        int preferred = Math.max(1, Math.min(5, portraitColumns));
        if (availableWidth <= 0 || shortEdgeWidth <= 0) return preferred;
        // A one-column list is an explicit user choice, not an automatic grid.
        if (preferred == 1) return 1;
        float widthRatio = Math.max(1f, (float) availableWidth / shortEdgeWidth);
        return Math.max(preferred, Math.min(8, Math.round(preferred * widthRatio)));
    }
}
