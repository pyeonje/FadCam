package com.fadcam.ui;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fadcam.MainActivity;
import com.fadcam.R;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Native regression on the one existing owned AVD, using only owned 35-minute media. */
@RunWith(AndroidJUnit4.class)
public class GalleryPinchDeviceTest {
    private final Instrumentation i=InstrumentationRegistry.getInstrumentation();
    private void main(Runnable action){i.runOnMainSync(action);}
    private void waitFor(BooleanSupplier condition) throws Exception {
        long end=SystemClock.uptimeMillis()+30000;
        while(SystemClock.uptimeMillis()<end){AtomicReference<Boolean> value=new AtomicReference<>();main(()->value.set(condition.getAsBoolean()));if(value.get())return;SystemClock.sleep(100);}
        fail("Actual product state/window did not settle within30seconds");
    }
    private void click(Activity a,int id){main(()->{View v=a.findViewById(id);assertNotNull(v);assertTrue(v.isShown());assertTrue(v.performClick());});}
    private Player player(VideoPlayerActivity a){return ((PlayerView)a.findViewById(R.id.player_view)).getPlayer();}
    private void autoRotationStillOff(){assertEquals(0,android.provider.Settings.System.getInt(i.getTargetContext().getContentResolver(),android.provider.Settings.System.ACCELEROMETER_ROTATION,-1));}
    private void drag(GalleryMediaStrip strip,float pixels) throws Exception {
        long start=SystemClock.uptimeMillis();
        for(int n=0;n<=12;n++){final int step=n;main(()->{
            int action=step==0?MotionEvent.ACTION_DOWN:step==12?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE;
            MotionEvent event=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,strip.getWidth()/2f+pixels*step/12f,strip.getHeight()/2f,0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);assertTrue(strip.dispatchTouchEvent(event));event.recycle();
        });SystemClock.sleep(35);}
        waitFor(()->strip.getScrollState()==androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE);
    }
    private void marker(Activity a,String name) throws Exception {write(a,name,new JSONObject().put("marker",name).toString());}
    private File folder(Activity a){
        String run=InstrumentationRegistry.getArguments().getString("repair_run","");assertTrue(run.matches("[A-Za-z0-9_-]{1,90}"));
        File dir=new File(a.getExternalFilesDir(null),"qa-gallery-repair/"+run);assertTrue(dir.isDirectory()||dir.mkdirs());return dir;
    }
    private void write(Activity a,String name,String text) throws Exception {try(FileOutputStream f=new FileOutputStream(new File(folder(a),name+".json"))){f.write(text.getBytes(StandardCharsets.UTF_8));}}
    private void snapshot(Activity a,String name) throws Exception {
        i.waitForIdleSync();SystemClock.sleep(450);
        Bitmap bitmap=i.getUiAutomation().takeScreenshot();assertNotNull(bitmap);
        try(FileOutputStream f=new FileOutputStream(new File(folder(a),name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,f));}finally{bitmap.recycle();}
        AtomicReference<String> json=new AtomicReference<>();main(()->{
            try {
                JSONObject data=new JSONObject().put("name",name).put("width",a.getWindow().getDecorView().getWidth()).put("height",a.getWindow().getDecorView().getHeight()).put("requestedOrientation",a.getRequestedOrientation());
                android.view.WindowInsets insets=a.getWindow().getDecorView().getRootWindowInsets();
                android.graphics.Insets cut=insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.displayCutout());
                data.put("cutoutSafe",new JSONArray(new int[]{cut.left,cut.top,cut.right,cut.bottom}));
                JSONArray views=new JSONArray();
                for(int id:new int[]{R.id.back_button,R.id.gallery_video_top_actions,R.id.gallery_viewer_favorite,R.id.gallery_viewer_edit,R.id.gallery_viewer_share,R.id.info_button,R.id.gallery_media_strip,androidx.media3.ui.R.id.exo_center_controls,R.id.recycler_view_records}) {
                    View v=a.findViewById(id);if(v==null)continue;Rect r=new Rect();v.getGlobalVisibleRect(r);
                    views.put(new JSONObject().put("id",a.getResources().getResourceEntryName(id)).put("shown",v.isShown()).put("bounds",new JSONArray(new int[]{r.left,r.top,r.right,r.bottom})));
                }
                data.put("views",views);
                if(a instanceof VideoPlayerActivity){Player p=player((VideoPlayerActivity)a);data.put("positionMs",p.getCurrentPosition()).put("durationMs",p.getDuration()).put("playing",p.getPlayWhenReady()).put("inline",a.findViewById(R.id.gallery_media_strip)!=null);}
                json.set(data.toString());
            }catch(Exception e){throw new RuntimeException(e);}
        });write(a,name,json.get());
    }
    private void assertSafeToolbar(VideoPlayerActivity a){main(()->{
        android.view.WindowInsets w=a.getWindow().getDecorView().getRootWindowInsets();
        android.graphics.Insets safe=w.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.displayCutout());
        assertTrue("Owned AVD cutout fixture is really active",safe.left+safe.top+safe.right+safe.bottom>0);
        Rect full=new Rect();a.getWindow().getDecorView().getGlobalVisibleRect(full);
        for(int id:new int[]{R.id.back_button,R.id.gallery_video_top_actions}){
            Rect bounds=new Rect();assertTrue(a.findViewById(id).getGlobalVisibleRect(bounds));
            assertTrue("Toolbar clears physical cutout left",bounds.left>=full.left+safe.left);
            assertTrue("Toolbar clears physical cutout top",bounds.top>=full.top+safe.top);
            assertTrue("Toolbar clears physical cutout right",bounds.right<=full.right-safe.right);
        }
    });}

    private void pinch(androidx.recyclerview.widget.RecyclerView grid,float startSpan,float endSpan) throws Exception {
        long down=SystemClock.uptimeMillis();
        for(int n=0;n<=17;n++){final int step=n;main(()->{
            int count=step==0||step==17?1:2;
            int action=step==0?MotionEvent.ACTION_DOWN:step==1?MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT):step==16?MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT):step==17?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE;
            float span=startSpan+(endSpan-startSpan)*Math.min(14,Math.max(0,step-1))/14f;
            MotionEvent.PointerProperties[] props=new MotionEvent.PointerProperties[count];MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[count];
            for(int p=0;p<count;p++){props[p]=new MotionEvent.PointerProperties();props[p].id=p;props[p].toolType=MotionEvent.TOOL_TYPE_FINGER;
                coords[p]=new MotionEvent.PointerCoords();coords[p].x=grid.getWidth()/2f+(p==0?-span:span)/2f;coords[p].y=grid.getHeight()/3f;coords[p].pressure=1;coords[p].size=1;}
            MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,count,props,coords,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);
            assertTrue(grid.dispatchTouchEvent(event));event.recycle();});SystemClock.sleep(65);}
        i.waitForIdleSync();
    }
    private int columns(androidx.recyclerview.widget.RecyclerView grid){return ((androidx.recyclerview.widget.GridLayoutManager)grid.getLayoutManager()).getSpanCount();}
    @Test public void actualTwoFingerGridDensityAndCameraRecentCircle() throws Exception {
        assertTrue(Build.HARDWARE.equals("ranchu")||Build.HARDWARE.equals("goldfish"));
        com.fadcam.data.VideoIndexRepository.getInstance(i.getTargetContext()).deltaScan(com.fadcam.SharedPreferencesManager.getInstance(i.getTargetContext()));
        MainActivity camera=(MainActivity)i.startActivitySync(new Intent(i.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        com.fadcam.SharedPreferencesManager prefs=com.fadcam.SharedPreferencesManager.getInstance(camera);
        int original=prefs.getRecordsGridSpan();
        try {
            waitFor(()->camera.hasWindowFocus()&&camera.findViewById(R.id.personal_home_videos)!=null);
            waitFor(()->((com.google.android.material.button.MaterialButton)camera.findViewById(R.id.personal_home_videos)).getIconTint()==null);
            snapshot(camera,"camera-recent-circle");
            main(()->{com.google.android.material.button.MaterialButton button=camera.findViewById(R.id.personal_home_videos);
                assertEquals(192,button.getWidth());assertEquals(192,button.getHeight());assertEquals(192,button.getIconSize());
                camera.switchFragment(1,false);});
            waitFor(()->camera.findViewById(R.id.recycler_view_records)!=null);
            androidx.recyclerview.widget.RecyclerView grid=camera.findViewById(R.id.recycler_view_records);
            waitFor(()->grid!=null&&grid.isShown()&&grid.getChildCount()>0&&!grid.hasPendingAdapterUpdates());
            final androidx.recyclerview.widget.RecyclerView.Adapter<?> adapter=grid.getAdapter();
            AtomicReference<Integer> before=new AtomicReference<>();main(()->before.set(columns(grid)));snapshot(camera,"grid-before");
            pinch(grid,1000,180);waitFor(()->columns(grid)>before.get());
            AtomicReference<Integer> dense=new AtomicReference<>();main(()->dense.set(columns(grid)));snapshot(camera,"grid-pinched-in");
            pinch(grid,180,1000);waitFor(()->columns(grid)<dense.get());snapshot(camera,"grid-pinched-out");
            main(()->{assertSame(adapter,grid.getAdapter());assertEquals(columns(grid),prefs.getRecordsGridSpan());
                assertTrue("Pinch stays in Gallery and does not open any tile",camera.hasWindowFocus());});
            write(camera,"pinch-result",new JSONObject().put("initialColumns",before.get()).put("denseColumns",dense.get()).put("finalColumns",prefs.getRecordsGridSpan()).put("sameAdapter",true).put("phoneTouched",false).toString());
        } finally {main(()->{prefs.setRecordsGridSpan(original);camera.finish();});}
    }
}
