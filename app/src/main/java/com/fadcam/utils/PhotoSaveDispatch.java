package com.fadcam.utils;

import android.graphics.Bitmap;
import android.os.Handler;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.fadcam.FLog;

/** Owns a captured bitmap until the storage task finishes or its handler rejects it. */
public final class PhotoSaveDispatch {
    private PhotoSaveDispatch() {}

    public static void post(@Nullable Handler handler, @NonNull Bitmap bitmap,
                            @NonNull Runnable save, @NonNull Runnable onFailure) {
        boolean accepted = false;
        try {
            if (handler != null) {
                accepted = handler.post(() -> {
                    try {
                        save.run();
                    } catch (RuntimeException error) {
                        FLog.e("PhotoSaveDispatch", "Photo storage task failed", error);
                        onFailure.run();
                    } finally {
                        recycle(bitmap);
                    }
                });
            }
        } catch (RuntimeException error) {
            FLog.e("PhotoSaveDispatch", "Photo storage dispatcher failed", error);
        }
        if (!accepted) {
            recycle(bitmap);
            onFailure.run();
        }
    }

    private static void recycle(Bitmap bitmap) {
        if (!bitmap.isRecycled()) bitmap.recycle();
    }
}
