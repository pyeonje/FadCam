package com.fadcam.service;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.ContentUris;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class GalleryMediaPublisherTest {
    private static final byte[] BYTES={11,22,33,44,55};
    private final Uri destination=Uri.parse("content://media/external_primary/video/media/7");
    private File source() throws IOException {
        File file=File.createTempFile("gallery-source-",".mp4",RuntimeEnvironment.getApplication().getCacheDir());
        Files.write(file.toPath(),BYTES);return file;
    }
    private RecordsDeletionRequestItem item(File source) {
        return new RecordsDeletionRequestItem(Uri.fromFile(source).toString(),source.getName(),BYTES.length,false);
    }
    private ContentResolver resolver(File source) throws Exception {
        ContentResolver resolver=mock(ContentResolver.class);
        when(resolver.getType(any())).thenReturn("video/mp4");
        when(resolver.insert(any(),any())).thenReturn(destination);
        when(resolver.openInputStream(Uri.fromFile(source))).thenAnswer(inv->new FileInputStream(source));
        when(resolver.openOutputStream(destination,"w")).thenReturn(new ByteArrayOutputStream());
        ParcelFileDescriptor fd=mock(ParcelFileDescriptor.class);when(fd.getStatSize()).thenReturn((long)BYTES.length);
        when(resolver.openFileDescriptor(destination,"r")).thenReturn(fd);
        when(resolver.update(eq(destination),any(),isNull(),isNull())).thenReturn(1);
        return resolver;
    }
    private RecordsDeletionService service(ContentResolver resolver,AtomicBoolean cancel) {
        RecordsDeletionService service=mock(RecordsDeletionService.class,CALLS_REAL_METHODS);
        doReturn(resolver).when(service).getContentResolver();
        ReflectionHelpers.setField(service,"cancelRequested",cancel);return service;
    }
    private boolean save(RecordsDeletionService service,RecordsDeletionRequestItem item,boolean move) {
        return ReflectionHelpers.callInstanceMethod(service,"processSaveItemAsync",
                ReflectionHelpers.ClassParameter.from(RecordsDeletionSessionSnapshot.class,new RecordsDeletionSessionSnapshot()),
                ReflectionHelpers.ClassParameter.from(RecordsDeletionRequestItem.class,item),
                ReflectionHelpers.ClassParameter.from(boolean.class,move));
    }

    @Test public void successfulMoveClosesAndPublishesBeforeDeletingOriginal() throws Exception {
        File source=source();ContentResolver resolver=resolver(source);List<String> order=new ArrayList<>();
        when(resolver.openOutputStream(destination,"w")).thenReturn(new ByteArrayOutputStream(){
            @Override public void close() throws IOException { super.close();order.add("closed"); }
        });
        when(resolver.update(eq(destination),any(),isNull(),isNull())).thenAnswer(inv->{
            assertTrue(source.exists());assertEquals(java.util.Arrays.asList("closed"),order);
            ContentValues ready=inv.getArgument(1);assertEquals(Integer.valueOf(0),ready.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
            order.add("published");return 1;
        });
        assertTrue(save(service(resolver,new AtomicBoolean()),item(source),true));
        assertFalse(source.exists());assertEquals(java.util.Arrays.asList("closed","published"),order);
        verify(resolver,never()).delete(any(),any(),any());
    }

    @Test public void copyPublishesWhilePreservingOriginalBytes() throws Exception {
        File source=source();ContentResolver resolver=resolver(source);
        assertTrue(save(service(resolver,new AtomicBoolean()),item(source),false));
        assertArrayEquals(BYTES,Files.readAllBytes(source.toPath()));
        verify(resolver).update(eq(destination),any(),isNull(),isNull());
    }

    @Test public void failedWriteStreamCloseNeverPublishesOrDeletesOriginalAndCleansOnlyOwnPendingRow() throws Exception {
        File source=source();ContentResolver resolver=resolver(source);
        when(resolver.openOutputStream(destination,"w")).thenReturn(new FilterOutputStream(new ByteArrayOutputStream()){
            @Override public void close() throws IOException { super.close();throw new IOException("fixture close failed"); }
        });
        assertFalse(save(service(resolver,new AtomicBoolean()),item(source),true));
        assertArrayEquals(BYTES,Files.readAllBytes(source.toPath()));
        verify(resolver,never()).update(any(),any(),any(),any());verify(resolver).delete(destination,null,null);
    }

    @Test public void failedPublishRetainsOriginalAndRemovesOnlyNewPendingDestination() throws Exception {
        File source=source();ContentResolver resolver=resolver(source);
        when(resolver.update(eq(destination),any(),isNull(),isNull())).thenReturn(0);
        assertFalse(save(service(resolver,new AtomicBoolean()),item(source),true));
        assertArrayEquals(BYTES,Files.readAllBytes(source.toPath()));verify(resolver).delete(destination,null,null);
    }

    @Test public void destinationSizeMismatchCannotPublishOrDeleteOriginal() throws Exception {
        File source=source();ContentResolver resolver=resolver(source);ParcelFileDescriptor fd=mock(ParcelFileDescriptor.class);
        when(fd.getStatSize()).thenReturn(1L);when(resolver.openFileDescriptor(destination,"r")).thenReturn(fd);
        assertFalse(save(service(resolver,new AtomicBoolean()),item(source),true));
        assertArrayEquals(BYTES,Files.readAllBytes(source.toPath()));
        verify(resolver,never()).update(any(),any(),any(),any());verify(resolver).delete(destination,null,null);
    }

    @Test public void cancellationDuringCopyCleansPendingRowButNeverSource() throws Exception {
        File source=source();ContentResolver resolver=resolver(source);AtomicBoolean cancelled=new AtomicBoolean();
        when(resolver.openOutputStream(destination,"w")).thenReturn(new ByteArrayOutputStream(){
            @Override public synchronized void write(byte[] bytes,int offset,int length) {
                super.write(bytes,offset,length);cancelled.set(true);
            }
        });
        assertFalse(save(service(resolver,cancelled),item(source),true));
        assertArrayEquals(BYTES,Files.readAllBytes(source.toPath()));
        verify(resolver,never()).update(any(),any(),any(),any());verify(resolver).delete(destination,null,null);
    }

    @Test public void lateCancellationAfterPublishPreservesBothCopyAndOriginal() throws Exception {
        File source=source();ContentResolver resolver=resolver(source);AtomicBoolean cancelled=new AtomicBoolean();
        when(resolver.update(eq(destination),any(),isNull(),isNull())).thenAnswer(inv->{cancelled.set(true);return 1;});
        assertFalse(save(service(resolver,cancelled),item(source),true));
        assertArrayEquals(BYTES,Files.readAllBytes(source.toPath()));verify(resolver,never()).delete(any(),any(),any());
    }

    @Test public void malformedInsertedRowCannotTriggerSourceCleanup() throws Exception {
        File source=source();ContentResolver resolver=resolver(source);
        when(resolver.insert(any(),any())).thenReturn(Uri.fromFile(source));
        assertFalse(save(service(resolver,new AtomicBoolean()),item(source),true));
        assertArrayEquals(BYTES,Files.readAllBytes(source.toPath()));verify(resolver,never()).delete(any(),any(),any());
    }

    @Test public void unsupportedMediaNameNeverCreatesPublicDestination() throws Exception {
        File source=source();ContentResolver resolver=resolver(source);when(resolver.getType(any())).thenReturn(null);
        RecordsDeletionRequestItem unknown=new RecordsDeletionRequestItem(Uri.fromFile(source).toString(),"unknown.bin",BYTES.length,false);
        assertFalse(save(service(resolver,new AtomicBoolean()),unknown,true));
        assertArrayEquals(BYTES,Files.readAllBytes(source.toPath()));verify(resolver,never()).insert(any(),any());
    }

    @Test public void sourceSizeChangeCannotPublishTruncatedCopyOrDeleteOriginal() throws Exception {
        File source=source();ContentResolver resolver=resolver(source);
        RecordsDeletionRequestItem changed=new RecordsDeletionRequestItem(Uri.fromFile(source).toString(),source.getName(),BYTES.length+1,false);
        assertFalse(save(service(resolver,new AtomicBoolean()),changed,true));
        assertArrayEquals(BYTES,Files.readAllBytes(source.toPath()));
        verify(resolver,never()).update(any(),any(),any(),any());verify(resolver).delete(destination,null,null);
    }

    @Test @Config(sdk=28) public void legacyFailedSourceCloseCleansOnlyNewPartialAndPreservesExistingNameCollision() throws Exception {
        File source=source();ContentResolver resolver=resolver(source);
        File directory=new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS),com.fadcam.Constants.RECORDING_DIRECTORY);
        assertTrue(directory.isDirectory()||directory.mkdirs());
        File existing=new File(directory,source.getName());Files.write(existing.toPath(),new byte[]{77});
        String[] before=directory.list();java.util.Arrays.sort(before);
        when(resolver.openInputStream(Uri.fromFile(source))).thenAnswer(inv->new FileInputStream(source){
            @Override public void close() throws IOException { super.close();throw new IOException("fixture source close failure"); }
        });
        assertFalse(save(service(resolver,new AtomicBoolean()),item(source),true));
        String[] after=directory.list();java.util.Arrays.sort(after);assertArrayEquals(before,after);
        assertArrayEquals(new byte[]{77},Files.readAllBytes(existing.toPath()));
        assertArrayEquals(BYTES,Files.readAllBytes(source.toPath()));
    }

    @Test public void realProviderPublishesPicturesAndMoviesWithPendingBytesAndSanitizedBasename() throws Exception {
        FixtureMediaProvider provider=new FixtureMediaProvider();ProviderInfo info=new ProviderInfo();info.authority="media";
        provider.attachInfo(RuntimeEnvironment.getApplication(),info);ShadowContentResolver.registerProviderInternal("media",provider);
        ContentResolver resolver=RuntimeEnvironment.getApplication().getContentResolver();
        for(String[] kind:new String[][]{{"video/mp4","../folder/video.mp4","Movies/FadCam"},
                {"image/jpeg","..\\folder\\photo.jpg","Pictures/FadCam"}}) {
            provider.mime=kind[0];File source=source();provider.source=source;
            Uri result=GalleryMediaPublisher.publish(resolver,new RecordsDeletionRequestItem(
                    Uri.fromFile(source).toString(),kind[1],BYTES.length,false),()->false);
            assertEquals(provider.row,result);assertEquals(kind[2],provider.inserted.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
            assertFalse(provider.inserted.getAsString(MediaStore.MediaColumns.DISPLAY_NAME).contains("/"));
            assertFalse(provider.inserted.getAsString(MediaStore.MediaColumns.DISPLAY_NAME).contains("\\"));
            assertEquals(Integer.valueOf(1),provider.inserted.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
            assertEquals(Integer.valueOf(0),provider.updated.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
            assertArrayEquals(BYTES,Files.readAllBytes(provider.output.toPath()));assertTrue(source.exists());
        }
    }

    public static final class FixtureMediaProvider extends ContentProvider {
        String mime="video/mp4";File source,output;Uri row;ContentValues inserted,updated;
        @Override public boolean onCreate(){return true;}
        @Override public String getType(Uri uri){return mime;}
        @Override public Uri insert(Uri collection,ContentValues values){
            inserted=new ContentValues(values);updated=null;row=ContentUris.withAppendedId(collection,99);
            try{output=File.createTempFile("gallery-destination-",".bin",getContext().getCacheDir());}
            catch(IOException error){throw new IllegalStateException(error);}return row;
        }
        @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
            return ParcelFileDescriptor.open(output,mode.equals("r")?ParcelFileDescriptor.MODE_READ_ONLY:
                    ParcelFileDescriptor.MODE_WRITE_ONLY|ParcelFileDescriptor.MODE_TRUNCATE);
        }
        @Override public int update(Uri uri,ContentValues values,String where,String[] args){
            assertTrue(source.exists());updated=new ContentValues(values);return 1;
        }
        @Override public int delete(Uri uri,String where,String[] args){return output.delete()?1:0;}
        @Override public Cursor query(Uri uri,String[] columns,String where,String[] args,String order){return null;}
    }
}
