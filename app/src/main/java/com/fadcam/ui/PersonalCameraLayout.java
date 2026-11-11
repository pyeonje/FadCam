package com.fadcam.ui;

import android.content.Context;
import android.util.DisplayMetrics;

/** Geometry measured in the stored 3120x1440 Samsung Pro reference, scaled by short edge. */
final class PersonalCameraLayout {
    private final float scale;
    PersonalCameraLayout(Context context) {
        DisplayMetrics metrics=context.getResources().getDisplayMetrics();
        scale=Math.min(metrics.widthPixels,metrics.heightPixels)/1440f;
    }
    int px(float referencePixels) { return Math.round(referencePixels*scale); }
    float textSp(Context context,float referencePixels) {
        return referencePixels*scale/context.getResources().getDisplayMetrics().density;
    }
}
