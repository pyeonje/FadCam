package com.fadcam.service;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.ContentUris;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import com.fadcam.Constants;
import com.fadcam.FLog;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Publishes one app-owned pending media row; never deletes or changes the source. */
final class GalleryMediaPublisher {
    private GalleryMediaPublisher() { }

    @RequiresApi(29)
    @NonNull static Uri publish(@NonNull ContentResolver resolver,
            @NonNull RecordsDeletionRequestItem item,@NonNull BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        Uri source=item.toUri();
        if(source==null)throw new IOException("Missing source URI");
        String name=displayName(item.displayName);
        String mime=resolver.getType(source);
        if(!isMediaMime(mime)) {
            int dot=name.lastIndexOf('.');
            mime=dot<0?null:MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    name.substring(dot+1).toLowerCase(Locale.ROOT));
        }
        if(!isMediaMime(mime))throw new IOException("Unsupported media MIME");
        boolean video=mime.startsWith("video/");
        Uri collection=video?MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                :MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        ContentValues values=new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
        values.put(MediaStore.MediaColumns.MIME_TYPE,mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,(video?Environment.DIRECTORY_MOVIES:
                Environment.DIRECTORY_PICTURES)+"/"+Constants.RECORDING_DIRECTORY);
        values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri destination=resolver.insert(collection,values);
        if(destination==null)throw new IOException("MediaStore insert failed");
        if(destination.equals(source)||!destination.toString().startsWith(collection+"/")
                ||ContentUris.parseId(destination)<=0)throw new IOException("Invalid inserted media row");
        boolean published=false;
        try {
            long copied;
            try(InputStream in=resolver.openInputStream(source);
                    OutputStream out=resolver.openOutputStream(destination,"w")) {
                if(in==null||out==null)throw new IOException("Media stream unavailable");
                copied=copy(in,out,cancelled);
            }
            if(copied<=0||(item.sizeBytes>0&&copied!=item.sizeBytes))
                throw new IOException("Source byte count mismatch");
            // Closing the write stream must succeed before verifying the actual
            // destination descriptor size and exposing this row to other apps.
            try(ParcelFileDescriptor fd=resolver.openFileDescriptor(destination,"r")) {
                if(fd==null||fd.getStatSize()!=copied)throw new IOException("Destination byte count mismatch");
            }
            checkCancelled(cancelled);
            ContentValues ready=new ContentValues();ready.put(MediaStore.MediaColumns.IS_PENDING,0);
            if(resolver.update(destination,ready,null,null)!=1)throw new IOException("MediaStore publish failed");
            published=true;
            return destination;
        } finally {
            if(!published) {
                try { resolver.delete(destination,null,null); }
                catch(RuntimeException error) { FLog.w("GalleryMediaPublisher","Unable to clean owned pending row",error); }
            }
        }
    }

    static long copy(InputStream in,OutputStream out,BooleanSupplier cancelled) throws IOException {
        byte[] buffer=new byte[16*1024];long copied=0;int read;
        while((read=in.read(buffer))!=-1) {
            checkCancelled(cancelled);
            if(read==0)continue;
            out.write(buffer,0,read);copied+=read;
        }
        checkCancelled(cancelled);out.flush();return copied;
    }

    static void checkCancelled(BooleanSupplier cancelled) {
        if(cancelled.getAsBoolean())throw new CancellationException("Gallery save cancelled");
    }

    @NonNull static String displayName(String original) throws IOException {
        if(original==null)throw new IOException("Missing display name");
        String path=original.replace('\\','/');String name=path.substring(path.lastIndexOf('/')+1);
        if(name.trim().isEmpty()||name.equals(".")||name.equals("..")||name.indexOf('\0')>=0)
            throw new IOException("Invalid display name");
        return name;
    }

    private static boolean isMediaMime(String mime) {
        return mime!=null&&(mime.startsWith("video/")||mime.startsWith("image/"))
                &&mime.indexOf('*')<0&&mime.indexOf('\n')<0&&mime.indexOf('\r')<0;
    }
}
