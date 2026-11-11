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
public class GalleryRepairDeviceTest {
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
    @Test public void realSensorRotationWithAutoOffAndLongVideoEndpointsAndGridReentry() throws Exception {
        assertTrue(Build.HARDWARE.equals("ranchu")||Build.HARDWARE.equals("goldfish"));autoRotationStillOff();
        MainActivity camera=null;VideoPlayerActivity viewer=null,reopened=null;
        Instrumentation.ActivityMonitor monitor=i.addMonitor(VideoPlayerActivity.class.getName(),null,false);
        try {
            camera=(MainActivity)i.startActivitySync(new Intent(i.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final MainActivity c=camera;
            waitFor(()->c.hasWindowFocus() && c.findViewById(R.id.personal_home_videos)!=null && c.findViewById(R.id.personal_home_videos).isEnabled());
            waitFor(()->c.getWindow().getDecorView().getHeight()>c.getWindow().getDecorView().getWidth());
            click(c,R.id.personal_home_videos);viewer=(VideoPlayerActivity)monitor.waitForActivityWithTimeout(30000);assertNotNull(viewer);
            final VideoPlayerActivity v=viewer;
            assertEquals(InstrumentationRegistry.getArguments().getString("expected_uri"),v.getIntent().getDataString());
            waitFor(()->v.hasWindowFocus() && player(v)!=null && player(v).getPlaybackState()==Player.STATE_READY && player(v).getDuration()>=2099000);
            main(()->((PlayerView)v.findViewById(R.id.player_view)).showController());
            AtomicReference<Boolean> playing=new AtomicReference<>();main(()->playing.set(player(v).getPlayWhenReady()));
            if(playing.get())click(v,androidx.media3.ui.R.id.exo_play_pause);
            waitFor(()->!player(v).getPlayWhenReady());
            GalleryMediaStrip strip=v.findViewById(R.id.gallery_media_strip);
            waitFor(()->strip.getChildCount()>0 && !strip.hasPendingAdapterUpdates());
            main(()->{boolean found=false;for(int n=0;n<strip.getChildCount();n++)if(strip.getChildAt(n).isSelected())found=strip.getChildAt(n).performClick();assertTrue(found);});
            waitFor(strip::isExpanded);main(()->player(v).seekTo(player(v).getDuration()/4));SystemClock.sleep(1300);
            drag(strip,650);waitFor(()->player(v).getCurrentPosition()==0);snapshot(v,"zero-portrait");assertSafeToolbar(v);
            for(int n=0;n<3;n++)drag(strip,-650);
            waitFor(()->player(v).getCurrentPosition()>=player(v).getDuration()-100);snapshot(v,"end-portrait");
            for(int n=0;n<3;n++)drag(strip,650);
            waitFor(()->player(v).getCurrentPosition()==0 && !player(v).getPlayWhenReady());
            marker(v,"request-landscape");waitFor(()->v.getWindow().getDecorView().getWidth()>v.getWindow().getDecorView().getHeight());
            autoRotationStillOff();assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,v.getRequestedOrientation());
            waitFor(()->player(v).getCurrentPosition()==0);snapshot(v,"zero-landscape");assertSafeToolbar(v);
            int firstRotation=v.getDisplay().getRotation();marker(v,"request-reverse");
            waitFor(()->v.getDisplay().getRotation()!=firstRotation && v.getWindow().getDecorView().getWidth()>v.getWindow().getDecorView().getHeight());
            autoRotationStillOff();snapshot(v,"zero-reverse-landscape");assertSafeToolbar(v);
            click(v,R.id.info_button);snapshot(v,"more-landscape");i.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            click(v,R.id.back_button);waitFor(()->c.hasWindowFocus() && c.findViewById(R.id.recycler_view_records)!=null && c.findViewById(R.id.recycler_view_records).isShown());
            waitFor(()->c.getWindow().getDecorView().getWidth()>c.getWindow().getDecorView().getHeight());autoRotationStillOff();snapshot(c,"grid-landscape");
            androidx.recyclerview.widget.RecyclerView grid=c.findViewById(R.id.recycler_view_records);waitFor(()->grid.getChildCount()>0);
            // A monitor can retain the earlier viewer's later onResume callback.
            // A fresh monitor here must observe the actual grid-selected new instance.
            i.removeMonitor(monitor);monitor=i.addMonitor(VideoPlayerActivity.class.getName(),null,false);
            main(()->{boolean found=false;for(int n=0;n<grid.getChildCount();n++){
                androidx.recyclerview.widget.RecyclerView.ViewHolder holder=grid.getChildViewHolder(grid.getChildAt(n));
                if(holder instanceof RecordsAdapter.RecordViewHolder && ((RecordsAdapter.RecordViewHolder)holder).currentUri!=null && ((RecordsAdapter.RecordViewHolder)holder).currentUri.endsWith("/GallerySeek_35min_20261026.mp4")){found=grid.getChildAt(n).performClick();break;}}
                assertTrue("Owned long video is selected from real grid",found);
            });
            reopened=(VideoPlayerActivity)monitor.waitForActivityWithTimeout(30000);assertNotNull(reopened);final VideoPlayerActivity r=reopened;
            waitFor(()->r.hasWindowFocus() && player(r)!=null && player(r).getPlaybackState()==Player.STATE_READY);
            assertTrue(r.getIntent().getBooleanExtra(VideoPlayerActivity.EXTRA_GALLERY_PREVIEW,false));assertNotNull(r.findViewById(R.id.gallery_media_strip));
            main(()->{player(r).pause();((PlayerView)r.findViewById(R.id.player_view)).showController();});
            snapshot(r,"grid-reopened-landscape");marker(r,"request-portrait");
            waitFor(()->r.getWindow().getDecorView().getHeight()>r.getWindow().getDecorView().getWidth());autoRotationStillOff();assertSafeToolbar(r);
            click(r,R.id.info_button);snapshot(r,"more-portrait");i.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        } finally {
            i.removeMonitor(monitor);final MainActivity c=camera;final VideoPlayerActivity v=viewer,r=reopened;
            main(()->{if(r!=null&&!r.isFinishing())r.finish();if(v!=null&&!v.isFinishing())v.finish();if(c!=null&&!c.isFinishing())c.finish();});
        }
    }
}
