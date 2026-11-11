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
public class GalleryMotionDeviceTest {
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

    private Object field(Object owner,String name){try{Class<?> type=owner instanceof Class?(Class<?>)owner:owner.getClass();java.lang.reflect.Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(owner instanceof Class?null:owner);}catch(Exception e){throw new RuntimeException(e);}}
    private Object service(){return field(com.fadcam.services.RecordingService.class,"liveInstance");}
    private View ownTile(androidx.recyclerview.widget.RecyclerView grid,String suffix){for(int n=0;n<grid.getChildCount();n++){
        View tile=grid.getChildAt(n);androidx.recyclerview.widget.RecyclerView.ViewHolder holder=grid.getChildViewHolder(tile);
        if(holder instanceof RecordsAdapter.RecordViewHolder&&((RecordsAdapter.RecordViewHolder)holder).currentUri!=null&&((RecordsAdapter.RecordViewHolder)holder).currentUri.endsWith(suffix))return tile;}return null;}
    private android.net.Uri published(String name){try(android.database.Cursor c=i.getTargetContext().getContentResolver().query(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,new String[]{"_id"},"_display_name=? AND relative_path=? AND is_pending=0",new String[]{name,"Movies/FadCam/"},null)){return c!=null&&c.moveToFirst()?android.content.ContentUris.withAppendedId(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,c.getLong(0)):null;}}
    private String sha(java.io.InputStream input)throws Exception{try(java.io.InputStream in=input){java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[16384];int count;while((count=in.read(buffer))!=-1)digest.update(buffer,0,count);StringBuilder out=new StringBuilder();for(byte b:digest.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}}
    @Test public void rotationTimelineSelectionAndActualPublicMove() throws Exception {
        assertTrue(Build.HARDWARE.equals("ranchu")||Build.HARDWARE.equals("goldfish"));
        MainActivity camera=null;VideoPlayerActivity viewer=null;android.net.Uri publicUri=null;
        Instrumentation.ActivityMonitor monitor=i.addMonitor(VideoPlayerActivity.class.getName(),null,false);
        String moving="QA_MOTION_MOVE_20261030.mp4";
        assertNull("Own unique destination must not preexist",published(moving));
        try {
            camera=(MainActivity)i.startActivitySync(new Intent(i.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final MainActivity c=camera;
            waitFor(()->c.hasWindowFocus()&&service()!=null&&field(service(),"cameraDevice")!=null&&field(service(),"captureSession")!=null&&field(service(),"glRecordingPipeline")!=null);
            Object owner=service(),device=field(owner,"cameraDevice"),session=field(owner,"captureSession"),pipeline=field(owner,"glRecordingPipeline"),surface=field(pipeline,"previewSurface");
            android.view.TextureView preview=c.findViewById(R.id.personal_home_preview);
            java.util.concurrent.atomic.AtomicInteger frames=new java.util.concurrent.atomic.AtomicInteger();
            main(()->{android.view.TextureView.SurfaceTextureListener original=preview.getSurfaceTextureListener();preview.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener(){
                public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture t,int w,int h){original.onSurfaceTextureAvailable(t,w,h);}
                public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture t,int w,int h){original.onSurfaceTextureSizeChanged(t,w,h);}
                public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture t){return original.onSurfaceTextureDestroyed(t);}
                public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture t){frames.incrementAndGet();original.onSurfaceTextureUpdated(t);}
            });});
            JSONArray rotationEvidence=new JSONArray();
            for(String orientation:new String[]{"landscape","reverse","portrait"}){
                int before=frames.get();long started=SystemClock.uptimeMillis();int prior=c.getDisplay().getRotation();marker(c,"request-"+orientation);
                waitFor(()->c.getDisplay().getRotation()!=prior&&frames.get()>=before+2);
                main(()->{assertSame(owner,service());assertSame(device,field(service(),"cameraDevice"));assertSame(session,field(service(),"captureSession"));assertSame(pipeline,field(service(),"glRecordingPipeline"));assertSame(surface,field(pipeline,"previewSurface"));});
                rotationEvidence.put(new JSONObject().put("orientation",orientation).put("settledMs",SystemClock.uptimeMillis()-started).put("newFrames",frames.get()-before));snapshot(c,"camera-"+orientation);
            }
            write(c,"camera-continuity",new JSONObject().put("sameCameraSessionAndGlSurface",true).put("transitions",rotationEvidence).toString());
            click(c,R.id.personal_home_videos);viewer=(VideoPlayerActivity)monitor.waitForActivityWithTimeout(30000);assertNotNull(viewer);final VideoPlayerActivity v=viewer;
            waitFor(()->v.hasWindowFocus()&&player(v)!=null&&player(v).getPlaybackState()==Player.STATE_READY&&player(v).getDuration()>52000);
            main(()->{player(v).pause();player(v).seekTo(5000);((PlayerView)v.findViewById(R.id.player_view)).showController();});SystemClock.sleep(1300);
            GalleryMediaStrip strip=v.findViewById(R.id.gallery_media_strip);waitFor(()->strip.getChildCount()>0&&!strip.hasPendingAdapterUpdates());
            main(()->{for(int n=0;n<strip.getChildCount();n++)if(strip.getChildAt(n).isSelected())assertTrue(strip.getChildAt(n).performClick());});waitFor(strip::isExpanded);
            for(int n=0;n<4;n++)drag(strip,-650);waitFor(()->player(v).getCurrentPosition()>=52900);
            main(()->{View selected=null;for(int n=0;n<strip.getChildCount();n++)if(strip.getChildAt(n).isSelected())selected=strip.getChildAt(n);assertNotNull(selected);assertEquals(strip.getWidth()/2,selected.getRight()-selected.getPaddingRight());});snapshot(v,"timeline-end-bounded");
            for(int n=0;n<4;n++)drag(strip,650);waitFor(()->player(v).getCurrentPosition()==0);snapshot(v,"timeline-zero-bounded");
            main(()->{android.widget.TextView total=v.findViewById(androidx.media3.ui.R.id.exo_duration);assertEquals(1,total.getLayout().getLineCount());assertEquals(0,total.getLayout().getEllipsisCount(0));assertTrue(total.getText().toString().endsWith("53"));assertFalse(v.findViewById(R.id.material_time_slider).isHapticFeedbackEnabled());});
            Runnable updater=(Runnable)field(v,"timelineUpdateRunnable");android.os.Handler updateHandler=(android.os.Handler)field(v,"timelineUpdateHandler");
            click(v,R.id.info_button);snapshot(v,"native-player-menu");i.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);click(v,R.id.back_button);
            waitFor(()->c.hasWindowFocus()&&c.findViewById(R.id.recycler_view_records)!=null&&c.findViewById(R.id.recycler_view_records).isShown());
            main(()->assertFalse("Closed viewer leaves no200ms updater behind",updateHandler.hasCallbacks(updater)));
            androidx.recyclerview.widget.RecyclerView grid=c.findViewById(R.id.recycler_view_records);waitFor(()->ownTile(grid,moving)!=null);
            main(()->assertTrue(ownTile(grid,moving).performLongClick()));waitFor(()->c.findViewById(R.id.gallery_selection_bar).isShown());snapshot(c,"selection-mode");
            i.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);waitFor(()->c.hasWindowFocus()&&!c.findViewById(R.id.gallery_selection_bar).isShown()&&c.findViewById(R.id.gallery_tabs).isShown());
            main(()->assertTrue(ownTile(grid,moving).performLongClick()));
            AtomicReference<androidx.appcompat.widget.PopupMenu> menu=new AtomicReference<>();main(()->{RecordsFragment fragment=null;for(androidx.fragment.app.Fragment f:c.getSupportFragmentManager().getFragments())if(f instanceof RecordsFragment)fragment=(RecordsFragment)f;assertNotNull(fragment);menu.set(fragment.createGallerySelectionMenu(c.findViewById(R.id.gallery_more)));menu.get().show();});snapshot(c,"selection-more");
            main(()->{menu.get().dismiss();assertTrue(menu.get().getMenu().performIdentifierAction(2,0));});
            waitFor(()->published(moving)!=null);publicUri=published(moving);
            assertEquals(InstrumentationRegistry.getArguments().getString("fixture_sha"),sha(i.getTargetContext().getContentResolver().openInputStream(publicUri)));
            java.io.File source=new java.io.File(android.net.Uri.parse(InstrumentationRegistry.getArguments().getString("moving_uri")).getPath());waitFor(()->!source.exists());
            write(c,"public-move",new JSONObject().put("publishedUri",publicUri.toString()).put("sha256",InstrumentationRegistry.getArguments().getString("fixture_sha")).put("sourceRemovedAfterPublish",!source.exists()).put("pending",false).toString());snapshot(c,"after-public-move");
        } finally {
            if(publicUri!=null)i.getTargetContext().getContentResolver().delete(publicUri,null,null);
            i.removeMonitor(monitor);final MainActivity c=camera;final VideoPlayerActivity v=viewer;main(()->{if(v!=null&&!v.isFinishing())v.finish();if(c!=null&&!c.isFinishing())c.finish();});
        }
    }
    private android.widget.TextView text(View view,String expected) {
        if(view instanceof android.widget.TextView&&expected.contentEquals(((android.widget.TextView)view).getText()))return (android.widget.TextView)view;
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int n=0;n<group.getChildCount();n++){android.widget.TextView found=text(group.getChildAt(n),expected);if(found!=null)return found;}}
        return null;
    }
    @Test public void selectedMenuContrastAndSuccessfulMoveHistoryStayUsable() throws Exception {
        assertTrue(Build.HARDWARE.equals("ranchu")||Build.HARDWARE.equals("goldfish"));
        MainActivity c=(MainActivity)i.startActivitySync(new Intent(i.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(GalleryViewerActions.EXTRA_OPEN_GALLERY,true));
        try {
            waitFor(()->c.hasWindowFocus()&&c.findViewById(R.id.recycler_view_records)!=null&&c.findViewById(R.id.recycler_view_records).isShown());
            androidx.recyclerview.widget.RecyclerView grid=c.findViewById(R.id.recycler_view_records);waitFor(()->ownTile(grid,"QA_MOTION_NEIGHBOR_20261030.mp4")!=null);
            main(()->assertFalse("Successful move must not leave a permanent OK card",c.findViewById(R.id.records_header_delete_dock).isShown()));snapshot(c,"completed-move-no-dock");
            main(()->assertTrue(ownTile(grid,"QA_MOTION_NEIGHBOR_20261030.mp4").performLongClick()));
            main(()->{RecordsFragment fragment=null;for(androidx.fragment.app.Fragment f:c.getSupportFragmentManager().getFragments())if(f instanceof RecordsFragment)fragment=(RecordsFragment)f;assertNotNull(fragment);fragment.createGallerySelectionMenu(c.findViewById(R.id.gallery_more)).show();});
            i.waitForIdleSync();SystemClock.sleep(300);
            main(()->{boolean found=false;for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews()){
                android.widget.TextView label=text(root,c.getString(R.string.video_menu_save_move));if(label!=null&&label.isShown()){assertTrue(label.isEnabled());assertEquals(0xFF010102,label.getCurrentTextColor());found=true;}}
                assertTrue("Real popup row is present and readable",found);});snapshot(c,"selection-more-readable");
            i.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            waitFor(c::hasWindowFocus);
            i.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            waitFor(()->c.hasWindowFocus()&&!c.findViewById(R.id.gallery_selection_bar).isShown());
        } finally {main(c::finish);}
    }
}
