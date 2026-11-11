package com.fadcam.ui;

import static org.junit.Assert.*;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fadcam.R;
import com.fadcam.MainActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual owned-emulator input/window test. No mocked views, image matrices or camera. */
@RunWith(AndroidJUnit4.class)
public class SamsungParityDeviceTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();

    @Test public void realVideoPlaybackPauseAndRotationKeepPlayerAndPosition() throws Exception {
        File movie=new File(instrumentation.getTargetContext().getExternalFilesDir(null),
                "FadCam/Camera/Back/FadCam_20261022_111016.mp4");
        assertTrue("Owned emulator recording required",movie.isFile());
        VideoPlayerActivity activity=(VideoPlayerActivity)instrumentation.startActivitySync(
                new Intent(instrumentation.getTargetContext(),VideoPlayerActivity.class)
                        .setData(Uri.fromFile(movie)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_0);
            androidx.media3.ui.PlayerView view=activity.findViewById(R.id.player_view);
            waitFor(()->activity.hasWindowFocus() && view.getPlayer()!=null
                    && view.getPlayer().getPlaybackState()==androidx.media3.common.Player.STATE_READY);
            androidx.media3.common.Player player=view.getPlayer();
            instrumentation.runOnMainSync(()->{player.seekTo(5000);player.play();view.showController();});
            waitFor(player::isPlaying);SystemClock.sleep(400);capture("video-playing-portrait");
            instrumentation.runOnMainSync(()->{
                view.showController();android.view.View button=view.findViewById(androidx.media3.ui.R.id.exo_play_pause);
                assertNotNull(button);assertTrue(button.performClick());
            });
            waitFor(()->!player.getPlayWhenReady());
            AtomicReference<Long> position=new AtomicReference<>();
            instrumentation.runOnMainSync(()->position.set(player.getCurrentPosition()));
            long paused=position.get();assertTrue(paused>=5000);
            capture("video-paused-portrait");
            instrumentation.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_90);
            waitFor(()->view.getWidth()>view.getHeight());SystemClock.sleep(300);
            instrumentation.runOnMainSync(()->{
                assertSame(player,view.getPlayer());assertFalse(player.getPlayWhenReady());
                assertEquals(paused,player.getCurrentPosition(),200);
                view.showController();
            });capture("video-paused-landscape");
            instrumentation.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_0);
            waitFor(()->view.getWidth()<view.getHeight());
            instrumentation.runOnMainSync(()->{
                assertSame(player,view.getPlayer());assertFalse(player.getPlayWhenReady());
                assertEquals(paused,player.getCurrentPosition(),200);view.showController();
                assertTrue(view.findViewById(androidx.media3.ui.R.id.exo_play_pause).performClick());
            });waitFor(player::isPlaying);
            instrumentation.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_90);
            waitFor(()->view.getWidth()>view.getHeight());SystemClock.sleep(400);
            instrumentation.runOnMainSync(()->{
                assertSame(player,view.getPlayer());assertTrue(player.isPlaying());
                assertTrue(player.getCurrentPosition()>paused);view.showController();
            });capture("video-playing-landscape");
        } finally {
            instrumentation.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_UNFREEZE);
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    @Test public void inspectActualGalleryLayoutInputs() throws Exception {
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(
                new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            boolean startLandscape=InstrumentationRegistry.getArguments().getString("start_landscape","false").equals("true");
            if(startLandscape)instrumentation.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_90);
            else instrumentation.runOnMainSync(()->activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
            waitFor(()->activity.hasWindowFocus() && activity.findViewById(R.id.personal_home_videos)!=null);
            if(startLandscape)waitFor(()->activity.getWindow().getDecorView().getWidth()>activity.getWindow().getDecorView().getHeight());
            instrumentation.runOnMainSync(()->activity.findViewById(R.id.personal_home_videos).performClick());
            waitFor(()->findRecords(activity)!=null && findRecords(activity).getView()!=null);
            SystemClock.sleep(800);
            boolean selection=InstrumentationRegistry.getArguments().getString("exercise_selection","false").equals("true");
            AtomicReference<String> anchor=new AtomicReference<>();
            if(selection) {
                waitFor(()->visibleMediaHolder(activity)!=null);
                instrumentation.runOnMainSync(()->activity.findViewById(R.id.btn_filter_checklist).performClick());
                SystemClock.sleep(600);
                androidx.recyclerview.widget.RecyclerView recycler=galleryRecycler(activity);
                android.graphics.Rect touchArea=new android.graphics.Rect();
                instrumentation.runOnMainSync(()->{
                    assertTrue(recycler.getGlobalVisibleRect(touchArea));
                    android.view.View navigation=activity.findViewById(R.id.bottom_navigation);
                    android.graphics.Rect navRect=new android.graphics.Rect();
                    if(navigation!=null && navigation.isShown() && navigation.getGlobalVisibleRect(navRect))
                        touchArea.bottom=Math.min(touchArea.bottom,navRect.top);
                });
                assertTrue("Gesture stays within the visible list above navigation",touchArea.height()>120);
                for(int i=0;i<3;i++)drag(touchArea.exactCenterX(),touchArea.bottom-30,touchArea.exactCenterX(),touchArea.top+30);
                SystemClock.sleep(600);
                saveGalleryObservation("touch-scroll",galleryInputs(activity),false);capture("gallery-touch-scroll");
                instrumentation.runOnMainSync(()->assertTrue("Real touch scroll moved away from first media row",
                        ((androidx.recyclerview.widget.GridLayoutManager)recycler.getLayoutManager()).findFirstVisibleItemPosition()>=5));
                // Exclude detached animation/skeleton rows sharing the same view ID.
                waitFor(()->visibleMediaHolder(activity)!=null);
                instrumentation.runOnMainSync(()->{
                    RecordsAdapter.RecordViewHolder media=visibleMediaHolder(activity);anchor.set(media.currentUri);
                    assertTrue(media.imageViewThumbnail.performClick());
                });
            }
            // The real toolbar uses a300ms slot animation after selection.
            SystemClock.sleep(600);
            String created=galleryInputs(activity);
            saveGalleryObservation("created",created,false);
            System.out.println("GALLERY_CREATED="+created);capture("gallery-created-in-landscape");
            if(selection)assertGallerySelection(created,startLandscape?4:2);
            if(startLandscape) {
                capture("gallery-created-in-landscape");
                instrumentation.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_0);
                waitFor(()->activity.getWindow().getDecorView().getWidth()<activity.getWindow().getDecorView().getHeight());
                SystemClock.sleep(800);
            }
            String portrait=galleryInputs(activity);capture("gallery-inputs-portrait");
            saveGalleryObservation("portrait",portrait,true);
            if(selection){assertGallerySelection(portrait,2);assertTrue("Scroll anchor visible after portrait rotation",visibleMediaContains(activity,anchor.get()));}
            if(startLandscape)instrumentation.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_90);
            else instrumentation.runOnMainSync(()->activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            waitFor(()->activity.getWindow().getDecorView().getWidth()>activity.getWindow().getDecorView().getHeight());
            SystemClock.sleep(1200);
            String landscape=galleryInputs(activity);capture("gallery-inputs-landscape");
            saveGalleryObservation("landscape",landscape,true);
            if(selection){assertGallerySelection(landscape,4);assertTrue("Scroll anchor visible after landscape rotation",visibleMediaContains(activity,anchor.get()));}
            if(selection && !startLandscape) {
                instrumentation.runOnMainSync(()->activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
                waitFor(()->activity.getWindow().getDecorView().getWidth()<activity.getWindow().getDecorView().getHeight());SystemClock.sleep(1000);
                String returned=galleryInputs(activity);saveGalleryObservation("returned-portrait",returned,true);capture("gallery-returned-portrait");
                assertGallerySelection(returned,2);assertTrue("Scroll anchor visible after returning portrait",visibleMediaContains(activity,anchor.get()));
            }
            File folder=new File(instrumentation.getTargetContext().getExternalFilesDir(null),"qa-runtime-evidence");
            try(FileOutputStream output=new FileOutputStream(new File(folder,"gallery-layout-inputs.jsonl"))) {
                output.write((created+"\n"+portrait+"\n"+landscape+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            System.out.println("GALLERY_INPUTS="+created+";"+portrait+";"+landscape);
        } finally {instrumentation.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_UNFREEZE);instrumentation.runOnMainSync(activity::finish);}
    }
    private RecordsFragment findRecords(MainActivity activity) {
        for(androidx.fragment.app.Fragment f:activity.getSupportFragmentManager().getFragments())if(f instanceof RecordsFragment)return(RecordsFragment)f;
        return null;
    }
    private RecordsAdapter.RecordViewHolder visibleMediaHolder(MainActivity activity) {
        try {
            androidx.recyclerview.widget.RecyclerView recycler=galleryRecycler(activity);
            if(recycler==null || !(recycler.getAdapter() instanceof RecordsAdapter)
                    || ((RecordsAdapter)recycler.getAdapter()).isSkeletonMode())return null;
            androidx.recyclerview.widget.GridLayoutManager layout=(androidx.recyclerview.widget.GridLayoutManager)recycler.getLayoutManager();
            for(int pos=layout.findFirstVisibleItemPosition();pos<=layout.findLastVisibleItemPosition();pos++) {
                androidx.recyclerview.widget.RecyclerView.ViewHolder row=recycler.findViewHolderForAdapterPosition(pos);
                if(!(row instanceof RecordsAdapter.RecordViewHolder))continue;
                RecordsAdapter.RecordViewHolder media=(RecordsAdapter.RecordViewHolder)row;
                android.graphics.Rect rect=new android.graphics.Rect();
                if(media.getBindingAdapterPosition()!=androidx.recyclerview.widget.RecyclerView.NO_POSITION
                        && media.currentUri!=null && media.itemView.getTag(R.id.skeleton_tag)==null
                        && media.itemView.isShown() && media.itemView.getGlobalVisibleRect(rect) && !rect.isEmpty()
                        && media.imageViewThumbnail.hasOnClickListeners())return media;
            }
            return null;
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private androidx.recyclerview.widget.RecyclerView galleryRecycler(MainActivity activity) {
        try {
            java.lang.reflect.Field field=RecordsFragment.class.getDeclaredField("recyclerView");field.setAccessible(true);
            return (androidx.recyclerview.widget.RecyclerView)field.get(findRecords(activity));
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private boolean visibleMediaContains(MainActivity activity,String uri) {
        AtomicReference<Boolean> found=new AtomicReference<>(false);
        instrumentation.runOnMainSync(()->{
            androidx.recyclerview.widget.RecyclerView recycler=galleryRecycler(activity);
            androidx.recyclerview.widget.GridLayoutManager layout=(androidx.recyclerview.widget.GridLayoutManager)recycler.getLayoutManager();
            for(int pos=layout.findFirstVisibleItemPosition();pos<=layout.findLastVisibleItemPosition();pos++) {
                androidx.recyclerview.widget.RecyclerView.ViewHolder row=recycler.findViewHolderForAdapterPosition(pos);
                if(row instanceof RecordsAdapter.RecordViewHolder && uri.equals(((RecordsAdapter.RecordViewHolder)row).currentUri))found.set(true);
            }
        });return found.get();
    }

    @Test public void landscapeDuplicateTapBackgroundReentryAndStopWhilePaused() throws Exception {
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(
                new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_90);
            waitFor(()->activity.hasWindowFocus() && activity.getWindow().getDecorView().getWidth()>activity.getWindow().getDecorView().getHeight()
                    && activity.findViewById(R.id.personal_home_record)!=null && activity.findViewById(R.id.personal_home_record).isEnabled());
            instrumentation.runOnMainSync(()->{
                android.view.View record=activity.findViewById(R.id.personal_home_record);record.performClick();record.performClick();
            });
            waitFor(()->com.fadcam.services.RecordingService.getSessionState()==com.fadcam.RecordingState.IN_PROGRESS
                    && activity.findViewById(R.id.personal_home_pause).isEnabled());
            SystemClock.sleep(1200);assertTrue(activity.getWindow().getDecorView().getWidth()>activity.getWindow().getDecorView().getHeight());
            capture("recording-landscape-real");
            instrumentation.runOnMainSync(()->assertTrue(activity.moveTaskToBack(true)));
            instrumentation.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_0);
            SystemClock.sleep(1200);
            assertEquals(com.fadcam.RecordingState.IN_PROGRESS,com.fadcam.services.RecordingService.getSessionState());
            instrumentation.getTargetContext().startActivity(new Intent(instrumentation.getTargetContext(),MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            waitFor(()->activity.hasWindowFocus() && activity.findViewById(R.id.personal_home_pause).isEnabled());
            waitFor(()->activity.getWindow().getDecorView().getWidth()>activity.getWindow().getDecorView().getHeight());
            assertEquals(com.fadcam.RecordingState.IN_PROGRESS,com.fadcam.services.RecordingService.getSessionState());
            capture("recording-landscape-reentry");
            instrumentation.runOnMainSync(()->{
                android.view.View pause=activity.findViewById(R.id.personal_home_pause);pause.performClick();pause.performClick();
            });
            waitFor(()->com.fadcam.services.RecordingService.getSessionState()==com.fadcam.RecordingState.PAUSED
                    && activity.findViewById(R.id.personal_home_pause).isEnabled());
            assertTrue(activity.getWindow().getDecorView().getWidth()>activity.getWindow().getDecorView().getHeight());
            com.fadcam.SharedPreferencesManager prefs=com.fadcam.SharedPreferencesManager.getInstance(instrumentation.getTargetContext());
            String repairEvents=java.util.Arrays.deepToString(prefs.getRecordingRepairEvents().toArray());
            java.util.concurrent.CountDownLatch scanDone=new java.util.concurrent.CountDownLatch(1);
            com.fadcam.services.RecordingService.runSelfHealingScan(instrumentation.getTargetContext(),scanDone::countDown);
            assertTrue("Paused scan finishes without touching active file",scanDone.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(repairEvents,java.util.Arrays.deepToString(prefs.getRecordingRepairEvents().toArray()));
            capture("paused-landscape-real");
            instrumentation.runOnMainSync(()->activity.findViewById(R.id.personal_home_record).performClick());
            waitFor(()->com.fadcam.services.RecordingService.getSessionState()==com.fadcam.RecordingState.NONE
                    && !com.fadcam.services.RecordingService.isRecordingFinalizing()
                    && !com.fadcam.services.RecordingService.isCameraCleanupInProgress()
                    && activity.findViewById(R.id.personal_home_record).isEnabled());
            assertEquals("Normal paused-stop must not create an abandoned-file repair event",repairEvents,
                    java.util.Arrays.deepToString(prefs.getRecordingRepairEvents().toArray()));
            capture("stopped-from-paused-landscape");
        } finally {
            if(com.fadcam.services.RecordingService.hasActiveSession())instrumentation.runOnMainSync(()->{
                activity.startService(new Intent(activity,com.fadcam.services.RecordingService.class)
                        .setAction(com.fadcam.Constants.INTENT_ACTION_STOP_RECORDING));
            });
            instrumentation.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_UNFREEZE);
            instrumentation.runOnMainSync(activity::finish);
        }
    }
    private void assertGallerySelection(String value,int columns) throws Exception {
        org.json.JSONObject json=new org.json.JSONObject(value);
        assertEquals(columns,json.getInt("layoutSpan"));assertEquals(1,json.getInt("selectedCount"));
        assertTrue(value,json.getString("title").startsWith("1 selected"));
        assertFalse("Adapter changes must be laid out",json.getBoolean("pendingUpdates"));
        org.json.JSONArray rows=json.getJSONArray("children");
        for(int i=0;i<rows.length();i++) {
            assertTrue("Actual child columns must match the requested span",rows.getJSONObject(i).getInt("spanIndex")<columns);
            assertTrue("Actual rows must have current adapter bindings",rows.getJSONObject(i).getInt("bindingPosition")>=0);
        }
    }
    private void saveGalleryObservation(String phase,String value,boolean append) throws Exception {
        File folder=new File(instrumentation.getTargetContext().getExternalFilesDir(null),"qa-runtime-evidence");
        assertTrue(folder.isDirectory()||folder.mkdirs());
        try(FileOutputStream output=new FileOutputStream(new File(folder,"gallery-observations.jsonl"),append)) {
            output.write(("{\"phase\":\""+phase+"\",\"inputs\":"+value+"}\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    private String galleryInputs(MainActivity activity) {
        AtomicReference<String> result=new AtomicReference<>();
        instrumentation.runOnMainSync(()->{
            try {
                RecordsFragment fragment=findRecords(activity);
                java.lang.reflect.Field recyclerField=RecordsFragment.class.getDeclaredField("recyclerView");recyclerField.setAccessible(true);
                androidx.recyclerview.widget.RecyclerView recycler=(androidx.recyclerview.widget.RecyclerView)recyclerField.get(fragment);
                org.json.JSONObject json=new org.json.JSONObject();
                for(String name:new String[]{"currentGridSpan","displayedGridSpan"}) {
                    java.lang.reflect.Field field=RecordsFragment.class.getDeclaredField(name);field.setAccessible(true);json.put(name,field.get(fragment));
                }
                android.content.res.Configuration c=fragment.getResources().getConfiguration();
                json.put("configWidthDp",c.screenWidthDp);json.put("configHeightDp",c.screenHeightDp);json.put("density",fragment.getResources().getDisplayMetrics().density);
                json.put("recyclerWidth",recycler.getWidth());json.put("recyclerHeight",recycler.getHeight());
                json.put("paddingLeft",recycler.getPaddingLeft());json.put("paddingRight",recycler.getPaddingRight());
                json.put("layoutSpan",((androidx.recyclerview.widget.GridLayoutManager)recycler.getLayoutManager()).getSpanCount());
                androidx.recyclerview.widget.GridLayoutManager layout=(androidx.recyclerview.widget.GridLayoutManager)recycler.getLayoutManager();
                json.put("firstVisible",layout.findFirstVisibleItemPosition());json.put("lastVisible",layout.findLastVisibleItemPosition());
                json.put("pendingUpdates",recycler.hasPendingAdapterUpdates());json.put("layoutRequested",recycler.isLayoutRequested());
                json.put("computingLayout",recycler.isComputingLayout());
                org.json.JSONArray children=new org.json.JSONArray();
                for(int i=0;i<recycler.getChildCount();i++) {
                    android.view.View child=recycler.getChildAt(i);
                    androidx.recyclerview.widget.RecyclerView.ViewHolder holder=recycler.getChildViewHolder(child);
                    org.json.JSONObject row=new org.json.JSONObject();
                    row.put("bindingPosition",holder.getBindingAdapterPosition());row.put("layoutPosition",holder.getLayoutPosition());
                    row.put("left",child.getLeft());row.put("top",child.getTop());row.put("right",child.getRight());row.put("bottom",child.getBottom());
                    if(child.getLayoutParams() instanceof androidx.recyclerview.widget.GridLayoutManager.LayoutParams)
                        row.put("spanIndex",((androidx.recyclerview.widget.GridLayoutManager.LayoutParams)child.getLayoutParams()).getSpanIndex());
                    children.put(row);
                }
                json.put("children",children);
                java.lang.reflect.Field selected=RecordsFragment.class.getDeclaredField("selectedUris");selected.setAccessible(true);
                json.put("selectedCount",((java.util.Collection<?>)selected.get(fragment)).size());
                json.put("title",((android.widget.TextView)fragment.requireView().findViewById(R.id.title_text)).getText());
                java.lang.reflect.Method resolve=RecordsFragment.class.getDeclaredMethod("resolveGridSpan");resolve.setAccessible(true);json.put("resolvedNow",resolve.invoke(fragment));
                json.put("rootWidth",activity.getWindow().getDecorView().getWidth());json.put("rootHeight",activity.getWindow().getDecorView().getHeight());
                result.set(json.toString());
            } catch(Exception error){throw new RuntimeException(error);}
        });return result.get();
    }

    @Test public void realPinchPanAndWindowRotationKeepPhotoViewport() throws Exception {
        File image=new File(instrumentation.getTargetContext().getExternalFilesDir(null),
                "FadCam/Camera/QA/Parity_QA_14.jpg");
        assertTrue("Synthetic fixture required",image.isFile());
        ImageViewerActivity activity=(ImageViewerActivity)instrumentation.startActivitySync(
                new Intent(instrumentation.getTargetContext(),ImageViewerActivity.class)
                        .setData(Uri.fromFile(image)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(()->activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
            ZoomableImageView view=activity.findViewById(R.id.imageViewerImage);
            waitFor(()->activity.hasWindowFocus() && view.getWidth()<view.getHeight() && view.getDrawable()!=null && view.captureViewport()!=null);
            SystemClock.sleep(600);
            capture("photo-before-pinch");
            java.util.concurrent.atomic.AtomicInteger touchEvents=new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger multiEvents=new java.util.concurrent.atomic.AtomicInteger();
            instrumentation.runOnMainSync(()->view.setOnTouchListener((v,event)->{
                touchEvents.incrementAndGet();if(event.getPointerCount()>1)multiEvents.incrementAndGet();return false;
            }));
            // Span must pass Android's density-dependent scaling threshold before
            // measurable zoom begins; keep both fingers inside this1080pxwindow.
            pinch(view.getWidth()*.5f,view.getHeight()*.5f,60,440);
            SystemClock.sleep(200);
            float[] zoomed=viewport(view);
            capture("photo-after-pinch");
            assertTrue("Real two-pointer input must zoom; events="+touchEvents.get()+", multi="+multiEvents.get()+", viewport="+java.util.Arrays.toString(zoomed),zoomed[0]>1.5f);
            drag(view.getWidth()*.5f,view.getHeight()*.5f,view.getWidth()*.5f+120,view.getHeight()*.5f-100);
            float[] before=viewport(view);
            capture("photo-pinch-pan-portrait");
            instrumentation.runOnMainSync(()->activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            waitFor(()->view.getWidth()>view.getHeight());
            SystemClock.sleep(300);
            float[] after=viewport(view);
            assertEquals("Relative zoom",before[0],after[0],.03f);
            assertEquals("Source center X",before[1],after[1],.03f);
            assertEquals("Source center Y",before[2],after[2],.03f);
            capture("photo-pinch-pan-landscape");
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }
    private float[] viewport(ZoomableImageView view) {
        AtomicReference<float[]> value=new AtomicReference<>();
        instrumentation.runOnMainSync(()->value.set(view.captureViewport()));
        return value.get();
    }
    private interface Condition {boolean ready();}
    private void waitFor(Condition condition) {
        long end=SystemClock.uptimeMillis()+10000;
        while(SystemClock.uptimeMillis()<end) {
            AtomicReference<Boolean> ready=new AtomicReference<>(false);
            instrumentation.runOnMainSync(()->ready.set(condition.ready()));
            if(ready.get())return;
            SystemClock.sleep(100);
        }
        fail("Actual window/image did not become ready");
    }
    private void pinch(float x,float y,float start,float end) {
        long down=SystemClock.uptimeMillis();
        event(down,MotionEvent.ACTION_DOWN,new float[]{x-start},new float[]{y});
        event(down,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),new float[]{x-start,x+start},new float[]{y,y});
        for(int i=1;i<=20;i++) {
            float distance=start+(end-start)*i/20f;
            event(down,MotionEvent.ACTION_MOVE,new float[]{x-distance,x+distance},new float[]{y,y});
            SystemClock.sleep(20);
        }
        event(down,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),new float[]{x-end,x+end},new float[]{y,y});
        event(down,MotionEvent.ACTION_UP,new float[]{x-end},new float[]{y});
    }
    private void drag(float x,float y,float toX,float toY) {
        long down=SystemClock.uptimeMillis();event(down,MotionEvent.ACTION_DOWN,new float[]{x},new float[]{y});
        for(int i=1;i<=15;i++) {event(down,MotionEvent.ACTION_MOVE,new float[]{x+(toX-x)*i/15f},new float[]{y+(toY-y)*i/15f});SystemClock.sleep(20);}
        event(down,MotionEvent.ACTION_UP,new float[]{toX},new float[]{toY});
    }
    private void event(long down,int action,float[] x,float[] y) {
        MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[x.length];
        MotionEvent.PointerCoords[] coordinates=new MotionEvent.PointerCoords[x.length];
        for(int i=0;i<x.length;i++) {
            properties[i]=new MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i]=new MotionEvent.PointerCoords();coordinates[i].x=x[i];coordinates[i].y=y[i];coordinates[i].pressure=1;coordinates[i].size=1;
        }
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x.length,properties,coordinates,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        assertTrue("Input injection",instrumentation.getUiAutomation().injectInputEvent(event,true));event.recycle();
    }
    private void capture(String name) throws Exception {
        File folder=new File(instrumentation.getTargetContext().getExternalFilesDir(null),"qa-runtime-evidence");
        assertTrue(folder.isDirectory()||folder.mkdirs());
        Bitmap image=instrumentation.getUiAutomation().takeScreenshot();assertNotNull(image);
        try(FileOutputStream output=new FileOutputStream(new File(folder,name+".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,output));
        }
        image.recycle();
    }
}
