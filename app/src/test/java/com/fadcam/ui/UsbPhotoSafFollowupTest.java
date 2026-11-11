package com.fadcam.ui;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.view.TextureView;
import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.data.FastFileScanner;
import com.fadcam.data.entity.VideoIndexEntity;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class UsbPhotoSafFollowupTest {
    private static final String AUTHORITY="test.fadcam.usb.documents";
    private static final String DIR=DocumentsContract.Document.MIME_TYPE_DIR;
    @Rule public TemporaryFolder output=new TemporaryFolder();

    public static class PhotoActivity extends UsbCameraLifecycleTest.ControlledActivity {
        @Override public int checkCallingOrSelfUriPermission(Uri uri,int mode){return PackageManager.PERMISSION_GRANTED;}
    }

    @Test public void usbCaptureUsesSelectedSafRootAndFreshScannerFindsOneStableImageUri()throws Exception{
        Application app=RuntimeEnvironment.getApplication();
        FixtureProvider provider=new FixtureProvider(output.newFolder());
        provider.folders("drive1","drive1/"+Constants.RECORDING_DIRECTORY,"drive2","read-only");
        ProviderInfo info=new ProviderInfo();
        info.authority=AUTHORITY;
        provider.attachInfo(app,info);
        ShadowContentResolver.registerProviderInternal(AUTHORITY,provider);
        SharedPreferencesManager prefs=SharedPreferencesManager.getInstance(app);
        Uri first=DocumentsContract.buildTreeDocumentUri(AUTHORITY,"drive1");
        Uri second=DocumentsContract.buildTreeDocumentUri(AUTHORITY,"drive2");
        int flags=Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        app.getContentResolver().takePersistableUriPermission(first,flags);
        app.getContentResolver().takePersistableUriPermission(second,flags);
        Uri readOnly=DocumentsContract.buildTreeDocumentUri(AUTHORITY,"read-only");
        app.getContentResolver().takePersistableUriPermission(readOnly,Intent.FLAG_GRANT_READ_URI_PERMISSION);

        ActivityController<PhotoActivity> controller=Robolectric.buildActivity(PhotoActivity.class).setup();
        PhotoActivity activity=controller.get();
        try {
            prefs.setStorageMode(SharedPreferencesManager.STORAGE_MODE_CUSTOM);
            prefs.setCustomStorageUri(first.toString());
            capture(activity);

            assertEquals("the selected SAF must receive the photo",1,provider.photos.size());
            String firstId=provider.photos.get(0);
            assertTrue(firstId.startsWith("drive1/"+Constants.RECORDING_DIRECTORY+"/"));
            String expected=DocumentsContract.buildDocumentUriUsingTree(first,firstId).toString();
            assertOneImage(new FastFileScanner(app).scanAll(prefs),expected);
            assertOneImage(new FastFileScanner(app).scanAll(prefs),expected);
            byte[] firstBytes=java.nio.file.Files.readAllBytes(provider.files.get(firstId).toPath());
            assertTrue(firstBytes.length>2);

            provider.incompleteParent="drive1";
            capture(activity);
            assertEquals(activity.getString(R.string.usb_camera_photo_failed),ShadowToast.getTextOfLatestToast());
            assertEquals(1,provider.photos.size());
            provider.incompleteParent=null;

            prefs.setCustomStorageUri(readOnly.toString());
            capture(activity);
            assertEquals(activity.getString(R.string.usb_camera_photo_failed),ShadowToast.getTextOfLatestToast());
            assertEquals(1,provider.photos.size());

            prefs.setCustomStorageUri(second.toString());
            capture(activity);
            assertEquals(2,provider.photos.size());
            String secondId=provider.photos.get(1);
            assertTrue(secondId.startsWith("drive2/"));
            assertOneImage(new FastFileScanner(app).scanAll(prefs),
                    DocumentsContract.buildDocumentUriUsingTree(second,secondId).toString());
            assertArrayEquals(firstBytes,java.nio.file.Files.readAllBytes(provider.files.get(firstId).toPath()));

            prefs.setCustomStorageUri(first.toString());
            assertOneImage(new FastFileScanner(app).scanAll(prefs),expected);
        } finally {
            controller.pause().stop().destroy();
            activity.drain();
            activity.finishWorker();
        }
    }

    private static void capture(PhotoActivity activity) {
        Bitmap bitmap=Bitmap.createBitmap(3,3,Bitmap.Config.ARGB_8888);
        TextureView preview=mock(TextureView.class);
        when(preview.isAvailable()).thenReturn(true);
        when(preview.getBitmap()).thenReturn(bitmap);
        ReflectionHelpers.setField(activity,"preview",preview);
        ReflectionHelpers.setField(activity,"streaming",true);
        ReflectionHelpers.setField(activity,"frameReady",true);
        activity.savePhoto();
        activity.drain();
        assertTrue(bitmap.isRecycled());
    }

    private static void assertOneImage(List<VideoIndexEntity> rows,String uri) {
        assertEquals(1,rows.size());
        assertEquals(uri,rows.get(0).uriString);
        assertEquals("IMAGE",rows.get(0).mediaType);
    }

    private static final class FixtureProvider extends ContentProvider {
        final File work;
        final Map<String,String> types=new LinkedHashMap<>();
        final Map<String,File> files=new LinkedHashMap<>();
        final List<String> photos=new ArrayList<>();
        String incompleteParent;
        FixtureProvider(File work){this.work=work;}
        void folders(String... ids){for(String id:ids)types.put(id,DIR);}
        @Override public boolean onCreate(){return true;}
        @Override public Uri insert(Uri uri,ContentValues values){return null;}
        @Override public int delete(Uri uri,String selection,String[] args){return 0;}
        @Override public int update(Uri uri,ContentValues values,String selection,String[] args){return 0;}
        @Override public String getType(Uri uri){return types.get(DocumentsContract.getDocumentId(uri));}
        @Override public Cursor query(Uri uri,String[] projection,String selection,String[] arguments,String order){
            String id=DocumentsContract.getDocumentId(uri);
            MatrixCursor cursor=new MatrixCursor(projection);
            if(uri.getLastPathSegment().equals("children")){
                if(id.equals(incompleteParent)){
                    Bundle extras=new Bundle();
                    extras.putBoolean(DocumentsContract.EXTRA_LOADING,true);
                    cursor.setExtras(extras);
                    return cursor;
                }
                String prefix=id+"/";
                for(String child:types.keySet())if(child.startsWith(prefix)&&child.indexOf('/',prefix.length())<0)
                    add(cursor,projection,child);
            }else if(types.containsKey(id))add(cursor,projection,id);
            return cursor;
        }
        private void add(MatrixCursor cursor,String[] projection,String id){
            Object[] row=new Object[projection.length];
            for(int i=0;i<projection.length;i++)switch(projection[i]){
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID:row[i]=id;break;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME:row[i]=id.substring(id.lastIndexOf('/')+1);break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE:row[i]=types.get(id);break;
                case DocumentsContract.Document.COLUMN_FLAGS:row[i]=DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                        |DocumentsContract.Document.FLAG_SUPPORTS_WRITE|DocumentsContract.Document.FLAG_SUPPORTS_DELETE;break;
                case DocumentsContract.Document.COLUMN_SIZE:row[i]=files.containsKey(id)?files.get(id).length():0L;break;
                case DocumentsContract.Document.COLUMN_LAST_MODIFIED:row[i]=1234L;break;
                default:row[i]=null;
            }
            cursor.addRow(row);
        }
        @Override public Bundle call(String method,String argument,Bundle extras){
            Uri parent=extras.getParcelable("uri");
            if(method.equals("android:createDocument")){
                String id=DocumentsContract.getDocumentId(parent)+"/"+extras.getString("_display_name");
                String mime=extras.getString("mime_type");
                types.put(id,mime);
                if(!DIR.equals(mime)){
                    photos.add(id);
                    files.put(id,new File(work,"shot-"+photos.size()+".jpg"));
                }
                Bundle result=new Bundle();
                result.putParcelable("uri",DocumentsContract.buildDocumentUriUsingTree(parent,id));
                return result;
            }
            return super.call(method,argument,extras);
        }
        @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
            File file=files.get(DocumentsContract.getDocumentId(uri));
            if(file==null)throw new FileNotFoundException(uri.toString());
            return ParcelFileDescriptor.open(file,ParcelFileDescriptor.parseMode(mode));
        }
    }
}
