package com.fadcam.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import androidx.core.content.FileProvider;
import com.fadcam.R;
import java.io.File;

/** Standard exported media contracts; prefer Samsung handlers without private component bypasses. */
final class GalleryExternalApps {
    private GalleryExternalApps() {}
    static Intent intent(Context context, Uri uri, boolean edit, boolean video) {
        if (uri == null) throw new IllegalArgumentException("Missing media URI");
        Uri content = "file".equals(uri.getScheme()) ? FileProvider.getUriForFile(context,
                context.getPackageName() + ".provider", new File(uri.getPath())) : uri;
        if (!"content".equals(content.getScheme())) throw new IllegalArgumentException("Unsupported media URI");
        Intent base = new Intent(edit ? Intent.ACTION_EDIT : Intent.ACTION_VIEW)
                .setDataAndType(content, video ? "video/*" : "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | (edit ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0));
        base.setClipData(ClipData.newRawUri("", content));
        String[] preferred = !edit ? new String[]{"com.samsung.android.video", "com.samsung.app.video", "com.sec.android.app.videoplayer"}
                : video ? new String[]{"com.sec.android.app.vepreload", "com.sec.android.gallery3d"}
                : new String[]{"com.sec.android.mimage.photoretouching", "com.sec.android.gallery3d"};
        for (String name : preferred) {
            Intent candidate = new Intent(base).setPackage(name);
            if (available(context, candidate)) return candidate;
        }
        if (edit && video) {
            // Samsung Studio7.4 on the target phone declares MAIN+video, not
            // ACTION_EDIT. Resolve that exported public filter; never name a private activity.
            Intent samsungEditor = new Intent(base).setAction(Intent.ACTION_MAIN).setPackage("com.sec.android.app.vepreload");
            if (available(context, samsungEditor)) return samsungEditor;
        }
        return base;
    }
    private static boolean available(Context context, Intent intent) {
        ResolveInfo match = context.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (match == null || match.activityInfo == null || !match.activityInfo.exported || !match.activityInfo.enabled) return false;
        String permission = match.activityInfo.permission;
        return permission == null || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
    static boolean launch(Context host, Intent intent, int chooserTitle) {
        try {
            if (intent.resolveActivity(host.getPackageManager()) == null) {
                android.widget.Toast.makeText(host, R.string.viewer_external_unavailable, android.widget.Toast.LENGTH_SHORT).show(); return false;
            }
            Intent launch = intent.getPackage() == null ? Intent.createChooser(intent, host.getString(chooserTitle)) : intent;
            if (!(host instanceof Activity)) launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            host.startActivity(launch);
            return true;
        } catch (RuntimeException failure) {
            android.widget.Toast.makeText(host, R.string.viewer_external_unavailable, android.widget.Toast.LENGTH_SHORT).show(); return false;
        }
    }
}
