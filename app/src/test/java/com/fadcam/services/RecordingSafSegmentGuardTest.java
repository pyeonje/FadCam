package com.fadcam.services;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.Application;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class RecordingSafSegmentGuardTest {
    @Test public void sameUriNeverOpensPreviousSafFile() {
        ContentResolver resolver = mock(ContentResolver.class);
        Uri first = Uri.parse("content://storage/document/segment-a");
        assertThrows(IOException.class,
                () -> RecordingService.openNewSafSegment(resolver, first, first.toString()));
        verifyNoInteractions(resolver);
    }

    @Test public void distinctSafUriIsNeverOpenedInTruncatingMode() throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);
        Uri first = Uri.parse("content://storage/document/segment-a");
        Uri next = Uri.parse("content://storage/document/segment-b");
        ParcelFileDescriptor owned = mock(ParcelFileDescriptor.class);
        when(resolver.openFileDescriptor(next, "rw")).thenReturn(owned);
        assertSame(owned, RecordingService.openNewSafSegment(resolver, next, first.toString()));
        verify(resolver).openFileDescriptor(next, "rw");
        verifyNoMoreInteractions(resolver);
    }
}
