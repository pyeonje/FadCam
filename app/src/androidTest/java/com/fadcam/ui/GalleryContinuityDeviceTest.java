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
public class GalleryContinuityDeviceTest {
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

    private void swipe(VideoPlayerActivity viewer,float dx) throws Exception {
        View surface=viewer.findViewById(R.id.player_view);long start=SystemClock.uptimeMillis();
        for(int n=0;n<=10;n++){final int step=n;main(()->{
            MotionEvent event=MotionEvent.obtain(start,SystemClock.uptimeMillis(),step==0?MotionEvent.ACTION_DOWN:step==10?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE,
                    surface.getWidth()/2f-dx/2f+dx*step/10f,surface.getHeight()/2f,0);
            assertTrue(surface.dispatchTouchEvent(event));event.recycle();});SystemClock.sleep(30);}
    }
    private GalleryVideoFilmstrip currentFrames(GalleryMediaStrip strip){
        for(int n=0;n<strip.getChildCount();n++)if(strip.getChildAt(n).isSelected())return (GalleryVideoFilmstrip)((android.widget.FrameLayout)strip.getChildAt(n)).getChildAt(1);
        return null;
    }
    private void assertMediaGrid(androidx.recyclerview.widget.RecyclerView grid){
        main(()->{for(int n=0;n<grid.getChildCount();n++){
            View tile=grid.getChildAt(n);
            if(!(grid.getChildViewHolder(tile) instanceof RecordsAdapter.RecordViewHolder))continue;
            assertEquals("Every grid tile stays media-only",View.GONE,tile.findViewById(R.id.record_content_container).getVisibility());
            assertEquals(View.GONE,tile.findViewById(R.id.text_view_serial_number).getVisibility());
            assertTrue(tile.hasOnClickListeners());
        }});
    }
    @Test public void realSwipeZeroStartFrameReuseAndWarmGrid() throws Exception {
        assertTrue(Build.HARDWARE.equals("ranchu")||Build.HARDWARE.equals("goldfish"));
        MainActivity camera=null;VideoPlayerActivity viewer=null;
        Instrumentation.ActivityMonitor monitor=i.addMonitor(VideoPlayerActivity.class.getName(),null,false);
        try {
            camera=(MainActivity)i.startActivitySync(new Intent(i.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final MainActivity c=camera;
            waitFor(()->c.hasWindowFocus()&&c.findViewById(R.id.personal_camera_mode_video)!=null);
            main(()->{
                android.widget.LinearLayout modes=c.findViewById(R.id.personal_camera_mode);assertEquals(3,modes.getChildCount());
                assertTrue(c.findViewById(R.id.personal_camera_mode_video).performClick());
                assertEquals(View.GONE,((View)c.findViewById(R.id.personal_pro_items).getParent()).getVisibility());
            });snapshot(c,"camera-video-three-modes");
            click(c,R.id.personal_camera_mode_pro_video);snapshot(c,"camera-pro-three-modes");
            click(c,R.id.personal_home_videos);viewer=(VideoPlayerActivity)monitor.waitForActivityWithTimeout(30000);assertNotNull(viewer);final VideoPlayerActivity v=viewer;
            String first=InstrumentationRegistry.getArguments().getString("expected_uri");String second=InstrumentationRegistry.getArguments().getString("second_uri");
            waitFor(()->v.hasWindowFocus()&&player(v)!=null&&player(v).getPlaybackState()==Player.STATE_READY);
            assertEquals(first,v.getIntent().getDataString());
            main(()->{player(v).pause();player(v).seekTo(90000);((PlayerView)v.findViewById(R.id.player_view)).showController();});
            GalleryMediaStrip strip=v.findViewById(R.id.gallery_media_strip);
            waitFor(()->strip.getChildCount()>1&&!strip.hasPendingAdapterUpdates()&&currentFrames(strip)!=null&&currentFrames(strip).hasAnyFrame());
            AtomicReference<android.graphics.drawable.Drawable> frame=new AtomicReference<>();
            main(()->{frame.set(((android.widget.ImageView)currentFrames(strip).getChildAt(0)).getDrawable());for(int n=0;n<strip.getChildCount();n++)if(strip.getChildAt(n).isSelected())assertTrue(strip.getChildAt(n).performClick());});
            waitFor(strip::isExpanded);
            main(()->{assertSame("Expansion reuses prepared frame without blanking",frame.get(),((android.widget.ImageView)currentFrames(strip).getChildAt(0)).getDrawable());assertEquals(View.GONE,v.findViewById(androidx.media3.ui.R.id.exo_buffering).getVisibility());});
            snapshot(v,"expanded-prepared-timeline");
            java.util.concurrent.atomic.AtomicLong rendered=new java.util.concurrent.atomic.AtomicLong(-1);
            androidx.media3.exoplayer.video.VideoFrameMetadataListener metadata=(pts,release,format,mediaFormat)->rendered.set(pts);
            main(()->((androidx.media3.exoplayer.ExoPlayer)player(v)).setVideoFrameMetadataListener(metadata));
            // Hold the finger down after moving, so this proves a decoded frame
            // changes during the drag rather than only after its release.
            long down=SystemClock.uptimeMillis();
            for(int n=0;n<=6;n++){final int step=n;main(()->{
                MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),step==0?MotionEvent.ACTION_DOWN:MotionEvent.ACTION_MOVE,strip.getWidth()/2f+step*60,strip.getHeight()/2f,0);
                strip.dispatchTouchEvent(event);event.recycle();});SystemClock.sleep(80);}
            waitFor(()->rendered.get()>=0&&rendered.get()<90_000_000);
            snapshot(v,"during-drag-decoded-frame");
            main(()->{MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,strip.getWidth()/2f+360,strip.getHeight()/2f,0);strip.dispatchTouchEvent(event);event.recycle();});
            main(()->((androidx.media3.exoplayer.ExoPlayer)player(v)).clearVideoFrameMetadataListener(metadata));
            drag(strip,650);waitFor(()->player(v).getCurrentPosition()==0);snapshot(v,"timeline-zero");
            main(()->{player(v).seekTo(90000);com.fadcam.SharedPreferencesManager.getInstance(v).setSavedPlaybackPositionMs(second,90000);});
            swipe(v,600);
            waitFor(()->v.hasWindowFocus()&&second.equals(v.getIntent().getDataString())&&player(v).getPlaybackState()==Player.STATE_READY);
            main(()->{assertEquals(second,player(v).getCurrentMediaItem().localConfiguration.uri.toString());assertTrue("Other media starts at zero, not saved90s",player(v).getCurrentPosition()<2000);player(v).pause();});snapshot(v,"swipe-next-zero");
            swipe(v,-600);waitFor(()->first.equals(v.getIntent().getDataString())&&player(v).getPlaybackState()==Player.STATE_READY);
            main(()->{assertTrue(player(v).getCurrentPosition()<2000);player(v).pause();});snapshot(v,"swipe-previous-zero");
            click(v,R.id.back_button);waitFor(()->c.hasWindowFocus()&&c.findViewById(R.id.recycler_view_records)!=null&&c.findViewById(R.id.recycler_view_records).isShown());
            androidx.recyclerview.widget.RecyclerView grid=c.findViewById(R.id.recycler_view_records);waitFor(()->grid.getChildCount()>0);assertMediaGrid(grid);snapshot(c,"gallery-grid");
            i.removeMonitor(monitor);monitor=i.addMonitor(VideoPlayerActivity.class.getName(),null,false);
            main(()->{boolean found=false;for(int n=0;n<grid.getChildCount();n++){
                androidx.recyclerview.widget.RecyclerView.ViewHolder holder=grid.getChildViewHolder(grid.getChildAt(n));
                if(holder instanceof RecordsAdapter.RecordViewHolder&&((RecordsAdapter.RecordViewHolder)holder).currentUri!=null&&((RecordsAdapter.RecordViewHolder)holder).currentUri.endsWith("GallerySeek_35min_20261026.mp4")){found=grid.getChildAt(n).performClick();break;}}
                assertTrue(found);});
            VideoPlayerActivity reopened=(VideoPlayerActivity)monitor.waitForActivityWithTimeout(30000);assertNotNull(reopened);
            waitFor(()->reopened.hasWindowFocus());assertTrue(reopened.getIntent().getBooleanExtra("gallery_return_to_grid",false));
            main(()->assertTrue(reopened.findViewById(R.id.back_button).performClick()));
            waitFor(c::hasWindowFocus);assertTrue(reopened.isFinishing());assertMediaGrid(grid);snapshot(c,"warm-grid-return");
        }finally{ i.removeMonitor(monitor);final MainActivity c=camera;final VideoPlayerActivity v=viewer;main(()->{if(v!=null&&!v.isFinishing())v.finish();if(c!=null&&!c.isFinishing())c.finish();});}
    }
}
