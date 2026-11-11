package com.fadcam.ui;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.app.Application;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35,application=Application.class)
public class GalleryGridPinchTest {
    @Test public void densityDirectionThresholdsAndBoundsDoNotConvertGridToLegacyList() {
        assertEquals(2,GalleryGridPinchController.nextColumns(3,1.26f));
        assertEquals(4,GalleryGridPinchController.nextColumns(3,.79f));
        assertEquals(3,GalleryGridPinchController.nextColumns(3,1.1f));
        assertEquals(2,GalleryGridPinchController.nextColumns(2,2f));
        assertEquals(5,GalleryGridPinchController.nextColumns(5,.5f));
        assertEquals(1,GalleryGridPinchController.nextColumns(1,2f));
        assertEquals(2,GalleryGridPinchController.nextColumns(1,.5f));
        assertEquals(3,GalleryGridPinchController.nextColumns(3,Float.NaN));
    }
    private MotionEvent event(long time,int action,float span,int count) {
        MotionEvent.PointerProperties[] props=new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[count];
        for(int i=0;i<count;i++){props[i]=new MotionEvent.PointerProperties();props[i].id=i;props[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coords[i]=new MotionEvent.PointerCoords();coords[i].x=400+(i==0?-span:span)/2;coords[i].y=300;coords[i].pressure=1;coords[i].size=1;}
        return MotionEvent.obtain(100,time,action,count,props,coords,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);
    }
    @Test public void realTwoPointerEventsResizeWhileSingleFingerStaysWithRecycler() {
        RecyclerView grid=mock(RecyclerView.class);when(grid.getContext()).thenReturn(RuntimeEnvironment.getApplication());
        AtomicInteger columns=new AtomicInteger(3);
        GalleryGridPinchController controller=new GalleryGridPinchController(grid,columns::get,(next,x,y)->columns.set(next));
        MotionEvent down=event(100,MotionEvent.ACTION_DOWN,150,1);assertFalse(controller.onInterceptTouchEvent(grid,down));down.recycle();
        for(int n=0;n<8;n++){
            MotionEvent e=event(200+n*80,n==0?MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT):MotionEvent.ACTION_MOVE,150+n*90,2);
            assertTrue(controller.onInterceptTouchEvent(grid,e));controller.onTouchEvent(grid,e);e.recycle();
        }
        assertEquals(2,columns.get());verify(grid,atLeastOnce()).stopScroll();
        MotionEvent up=event(900,MotionEvent.ACTION_UP,100,1);controller.onTouchEvent(grid,up);up.recycle();
        MotionEvent another=event(1100,MotionEvent.ACTION_DOWN,100,1);assertFalse(controller.onInterceptTouchEvent(grid,another));another.recycle();
        controller.close();verify(grid).removeOnItemTouchListener(controller);
    }
    @Test public void pinchKeepsFocalItemAdapterSelectionAndPersistsDensity() {
        RecordsFragment fragment=spy(new RecordsFragment());Resources resources=mock(Resources.class);
        Configuration configuration=new Configuration();configuration.screenWidthDp=400;configuration.screenHeightDp=800;
        DisplayMetrics metrics=new DisplayMetrics();metrics.density=1;
        when(resources.getConfiguration()).thenReturn(configuration);when(resources.getDisplayMetrics()).thenReturn(metrics);doReturn(resources).when(fragment).getResources();
        RecyclerView recycler=mock(RecyclerView.class);GridLayoutManager layout=mock(GridLayoutManager.class);RecordsAdapter adapter=mock(RecordsAdapter.class);
        View focal=new View(RuntimeEnvironment.getApplication());when(recycler.getLayoutManager()).thenReturn(layout);when(recycler.getWidth()).thenReturn(400);
        when(layout.getSpanCount()).thenReturn(2);when(recycler.findChildViewUnder(200,350)).thenReturn(focal);when(recycler.getChildAdapterPosition(focal)).thenReturn(17);when(layout.getDecoratedTop(focal)).thenReturn(222);
        com.fadcam.SharedPreferencesManager prefs=mock(com.fadcam.SharedPreferencesManager.class);
        List<Uri> selected=new ArrayList<>(Arrays.asList(Uri.parse("content://test/17")));
        ReflectionHelpers.setField(fragment,"recyclerView",recycler);ReflectionHelpers.setField(fragment,"recordsAdapter",adapter);
        ReflectionHelpers.setField(fragment,"selectedUris",selected);ReflectionHelpers.setField(fragment,"isInSelectionMode",true);ReflectionHelpers.setField(fragment,"sharedPreferencesManager",prefs);
        ReflectionHelpers.callInstanceMethod(fragment,"applyPinchGridSpan",ReflectionHelpers.ClassParameter.from(int.class,3),ReflectionHelpers.ClassParameter.from(float.class,200f),ReflectionHelpers.ClassParameter.from(float.class,350f));
        verify(prefs).setRecordsGridSpan(3);verify(layout).setSpanCount(3);verify(layout).scrollToPositionWithOffset(17,222);
        verify(recycler,never()).setAdapter(any());assertSame(selected,ReflectionHelpers.getField(fragment,"selectedUris"));assertTrue(ReflectionHelpers.getField(fragment,"isInSelectionMode"));
    }
}
