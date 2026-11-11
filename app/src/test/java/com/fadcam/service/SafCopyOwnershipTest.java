package com.fadcam.service;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.model.TrashItem;
import com.fadcam.utils.TrashManager;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class SafCopyOwnershipTest {
    private static final String AUTHORITY="test.fadcam.storage.documents";
    private static final String PARENT="target";
    private static final String EXISTING="opaque-existing-id";
    private static final byte[] EXISTING_BYTES={11,22,33};
    private static final byte[] SOURCE_BYTES={44,55,66,77};
    private final Uri tree=DocumentsContract.buildTreeDocumentUri(AUTHORITY,PARENT);

    @Test public void exportNeverOpensOrDeletesAnExistingUriReturnedByCreate() throws Exception {
        Application app=RuntimeEnvironment.getApplication();
        ReusingProvider provider=provider(app);
        File source=File.createTempFile("saf-source-",".mp4",app.getCacheDir());
        Files.write(source.toPath(),SOURCE_BYTES);
        var item=new RecordsDeletionRequestItem(Uri.fromFile(source).toString(),"copy.mp4",SOURCE_BYTES.length,false);
        var snapshot=RecordsDeletionSessionSnapshot.create(Collections.singletonList(item),
                RecordsDeletionSessionSnapshot.OperationKind.SAVE_EXPORT_TO_CUSTOM_TREE);
        snapshot.customTreeUri=tree.toString();
        assertSame(item,snapshot.claimNext());
        var controller=Robolectric.buildService(RecordsDeletionService.class).create();
        try {
            var store=new RecordsDeletionSessionStore(app);
            assertTrue(store.write(snapshot));
            boolean saved=ReflectionHelpers.callInstanceMethod(controller.get(),"processExportItemAsync",
                    ReflectionHelpers.ClassParameter.from(RecordsDeletionSessionSnapshot.class,snapshot),
                    ReflectionHelpers.ClassParameter.from(RecordsDeletionRequestItem.class,item),
                    ReflectionHelpers.ClassParameter.from(String.class,tree.toString()));
            assertArrayEquals(EXISTING_BYTES,Files.readAllBytes(provider.existing.toPath()));
            assertArrayEquals(SOURCE_BYTES,Files.readAllBytes(source.toPath()));
            assertEquals(1,provider.creates);
            assertEquals(0,provider.writes);
            assertEquals(0,provider.deletes);
            assertFalse(saved);
            assertNull(store.read().inFlightOutputUri);
        } finally {
            controller.destroy();
        }
    }

    @Test public void trashRestoreNeverOpensOrDeletesAnExistingUriReturnedByCreate() throws Exception {
        Application app=RuntimeEnvironment.getApplication();
        ReusingProvider provider=provider(app);
        app.getContentResolver().takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        SharedPreferencesManager.getInstance(app).setCustomStorageUri(tree.toString());
        File source=new File(TrashManager.getTrashDirectory(app),"trash-copy.mp4");
        Files.write(source.toPath(),SOURCE_BYTES);
        var item=new TrashItem("file:///copy.mp4","copy.mp4",source.getName(),100L,false,SOURCE_BYTES.length);
        assertTrue(TrashManager.saveTrashMetadata(app,Collections.singletonList(item)));
        boolean saved=TrashManager.restoreItemsFromTrash(app,Collections.singletonList(item),
                TrashManager.RestoreDestination.SAF_STORAGE);
        assertArrayEquals(EXISTING_BYTES,Files.readAllBytes(provider.existing.toPath()));
        assertArrayEquals(SOURCE_BYTES,Files.readAllBytes(source.toPath()));
        assertEquals(1,provider.creates);
        assertEquals(0,provider.writes);
        assertEquals(0,provider.deletes);
        assertFalse(saved);
        assertEquals(Collections.singletonList(item),TrashManager.loadTrashMetadata(app));
    }

    @Test public void normalProviderExportWritesOnlyConfirmedFreshChildAndRecordsVerifiedCopy() throws Exception {
        Application app=RuntimeEnvironment.getApplication();
        ReusingProvider provider=provider(app);
        provider.reuse=false;
        File source=File.createTempFile("saf-valid-source-",".mp4",app.getCacheDir());
        Files.write(source.toPath(),SOURCE_BYTES);
        var item=new RecordsDeletionRequestItem(Uri.fromFile(source).toString(),"copy.mp4",SOURCE_BYTES.length,false);
        var snapshot=RecordsDeletionSessionSnapshot.create(Collections.singletonList(item),
                RecordsDeletionSessionSnapshot.OperationKind.SAVE_EXPORT_TO_CUSTOM_TREE);
        snapshot.customTreeUri=tree.toString();
        assertSame(item,snapshot.claimNext());
        var controller=Robolectric.buildService(RecordsDeletionService.class).create();
        try {
            var store=new RecordsDeletionSessionStore(app);
            assertTrue(store.write(snapshot));
            assertTrue(ReflectionHelpers.<Boolean>callInstanceMethod(controller.get(),"processExportItemAsync",
                    ReflectionHelpers.ClassParameter.from(RecordsDeletionSessionSnapshot.class,snapshot),
                    ReflectionHelpers.ClassParameter.from(RecordsDeletionRequestItem.class,item),
                    ReflectionHelpers.ClassParameter.from(String.class,tree.toString())));
            assertArrayEquals(EXISTING_BYTES,Files.readAllBytes(provider.existing.toPath()));
            assertArrayEquals(SOURCE_BYTES,Files.readAllBytes(source.toPath()));
            assertArrayEquals(SOURCE_BYTES,Files.readAllBytes(provider.fresh.toPath()));
            assertEquals(1,provider.writes);
            assertEquals(0,provider.deletes);
            assertEquals(Long.valueOf(SOURCE_BYTES.length),store.read().inFlightVerifiedBytes);
            assertNotNull(store.read().inFlightVerifiedDigest);
        } finally {
            controller.destroy();
        }
    }

    private static ReusingProvider provider(Application app) throws Exception {
        var provider=new ReusingProvider();
        provider.existing=File.createTempFile("saf-existing-",".mp4",app.getCacheDir());
        Files.write(provider.existing.toPath(),EXISTING_BYTES);
        ProviderInfo info=new ProviderInfo();
        info.authority=AUTHORITY;
        provider.attachInfo(app,info);
        ShadowContentResolver.registerProviderInternal(AUTHORITY,provider);
        return provider;
    }

    private static final class ReusingProvider extends ContentProvider {
        File existing,fresh;
        boolean reuse=true;
        int creates,writes,deletes;
        @Override public boolean onCreate(){return true;}
        @Override public Uri insert(Uri uri,ContentValues values){return null;}
        @Override public int delete(Uri uri,String selection,String[] args){return 0;}
        @Override public int update(Uri uri,ContentValues values,String selection,String[] args){return 0;}
        @Override public String getType(Uri uri){return "video/mp4";}
        @Override public Cursor query(Uri uri,String[] projection,String selection,String[] arguments,String order){
            var cursor=new MatrixCursor(projection);
            if(uri.getLastPathSegment().equals("children")){
                addRow(cursor,projection,EXISTING);
                if(fresh!=null)addRow(cursor,projection,"opaque-fresh-id");
            }else addRow(cursor,projection,DocumentsContract.getDocumentId(uri));
            return cursor;
        }
        private void addRow(MatrixCursor cursor,String[] projection,String id){
            Object[] row=new Object[projection.length];
            for(int i=0;i<projection.length;i++){
                switch(projection[i]){
                    case DocumentsContract.Document.COLUMN_DOCUMENT_ID:row[i]=id;break;
                    case DocumentsContract.Document.COLUMN_DISPLAY_NAME:row[i]=PARENT.equals(id)?"Folder":(EXISTING.equals(id)?"old.mp4":"copy.mp4");break;
                    case DocumentsContract.Document.COLUMN_MIME_TYPE:row[i]=PARENT.equals(id)
                            ?DocumentsContract.Document.MIME_TYPE_DIR:"video/mp4";break;
                    case DocumentsContract.Document.COLUMN_FLAGS:row[i]=DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                            |DocumentsContract.Document.FLAG_SUPPORTS_WRITE|DocumentsContract.Document.FLAG_SUPPORTS_DELETE;break;
                    case DocumentsContract.Document.COLUMN_SIZE:row[i]=PARENT.equals(id)?0L:(EXISTING.equals(id)?existing.length():fresh.length());break;
                    default:row[i]=null;
                }
            }
            cursor.addRow(row);
        }
        @Override public Bundle call(String method,String argument,Bundle extras){
            if("android:createDocument".equals(method)){
                creates++;
                Uri parent=extras.getParcelable("uri");
                String id=EXISTING;
                if(!reuse){
                    id="opaque-fresh-id";
                    try { fresh=File.createTempFile("saf-fresh-",".mp4",getContext().getCacheDir()); }
                    catch(java.io.IOException error){throw new IllegalStateException(error);}
                }
                Bundle result=new Bundle();
                result.putParcelable("uri",DocumentsContract.buildDocumentUriUsingTree(parent,id));
                return result;
            }
            if("android:deleteDocument".equals(method)){
                deletes++;
                existing.delete();
                return new Bundle();
            }
            return super.call(method,argument,extras);
        }
        @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
            if(mode.contains("w"))writes++;
            File file=EXISTING.equals(DocumentsContract.getDocumentId(uri))?existing:fresh;
            return ParcelFileDescriptor.open(file,ParcelFileDescriptor.parseMode(mode));
        }
    }
}

