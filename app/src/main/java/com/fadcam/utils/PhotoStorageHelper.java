package com.fadcam.utils;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.documentfile.provider.DocumentFile;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class PhotoStorageHelper {
    private static final String TAG = "PhotoStorageHelper";
    private static final int JPEG_QUALITY = 88;
    private static final int MAX_NAME_ATTEMPTS = 128;
    private static final Object INTERNAL_DIRECTORY_LOCK = new Object();
    private static final Object SAF_CREATION_LOCK = new Object();

    public enum ShotSource {
        BACK,
        SELFIE,
        FADREC
    }

    private PhotoStorageHelper() {
    }

    @Nullable
    public static Uri saveJpegBitmap(
            @NonNull Context context,
            @NonNull Bitmap bitmap
    ) {
        return saveJpegBitmap(context, bitmap, false, ShotSource.BACK);
    }

    @Nullable
    public static Uri saveJpegBitmap(
            @NonNull Context context,
            @NonNull Bitmap bitmap,
            boolean applyWatermarkFromPreferences
    ) {
        return saveJpegBitmap(context, bitmap, applyWatermarkFromPreferences, ShotSource.BACK);
    }

    @Nullable
    public static Uri saveJpegBitmap(
            @NonNull Context context,
            @NonNull Bitmap bitmap,
            boolean applyWatermarkFromPreferences,
            @NonNull ShotSource shotSource
    ) {
        return saveJpegBitmap(context, bitmap, applyWatermarkFromPreferences, shotSource, null);
    }

    @Nullable
    public static Uri saveJpegBitmap(
            @NonNull Context context,
            @NonNull Bitmap bitmap,
            boolean applyWatermarkFromPreferences,
            @NonNull ShotSource shotSource,
            @Nullable String extraWatermarkData
    ) {
        return saveJpegBitmap(context, bitmap, snapshotSaveRequest(context, shotSource,
                applyWatermarkFromPreferences, extraWatermarkData, System.currentTimeMillis()));
    }

    /** Frozen before the render or I/O worker can observe preferences for another shot. */
    public static final class SaveRequest {
        final ShotSource source;
        final String fileName;
        final String watermarkText;
        @Nullable final String customTreeUri;

        private SaveRequest(ShotSource source, String fileName, String watermarkText, @Nullable String customTreeUri) {
            this.source = source;
            this.fileName = fileName;
            this.watermarkText = watermarkText;
            this.customTreeUri = customTreeUri;
        }
    }

    public static SaveRequest snapshotSaveRequest(Context context, ShotSource source, boolean watermark,
            @Nullable String extras, long captureTimeMillis) {
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(context);
        return new SaveRequest(source, buildShotFileName(source, captureTimeMillis),
                watermark ? buildWatermarkText(prefs, extras, captureTimeMillis) : "",
                prefs.getCustomStorageUri());
    }

    @Nullable
    public static Uri saveJpegBitmap(Context context, Bitmap bitmap, SaveRequest request) {
        Bitmap prepared = bitmap;
        try {
            if (!request.watermarkText.isEmpty()) {
                Bitmap watermarked = applyWatermark(context, bitmap, request.watermarkText);
                if (watermarked != null) prepared = watermarked;
            }
            String tree = request.customTreeUri;
            if (tree != null && !tree.trim().isEmpty()) {
                Uri saved = saveToSaf(context, tree, request.fileName, prepared, request.source);
                if (saved != null) return saved;
            }
            return saveToInternal(context, request.fileName, prepared, request.source);
        } finally {
            if (prepared != bitmap && !prepared.isRecycled()) prepared.recycle();
        }
    }

    @Nullable
    private static Uri saveToInternal(
            @NonNull Context context,
            @NonNull String fileName,
            @NonNull Bitmap bitmap,
            @NonNull ShotSource shotSource
    ) {
        return saveToInternal(context, fileName, bitmap, shotSource, java.util.function.UnaryOperator.identity());
    }

    // Package-private stream decorator lets the regression suite inject a close failure
    // after a real candidate has been exclusively created, without global I/O hooks.
    @Nullable
    static Uri saveToInternal(Context context, String fileName, Bitmap bitmap, ShotSource shotSource,
            java.util.function.UnaryOperator<OutputStream> decorateOutput) {
        final File shotDir;
        synchronized (INTERNAL_DIRECTORY_LOCK) {
            shotDir = RecordingStoragePaths.getInternalShotSourceDir(context, toStorageShotSource(shotSource), true);
        }
        if (shotDir == null || !shotDir.isDirectory()) {
            FLog.e(TAG, "Internal photo directory unavailable");
            return null;
        }
        for (int attempt = 0; attempt < MAX_NAME_ATTEMPTS; attempt++) {
            File candidate = new File(shotDir, photoName(fileName, attempt));
            boolean owned = false;
            boolean verified = false;
            try {
                final OutputStream output;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    output = ExclusiveLocalFile.open(candidate);
                    if (output == null) continue; // Name taken; never open it for writing.
                    owned = true;
                } else {
                    if (!candidate.createNewFile()) continue;
                    owned = true;
                    // Android 7 lacks java.nio.file. Exclusive creation reserves the name;
                    // append mode also avoids truncation when opening the reserved file.
                    output = new FileOutputStream(candidate, true);
                }
                try (OutputStream target = output) {
                    writeVerifiedJpeg(bitmap, decorateOutput.apply(target), () -> new FileInputStream(candidate));
                }
                verified = true;
                return Uri.fromFile(candidate);
            } catch (IOException | RuntimeException failure) {
                FLog.e(TAG, "Internal photo save failed", failure);
                return null;
            } finally {
                if (owned && !verified) {
                    try {
                        if (!candidate.delete()) FLog.w(TAG, "Could not remove incomplete new photo");
                    } catch (RuntimeException failure) {
                        FLog.w(TAG, "Could not remove incomplete new photo", failure);
                    }
                }
            }
        }
        FLog.e(TAG, "No available photo name");
        return null;
    }

    @Nullable
    private static Uri saveToSaf(
            @NonNull Context context,
            @NonNull String customTreeUri,
            @NonNull String fileName,
            @NonNull Bitmap bitmap,
            @NonNull ShotSource shotSource
    ) {
        DocumentFile confirmed = null;
        boolean verified = false;
        try {
            final android.content.ContentResolver resolver = context.getContentResolver();
            synchronized (SAF_CREATION_LOCK) {
                DocumentFile shotDir = RecordingStoragePaths.getSafShotSourceDir(
                        context, customTreeUri, toStorageShotSource(shotSource), true);
                if (shotDir == null || !shotDir.canWrite()) return null;
                confirmed = SafFreshDocument.create(resolver, shotDir, "image/jpeg", fileName);
                if (confirmed == null) {
                    // A name may already be present. The second candidate still needs
                    // independent ownership proof and retains the FadShot source prefix.
                    confirmed = SafFreshDocument.create(resolver, shotDir, "image/jpeg",
                            photoName(fileName, java.util.UUID.randomUUID().toString()));
                }
            }
            if (confirmed == null) return null;
            Uri uri = confirmed.getUri();
            writeVerifiedJpeg(bitmap, resolver.openOutputStream(uri, "w"), () -> resolver.openInputStream(uri));
            verified = true;
            return uri;
        } catch (IOException | RuntimeException failure) {
            FLog.e(TAG, "SAF photo save failed", failure);
            return null;
        } finally {
            // SafFreshDocument either proved ownership or threw before assigning confirmed.
            if (confirmed != null && !verified) {
                try {
                    if (!confirmed.delete()) FLog.w(TAG, "Could not remove incomplete new SAF photo");
                } catch (RuntimeException failure) {
                    FLog.w(TAG, "Could not remove incomplete new SAF photo", failure);
                }
            }
        }
    }

    private static String photoName(String fileName, int attempt) {
        return attempt == 0 ? fileName : photoName(fileName, String.valueOf(attempt));
    }

    private static String photoName(String fileName, String suffix) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0
                ? fileName.substring(0, dot) + "_" + suffix + fileName.substring(dot)
                : fileName + "_" + suffix;
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private static final class ExclusiveLocalFile {
        @Nullable
        static OutputStream open(File file) throws IOException {
            try {
                return java.nio.file.Files.newOutputStream(file.toPath(),
                        java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
            } catch (java.nio.file.FileAlreadyExistsException taken) {
                return null;
            }
        }
    }

    private interface ReadBack {
        InputStream open() throws IOException;
    }

    private static void writeVerifiedJpeg(Bitmap bitmap, OutputStream target, ReadBack readBack) throws IOException {
        if (target == null) throw new IOException("JPEG output could not be opened");
        MessageDigest digest = CopyIntegrity.sha256();
        CountingDigestOutput output = new CountingDigestOutput(target, digest);
        try (CountingDigestOutput ignored = output) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                throw new IOException("JPEG encoding failed");
            }
            output.flush();
        }
        if (output.bytes <= 0 || !CopyIntegrity.matches(readBack.open(), output.bytes, digest.digest())) {
            throw new IOException("Stored JPEG differs from the encoded image");
        }
    }

    private static final class CountingDigestOutput extends DigestOutputStream {
        long bytes;

        CountingDigestOutput(OutputStream output, MessageDigest digest) {
            super(output, digest);
        }

        @Override public void write(int value) throws IOException {
            super.write(value);
            bytes++;
        }

        @Override public void write(byte[] data, int offset, int count) throws IOException {
            super.write(data, offset, count);
            bytes += count;
        }
    }

    @NonNull
    private static String buildShotFileName(@NonNull ShotSource shotSource, long captureTimeMillis) {
        return Constants.RECORDING_FILE_PREFIX_FADSHOT
                + shotSourceLabel(shotSource)
                + "_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(captureTimeMillis))
                + "."
                + Constants.RECORDING_IMAGE_EXTENSION;
    }

    @NonNull
    private static String shotSourceLabel(@NonNull ShotSource shotSource) {
        switch (shotSource) {
            case SELFIE:
                return "Selfie";
            case FADREC:
                return "FadRec";
            case BACK:
            default:
                return "Back";
        }
    }

    @NonNull
    private static RecordingStoragePaths.ShotSource toStorageShotSource(@NonNull ShotSource shotSource) {
        switch (shotSource) {
            case SELFIE:
                return RecordingStoragePaths.ShotSource.SELFIE;
            case FADREC:
                return RecordingStoragePaths.ShotSource.FADREC;
            case BACK:
            default:
                return RecordingStoragePaths.ShotSource.BACK;
        }
    }

    private static String buildWatermarkText(@Nullable SharedPreferencesManager prefs, @Nullable String extraWatermarkData, long captureTimeMillis) {
        if (prefs == null) {
            return "";
        }
        String option = prefs.getWatermarkOption();
        if ("no_watermark".equals(option)) {
            return "";
        }
        String timestamp = new SimpleDateFormat("dd/MMM/yyyy hh:mm:ss a", Locale.ENGLISH).format(new Date(captureTimeMillis));

        // Timezone suffix (matching video watermark)
        String timezoneSuffix = "";
        if (prefs.isTimezoneEnabled()) {
            java.util.TimeZone tz = java.util.TimeZone.getDefault();
            int offsetMs = tz.getOffset(captureTimeMillis);
            int totalMinutes = offsetMs / 60000;
            int hours = totalMinutes / 60;
            int minutes = Math.abs(totalMinutes % 60);
            String sign = offsetMs >= 0 ? "+" : "";
            String gmt;
            if (minutes == 0) {
                gmt = "GMT" + sign + hours;
            } else {
                gmt = "GMT" + sign + hours + ":" + String.format(Locale.ENGLISH, "%02d", minutes);
            }
            if ("gmt_name".equals(prefs.getTimezoneFormat())) {
                gmt += " (" + tz.getID() + ")";
            }
            timezoneSuffix = " " + gmt;
        }

        String custom = prefs.getWatermarkCustomText();
        String customLine = (custom != null && !custom.trim().isEmpty()) ? ("\n" + custom.trim()) : "";

        String base;
        switch (option) {
            case "badge_fadcam":
                base = "Captured by <FADCAM_ICON>" + customLine;
                break;
            case "timestamp":
                base = timestamp + timezoneSuffix + customLine;
                break;
            case "timestamp_fadcam":
            default:
                base = "Captured by <FADCAM_ICON> - " + timestamp + timezoneSuffix + customLine;
                break;
        }

        // Append extended sensor data if available, with a blank line gap
        // matching the video watermark pipeline layout
        if (extraWatermarkData != null && !extraWatermarkData.isEmpty()) {
            base += "\n\n" + extraWatermarkData;
        }

        return base;
    }

    @Nullable
    private static Bitmap applyWatermark(@NonNull Context context, @NonNull Bitmap source, @NonNull String text) {
        try {
            Bitmap result = source.copy(Bitmap.Config.ARGB_8888, true);
            Canvas canvas = new Canvas(result);
            // Text size: ~3% of smallest dimension (same proportion as video watermark)
            // For 1080p: 1080 * 0.03 = ~32px (clamped 24-40)
            float minDim = Math.min(result.getWidth(), result.getHeight());
            float textSize = Math.max(18f, Math.min(26f, minDim * 0.02f));

            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.WHITE);
            paint.setTextSize(textSize);
            paint.setShadowLayer(2.5f, 0f, 1.5f, Color.BLACK);
            paint.setFakeBoldText(true);
            try {
                Typeface ubuntu = Typeface.createFromAsset(context.getAssets(), "ubuntu_regular.ttf");
                paint.setTypeface(ubuntu);
            } catch (Exception ignored) {
                paint.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD));
            }

            // Load FadCam icon - same resource used by video watermark
            Bitmap fadcamIcon = null;
            try {
                fadcamIcon = BitmapFactory.decodeResource(context.getResources(), R.drawable.menu_icon_unknown);
            } catch (Exception ignored) {}

            // Icon bigger than text like video watermark (icon is ~2x text height)
            float iconHeight = textSize * 2.0f;

            // Padding accounts for icon extending above text
            int padding = Math.max(Math.round(iconHeight * 0.5f + 8),
                    Math.round(minDim * 0.015f));
            String[] lines = text.split("\n");
            Paint.FontMetrics fm = paint.getFontMetrics();
            float lineHeight = (fm.descent - fm.ascent) + 4f;

            Bitmap scaledIcon = null;
            if (fadcamIcon != null) {
                // Keep aspect ratio — don't stretch square
                float ratio = (float) fadcamIcon.getWidth() / fadcamIcon.getHeight();
                int iconW = Math.round(iconHeight * ratio);
                int iconH = Math.round(iconHeight);
                scaledIcon = Bitmap.createScaledBitmap(fadcamIcon, iconW, iconH, true);
            }

            float y = padding - fm.ascent;
            for (String line : lines) {
                float x = padding;
                if (line.contains("<FADCAM_ICON>") && scaledIcon != null) {
                    // Draw inline icon, then text after it (same as video watermark)
                    String[] parts = line.split("<FADCAM_ICON>", 2);
                    if (parts.length > 0 && !parts[0].isEmpty()) {
                        canvas.drawText(parts[0], x, y, paint);
                        x += paint.measureText(parts[0]) + 6f;
                    }
                    // Center icon vertically on the text line
                    float iconCenterY = y + (fm.ascent + fm.descent) * 0.5f;
                    float iconY = iconCenterY - iconHeight * 0.5f;
                    canvas.drawBitmap(scaledIcon, x, iconY, null);
                    x += scaledIcon.getWidth() + 6f;
                    if (parts.length > 1 && !parts[1].isEmpty()) {
                        canvas.drawText(parts[1], x, y, paint);
                    }
                } else {
                    canvas.drawText(line, x, y, paint);
                }
                y += lineHeight;
            }
            if (fadcamIcon != null && !fadcamIcon.isRecycled()) fadcamIcon.recycle();
            return result;
        } catch (Exception ignored) {
            return null;
        }
    }
}
