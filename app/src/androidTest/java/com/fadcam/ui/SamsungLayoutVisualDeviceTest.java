package com.fadcam.ui;

import static org.junit.Assert.*;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.RecordingState;
import com.fadcam.services.RecordingService;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual product windows. Restricted to the already owned synthetic-media emulator. */
@RunWith(AndroidJUnit4.class)
public class SamsungLayoutVisualDeviceTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final android.os.Handler fixtureHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private MainActivity fixtureCamera;
    private int fixtureDirection;
    private boolean fixtureLanguageChanged;
    private String fixtureLanguage;
    private final Runnable holdIdleCameraWindow=new Runnable() {
        @Override public void run() {
            MainActivity activity=fixtureCamera;
            if(activity==null || activity.isFinishing() || activity.isDestroyed())return;
            View record=activity.findViewById(R.id.personal_home_record);
            if(activity.hasWindowFocus() && record!=null && record.isShown()
                    && !RecordingService.hasActiveSession() && !RecordingService.isRecordingFinalizing()
                    && activity.getRequestedOrientation()!=fixtureDirection)
                activity.setRequestedOrientation(fixtureDirection);
            fixtureHandler.postDelayed(this,100);
        }
    };
    private void stopWindowFixture() {
        instrumentation.runOnMainSync(()->{fixtureCamera=null;fixtureHandler.removeCallbacksAndMessages(null);});
        if(fixtureLanguageChanged) {
            android.content.SharedPreferences.Editor edit=instrumentation.getTargetContext()
                    .getSharedPreferences(com.fadcam.Constants.PREFS_NAME,android.content.Context.MODE_PRIVATE).edit();
            if(fixtureLanguage==null)edit.remove(com.fadcam.Constants.LANGUAGE_KEY);
            else edit.putString(com.fadcam.Constants.LANGUAGE_KEY,fixtureLanguage);
            assertTrue(edit.commit());fixtureLanguageChanged=false;
        }
    }

    private MainActivity openCamera() throws Exception {
        assertTrue("Only synthetic-media QA emulator",Build.HARDWARE.equals("ranchu")
                || Build.HARDWARE.equals("goldfish"));
        String language=InstrumentationRegistry.getArguments().getString("fixture_language","");
        if(!language.isEmpty() && !fixtureLanguageChanged) {
            assertEquals("Only bounded Korean reference fixture", "ko",language);
            android.content.SharedPreferences prefs=instrumentation.getTargetContext()
                    .getSharedPreferences(com.fadcam.Constants.PREFS_NAME,android.content.Context.MODE_PRIVATE);
            fixtureLanguage=prefs.getString(com.fadcam.Constants.LANGUAGE_KEY,null);
            // This owned AVD had no chosen language before the failed Korean fixture.
            // Repair that task-owned residue, without resetting any other preference.
            if("ko".equals(fixtureLanguage) && InstrumentationRegistry.getArguments().getString("repair_qa_language_residue","").equals("true"))fixtureLanguage=null;
            assertTrue(prefs.edit().putString(com.fadcam.Constants.LANGUAGE_KEY,language).commit());fixtureLanguageChanged=true;
        }
        MainActivity activity;
        try {activity=(MainActivity)instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
        catch(RuntimeException failure){stopWindowFixture();throw failure;}
        waitFor(()->activity.hasWindowFocus() && activity.findViewById(R.id.personal_home_record)!=null);
        return activity;
    }

    @Test public void captureRealCameraIdleFocusAndRecordingControls() throws Exception {
        MainActivity activity=openCamera();
        boolean startIssued=false;
        try {
            assertFalse("Do not interrupt a pre-existing QA recording",RecordingService.hasActiveSession());
            assertFalse("Do not overlap a finalizing recording",RecordingService.isRecordingFinalizing());
            rotate(activity,false);SystemClock.sleep(5000);snapshot(activity,"camera-idle-portrait");
            rotate(activity,true);
            waitFor(()->activity.findViewById(R.id.personal_pro_focus).isEnabled());
            SystemClock.sleep(500);snapshot(activity,"camera-idle-landscape");
            verifyNativeCapturePinch(activity);
            instrumentation.runOnMainSync(()->{
                View zoom=activity.findViewById(R.id.personal_zoom_value);
                if(zoom.isShown())assertTrue(zoom.performClick());
                else {
                    android.view.ViewGroup lenses=activity.findViewById(R.id.personal_zoom_presets);
                    boolean clicked=false;
                    for(int i=0;i<lenses.getChildCount();i++)if(lenses.getChildAt(i).isSelected())clicked=lenses.getChildAt(i).performClick();
                    assertTrue("Selected physical lens opens expanded zoom",clicked);
                }
            });
            waitFor(()->activity.findViewById(R.id.personal_zoom_close).isShown());
            snapshot(activity,"camera-zoom-landscape");click(activity,R.id.personal_zoom_close);
            click(activity,R.id.personal_pro_focus);SystemClock.sleep(450);
            snapshot(activity,"camera-focus-landscape");
            // The same selected control closes its adjustment panel without changing a value.
            click(activity,R.id.personal_pro_focus);SystemClock.sleep(200);
            waitFor(()->activity.findViewById(R.id.personal_home_record).isEnabled());
            startIssued=true;click(activity,R.id.personal_home_record);
            waitFor(()->RecordingService.getSessionState()==RecordingState.IN_PROGRESS
                    && activity.findViewById(R.id.personal_home_pause).isEnabled());
            SystemClock.sleep(3500);snapshot(activity,"camera-recording-landscape");
            android.content.Context context=instrumentation.getTargetContext();
            File shotFolder=new File(context.getExternalFilesDir(null),"FadCam/FadShot/Back");
            java.util.Set<String> priorShots=new java.util.HashSet<>();
            File[] beforeShots=shotFolder.listFiles();
            if(beforeShots!=null)for(File shot:beforeShots)priorShots.add(shot.getName());
            AtomicReference<File> capturedPhoto=new AtomicReference<>();
                click(activity,R.id.personal_home_videos);
                waitFor(()->{
                    File[] shots=shotFolder.listFiles();
                    if(shots!=null)for(File shot:shots)if(!priorShots.contains(shot.getName())
                            && shot.getName().endsWith(".jpg") && shot.length()>0){capturedPhoto.set(shot);return true;}
                    return false;
                });
                SystemClock.sleep(300);
                try(java.io.InputStream photo=new java.io.FileInputStream(capturedPhoto.get())) {
                    android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();
                    options.inJustDecodeBounds=true;
                    android.graphics.BitmapFactory.decodeStream(photo,null,options);
                    assertTrue("Recording still is a valid saved image",options.outWidth>0 && options.outHeight>0);
                }
                assertEquals("Still capture must not stop recording",RecordingState.IN_PROGRESS,RecordingService.getSessionState());
                System.out.println("OWNED_SNAPSHOT_URI="+android.net.Uri.fromFile(capturedPhoto.get()));
                SystemClock.sleep(2300);
            click(activity,R.id.personal_home_pause);
            waitFor(()->RecordingService.getSessionState()==RecordingState.PAUSED
                    && activity.findViewById(R.id.personal_home_pause).isEnabled());
            snapshot(activity,"camera-paused-landscape");
            click(activity,R.id.personal_home_record);
            waitFor(()->RecordingService.getSessionState()==RecordingState.NONE
                    && !RecordingService.isRecordingFinalizing()
                    && !RecordingService.isCameraCleanupInProgress()
                    && activity.findViewById(R.id.personal_home_record).isEnabled());
        } finally {
            stopWindowFixture();
            if(startIssued && RecordingService.hasActiveSession())instrumentation.runOnMainSync(()->activity.startService(
                    new Intent(activity,RecordingService.class).setAction(com.fadcam.Constants.INTENT_ACTION_STOP_RECORDING)));
            instrumentation.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_UNFREEZE);
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    @Test public void captureRealGalleryGridSelectionRotationAndCameraReturn() throws Exception {
        com.fadcam.SharedPreferencesManager settings=com.fadcam.SharedPreferencesManager.getInstance(
                instrumentation.getTargetContext());
        int originalColumns=settings.getRecordsGridSpan();
        // Set the owned QA fixture before Android restores its retained RecordsFragment.
        settings.setRecordsGridSpan(4);
        final MainActivity activity;
        try {activity=openCamera();}
        catch(Exception | AssertionError error){settings.setRecordsGridSpan(originalColumns);throw error;}
        try {
            // Explicit setup of the owned synthetic-media QA fixture, restored below.
            // The user's saved density is covered separately by product regressions.
            rotate(activity,false);
            instrumentation.runOnMainSync(()->activity.switchFragment(1,false));
            waitFor(()->activity.findViewById(R.id.recycler_view_records)!=null);
            androidx.recyclerview.widget.RecyclerView grid=activity.findViewById(R.id.recycler_view_records);
            waitFor(()->grid.getChildCount()>0 && grid.getAdapter()!=null && grid.getAdapter().getItemCount()>0);
            waitFor(()->((androidx.recyclerview.widget.GridLayoutManager)grid.getLayoutManager()).getSpanCount()==4
                    && !grid.hasPendingAdapterUpdates());
            SystemClock.sleep(500);
            instrumentation.runOnMainSync(()->assertEquals("Gallery owns its navigation",View.GONE,
                    activity.findViewById(R.id.nav_container).getVisibility()));
            snapshot(activity,"gallery-pictures-portrait");
            click(activity,R.id.gallery_tab_albums);SystemClock.sleep(400);
            snapshot(activity,"gallery-albums-portrait");click(activity,R.id.gallery_tab_pictures);
            waitFor(grid::isShown);
            rotate(activity,true);snapshot(activity,"gallery-pictures-landscape");
            rotate(activity,false);
            instrumentation.runOnMainSync(()->{
                boolean selected=false;
                for(int index=0;index<grid.getChildCount();index++) {
                    View child=grid.getChildAt(index);
                    androidx.recyclerview.widget.RecyclerView.ViewHolder holder=grid.getChildViewHolder(child);
                    if(holder instanceof RecordsAdapter.RecordViewHolder
                            && ((RecordsAdapter.RecordViewHolder)holder).currentUri!=null) {
                        selected=child.performLongClick();break;
                    }
                }
                assertTrue("A real media tile must remain selectable",selected);
            });
            SystemClock.sleep(350);snapshot(activity,"gallery-selected-portrait");
            rotate(activity,true);snapshot(activity,"gallery-selected-landscape");
            rotate(activity,false);
            instrumentation.runOnMainSync(()->activity.getOnBackPressedDispatcher().onBackPressed());
            SystemClock.sleep(250);
            instrumentation.runOnMainSync(()->activity.switchFragment(4,false));
            instrumentation.runOnMainSync(()->{
                assertEquals("Settings dock must be restored",View.VISIBLE,activity.findViewById(R.id.nav_container).getVisibility());
                assertEquals("Settings dock scrim must be restored",View.VISIBLE,activity.findViewById(R.id.dock_focus_gradient_scrim).getVisibility());
            });
            instrumentation.runOnMainSync(()->activity.switchFragment(0,false));
            waitFor(()->activity.findViewById(R.id.personal_home_record).isShown());
            instrumentation.runOnMainSync(()->assertEquals(View.GONE,activity.findViewById(R.id.nav_container).getVisibility()));
        } finally {
            stopWindowFixture();
            settings.setRecordsGridSpan(originalColumns);
            instrumentation.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_UNFREEZE);
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    @Test public void captureExpandedZoomWithoutRecording() throws Exception {
        MainActivity activity=openCamera();
        try {
            assertFalse(RecordingService.hasActiveSession());rotate(activity,true);
            waitFor(()->activity.findViewById(R.id.personal_pro_focus).isEnabled());
            instrumentation.runOnMainSync(()->{
                View value=activity.findViewById(R.id.personal_zoom_value);
                assertTrue(value.performClick());
            });
            waitFor(()->activity.findViewById(R.id.personal_zoom_close).isShown());
            SystemClock.sleep(700);snapshot(activity,"camera-expanded-zoom-landscape");
            instrumentation.runOnMainSync(()->{
                View ruler=activity.findViewById(R.id.personal_zoom_slider);
                assertTrue(ruler.isShown());assertTrue(ruler.getWidth()>0 && ruler.getHeight()>0);
                assertFalse(activity.findViewById(R.id.personal_pro_items).isShown());
                assertFalse(activity.findViewById(R.id.personal_camera_more).isShown());
            });
            click(activity,R.id.personal_zoom_close);
            waitFor(()->activity.findViewById(R.id.personal_pro_items).isShown());
            snapshot(activity,"camera-restored-landscape");
        } finally {
            stopWindowFixture();instrumentation.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_UNFREEZE);
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    @Test public void captureRecentVideoEntryAndTimeline() throws Exception {
        MainActivity camera=openCamera();
        VideoPlayerActivity viewer=null;
        android.app.Instrumentation.ActivityMonitor monitor=instrumentation.addMonitor(
                VideoPlayerActivity.class.getName(),null,false);
        try {
            rotate(camera,false);
            click(camera,R.id.personal_home_videos);
            android.app.Activity opened=monitor.waitForActivityWithTimeout(25000);
            assertTrue("Camera thumbnail opens latest video directly, without gallery grid",opened instanceof VideoPlayerActivity);
            viewer=(VideoPlayerActivity)opened;
            final VideoPlayerActivity playerActivity=viewer;
            androidx.media3.ui.PlayerView playerView=viewer.findViewById(R.id.player_view);
            waitFor(()->playerActivity.hasWindowFocus() && playerView.getPlayer()!=null
                    && playerView.getPlayer().getPlaybackState()==androidx.media3.common.Player.STATE_READY);
            String expected=InstrumentationRegistry.getArguments().getString("expected_recent_uri","");
            assertFalse("Owned newly recorded fixture URI required",expected.isEmpty());
            assertEquals(expected,viewer.getIntent().getDataString());
            androidx.media3.common.Player player=playerView.getPlayer();
            waitFor(player::isPlaying);
            instrumentation.runOnMainSync(playerView::showController);
            click(viewer,androidx.media3.ui.R.id.exo_play_pause);
            waitFor(()->!player.getPlayWhenReady());
            assertInlineTransport(viewer);
            SystemClock.sleep(3000);snapshot(viewer,"viewer-recent-portrait");
            click(viewer,R.id.info_button);SystemClock.sleep(250);
            snapshot(viewer,"viewer-more-portrait");
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            waitFor(playerActivity::hasWindowFocus);
            com.google.android.material.slider.Slider slider=viewer.findViewById(R.id.material_time_slider);
            GalleryMediaStrip strip=viewer.findViewById(R.id.gallery_media_strip);
            assertNotNull("Real media list",strip);
            assertFalse("Linear player seek bar is not the inline Gallery UI",slider.isShown());
            waitFor(()->strip.getChildCount()>0 && !strip.hasPendingAdapterUpdates());
            instrumentation.runOnMainSync(()->{
                View selected=null;
                for(int i=0;i<strip.getChildCount();i++)if(strip.getChildAt(i).isSelected())selected=strip.getChildAt(i);
                assertNotNull("Current selected video belongs to media list",selected);assertTrue(selected.performClick());
            });
            waitFor(()->strip.isExpanded() && !strip.hasPendingAdapterUpdates());
            waitFor(()->{
                for(int i=0;i<strip.getChildCount();i++)if(strip.getChildAt(i).isSelected()) {
                    GalleryVideoFilmstrip frames=findFrames(strip.getChildAt(i));
                    if(frames==null || !frames.isShown() || frames.getChildCount()!=8)return false;
                    for(int frame=0;frame<8;frame++)if(((android.widget.ImageView)frames.getChildAt(frame)).getDrawable()==null)return false;
                    return true;
                }
                return false;
            });
            SystemClock.sleep(700);snapshot(viewer,"viewer-expanded-portrait");
            instrumentation.runOnMainSync(()->player.seekTo(player.getDuration()/4));
            SystemClock.sleep(500);
            long earlier=position(player);
            dragStrip(strip,-180);
            waitFor(()->player.getPlaybackState()==androidx.media3.common.Player.STATE_READY
                    && player.getCurrentPosition()>earlier+250);
            long later=position(player);
            snapshot(viewer,"viewer-scrub-left-portrait");
            dragStrip(strip,160);
            waitFor(()->player.getPlaybackState()==androidx.media3.common.Player.STATE_READY
                    && player.getCurrentPosition()<later-250);
            AtomicReference<Long> seekPosition=new AtomicReference<>();
            instrumentation.runOnMainSync(()->{
                assertFalse("Scrubbing preserves paused playback",player.getPlayWhenReady());
                seekPosition.set(player.getCurrentPosition());
            });
            SystemClock.sleep(250);snapshot(viewer,"viewer-scrubbed-portrait");
            singleTap(playerView);
            waitFor(()->!strip.isShown());
            singleTap(playerView);
            waitFor(strip::isShown);
            rotate(viewer,true);
            instrumentation.runOnMainSync(()->{
                playerView.showController();assertSame(player,playerView.getPlayer());
                assertEquals("Rotation preserves selected time",seekPosition.get(),player.getCurrentPosition(),250);
            });
            assertInlineTransport(viewer);
            assertTrue("Expanded selected video survives layout rotation",strip.isExpanded());
            snapshot(viewer,"viewer-scrubbed-landscape");
            click(viewer,androidx.media3.ui.R.id.exo_play_pause);
            waitFor(player::isPlaying);
            instrumentation.runOnMainSync(playerView::showController);
            snapshot(viewer,"viewer-playing-landscape");
            instrumentation.runOnMainSync(player::pause);
            click(viewer,R.id.back_button);
            waitFor(()->camera.hasWindowFocus() && camera.findViewById(R.id.recycler_view_records)!=null
                    && camera.findViewById(R.id.recycler_view_records).isShown());
            rotate(camera,true);
            snapshot(camera,"viewer-parent-gallery-landscape");
            instrumentation.runOnMainSync(()->camera.getOnBackPressedDispatcher().onBackPressed());
            waitFor(()->camera.hasWindowFocus() && camera.findViewById(R.id.personal_home_record).isShown());
            // Focus returns before Android finishes the viewer-exit/window-inset animation.
            // Capture the settled camera window, as for the initial camera evidence above.
            SystemClock.sleep(3000);
            waitFor(()->{
                android.view.WindowInsets insets=camera.getWindow().getDecorView().getRootWindowInsets();
                return insets!=null && !insets.isVisible(android.view.WindowInsets.Type.statusBars());
            });
            snapshot(camera,"viewer-back-camera");
        } finally {
            stopWindowFixture();
            instrumentation.removeMonitor(monitor);
            final VideoPlayerActivity finalViewer=viewer;
            if(finalViewer!=null && !finalViewer.isFinishing())instrumentation.runOnMainSync(finalViewer::finish);
            instrumentation.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_UNFREEZE);
            instrumentation.runOnMainSync(camera::finish);
        }
    }

    private long position(androidx.media3.common.Player player) {
        AtomicReference<Long> position=new AtomicReference<>();
        instrumentation.runOnMainSync(()->position.set(player.getCurrentPosition()));return position.get();
    }
    private GalleryVideoFilmstrip findFrames(View view) {
        if(view instanceof GalleryVideoFilmstrip)return (GalleryVideoFilmstrip)view;
        if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++) {
            GalleryVideoFilmstrip frames=findFrames(((android.view.ViewGroup)view).getChildAt(i));if(frames!=null)return frames;
        }
        return null;
    }
    private void verifyNativeCapturePinch(MainActivity activity) throws Exception {
        android.view.TextureView preview=activity.findViewById(R.id.personal_home_preview);
        com.fadcam.SharedPreferencesManager prefs=com.fadcam.SharedPreferencesManager.getInstance(activity);
        com.fadcam.CameraType camera=prefs.getCameraSelection();
        float original=prefs.getSpecificZoomRatio(camera);
        long start=SystemClock.uptimeMillis();
        try {
            for(int step=0;step<10;step++) {
                final int index=step;
                instrumentation.runOnMainSync(()->{
                    int count=index==0||index==9?1:2;
                    int action=index==0?android.view.MotionEvent.ACTION_DOWN:index==1?android.view.MotionEvent.ACTION_POINTER_DOWN|(1<<8):index==8?android.view.MotionEvent.ACTION_POINTER_UP|(1<<8):index==9?android.view.MotionEvent.ACTION_UP:android.view.MotionEvent.ACTION_MOVE;
                    android.view.MotionEvent.PointerProperties[] properties=new android.view.MotionEvent.PointerProperties[count];
                    android.view.MotionEvent.PointerCoords[] coordinates=new android.view.MotionEvent.PointerCoords[count];
                    float span=preview.getWidth()*(.08f+Math.min(index,7)*.018f);
                    for(int i=0;i<count;i++) {
                        properties[i]=new android.view.MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=android.view.MotionEvent.TOOL_TYPE_FINGER;
                        coordinates[i]=new android.view.MotionEvent.PointerCoords();coordinates[i].x=preview.getWidth()/2f+(i==0?-span:span);coordinates[i].y=preview.getHeight()/2f;coordinates[i].pressure=1;coordinates[i].size=1;
                    }
                    android.view.MotionEvent event=android.view.MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,count,properties,coordinates,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);
                    assertTrue(preview.dispatchTouchEvent(event));event.recycle();
                });
                SystemClock.sleep(55);
            }
            waitFor(()->prefs.getSpecificZoomRatio(camera)>original+.05f);
            snapshot(activity,"camera-pinch-landscape");
        } finally {
            instrumentation.runOnMainSync(()->{
                prefs.setSpecificZoomRatio(camera,original);
                activity.startService(com.fadcam.RecordingControlIntents.setZoomRatio(activity,original,0,0));
                for(androidx.fragment.app.Fragment fragment:activity.getSupportFragmentManager().getFragments())
                    if(fragment instanceof PersonalHomeFragment)try {
                        java.lang.reflect.Field field=PersonalHomeFragment.class.getDeclaredField("cameraControls");field.setAccessible(true);
                        PersonalCameraControls controls=(PersonalCameraControls)field.get(fragment);if(controls!=null)controls.refresh();
                    } catch(ReflectiveOperationException error){throw new RuntimeException(error);}
            });
        }
    }
    private void dragStrip(GalleryMediaStrip strip,float dp) throws Exception {
        float density=strip.getResources().getDisplayMetrics().density;
        long down=SystemClock.uptimeMillis();
        for(int step=0;step<=12;step++) {
            final int index=step;
            instrumentation.runOnMainSync(()->{
                int action=index==0?android.view.MotionEvent.ACTION_DOWN:index==12?android.view.MotionEvent.ACTION_UP:android.view.MotionEvent.ACTION_MOVE;
                float x=strip.getWidth()/2f+dp*density*index/12f;
                android.view.MotionEvent event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,strip.getHeight()/2f,0);
                event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                assertTrue(strip.dispatchTouchEvent(event));event.recycle();
            });
            SystemClock.sleep(45);
        }
        waitFor(()->strip.getScrollState()==androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE);
    }
    private void assertInlineTransport(android.app.Activity activity) {
        instrumentation.runOnMainSync(()->{
            View root=activity.findViewById(R.id.player_view);
            View play=activity.findViewById(androidx.media3.ui.R.id.exo_center_controls);
            Rect rootBounds=new Rect(),playBounds=new Rect(),seekBounds=new Rect();
            assertTrue(root.getGlobalVisibleRect(rootBounds));
            assertTrue("Play control must be visible",play.getGlobalVisibleRect(playBounds));
            assertEquals("Visible play control must be horizontally centered",rootBounds.exactCenterX(),playBounds.exactCenterX(),2f);
            assertTrue("Inline transport belongs near the bottom",playBounds.top>rootBounds.height()*.60f);
            View slider=activity.findViewById(R.id.material_time_slider);
            assertFalse("Inline preview has no linear player seek",slider.isShown());
            assertTrue(activity.findViewById(R.id.gallery_media_strip).getGlobalVisibleRect(seekBounds));
            assertTrue("Media strip belongs below the inline transport",seekBounds.top>playBounds.bottom);
            assertNotNull("Gallery keeps its real photo actions",activity.findViewById(R.id.gallery_viewer_favorite));
        });
    }

    private void click(android.app.Activity activity,int id) {
        instrumentation.runOnMainSync(()->{
            View view=activity.findViewById(id);assertNotNull(view);assertTrue(view.isShown());assertTrue(view.performClick());
        });
    }
    private void singleTap(View view) throws Exception {
        instrumentation.runOnMainSync(()->{
            long now=SystemClock.uptimeMillis();
            // The center is now the real play/pause target. Tap unobstructed content to toggle chrome.
            float x=view.getWidth()*.25f,y=view.getHeight()*.35f;
            android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,
                    android.view.MotionEvent.ACTION_DOWN,x,y,0);
            android.view.MotionEvent up=android.view.MotionEvent.obtain(now,now+60,
                    android.view.MotionEvent.ACTION_UP,x,y,0);
            down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            assertTrue(view.dispatchTouchEvent(down));assertTrue(view.dispatchTouchEvent(up));
            down.recycle();up.recycle();
        });
        SystemClock.sleep(600);
    }
    private void rotate(android.app.Activity activity,boolean landscape) throws Exception {
        // Explicit native-window layout fixture; this does not qualify physical sensor rotation.
        int requested=landscape?android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        instrumentation.runOnMainSync(()->{
            activity.setRequestedOrientation(requested);
            if(activity instanceof MainActivity) {
                fixtureCamera=(MainActivity)activity;fixtureDirection=requested;
                fixtureHandler.removeCallbacks(holdIdleCameraWindow);fixtureHandler.post(holdIdleCameraWindow);
            }
        });
        instrumentation.getUiAutomation().setRotation(landscape?android.app.UiAutomation.ROTATION_FREEZE_90:
                android.app.UiAutomation.ROTATION_FREEZE_0);
        try {waitFor(()->(activity.getWindow().getDecorView().getWidth()>activity.getWindow().getDecorView().getHeight())==landscape);}
        catch(AssertionError error){snapshot(activity,"camera-rotation-failure");throw error;}
        long deadline=SystemClock.uptimeMillis()+12000,stableSince=0;
        while(SystemClock.uptimeMillis()<deadline) {
            AtomicReference<Boolean> stable=new AtomicReference<>();
            instrumentation.runOnMainSync(()->stable.set(
                    (activity.getWindow().getDecorView().getWidth()>activity.getWindow().getDecorView().getHeight())==landscape
                            && activity.getRequestedOrientation()==requested));
            if(stable.get()) {if(stableSince==0)stableSince=SystemClock.uptimeMillis();
                if(SystemClock.uptimeMillis()-stableSince>1000)return;
            } else stableSince=0;
            SystemClock.sleep(100);
        }
        fail("Native orientation fixture did not remain stable");
    }
    private void snapshot(android.app.Activity activity,String name) throws Exception {
        // Model visibility/layout changes precede the compositor's next frame.
        // Let the real window draw instead of pairing a stale PNG with new view state.
        instrumentation.waitForIdleSync();SystemClock.sleep(400);
        instrumentation.runOnMainSync(()->{
            int width=activity.getWindow().getDecorView().getWidth(),height=activity.getWindow().getDecorView().getHeight();
            if(name.endsWith("landscape"))assertTrue("Landscape evidence must actually be landscape",width>height);
            if(name.endsWith("portrait"))assertTrue("Portrait evidence must actually be portrait",height>width);
        });
        String run=InstrumentationRegistry.getArguments().getString("layout_capture_run","");
        assertTrue("A unique bounded evidence folder is required",run.matches("[A-Za-z0-9_-]{1,80}"));
        File folder=new File(instrumentation.getTargetContext().getExternalFilesDir(null),"qa-layout-parity-v2/"+run);
        assertTrue(folder.isDirectory()||folder.mkdirs());
        Bitmap bitmap=instrumentation.getUiAutomation().takeScreenshot();assertNotNull(bitmap);
        try(FileOutputStream output=new FileOutputStream(new File(folder,name+".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));
        } finally {bitmap.recycle();}
        AtomicReference<String> data=new AtomicReference<>();
        instrumentation.runOnMainSync(()->{
            try {
                JSONArray elements=new JSONArray();
                for(int id:new int[]{R.id.personal_home_record,R.id.personal_home_pause,R.id.personal_home_videos,
                        R.id.personal_pro_items,R.id.personal_pro_focus,R.id.recycler_view_records,R.id.title_text,
                        R.id.nav_container,R.id.personal_home_timer,R.id.personal_quick_tools,
                        R.id.personal_grid_control,R.id.personal_stabilization_control,R.id.personal_mic_control,
                        R.id.personal_quality_control,R.id.personal_torch_control,R.id.personal_home_settings,
                        R.id.material_time_slider,androidx.media3.ui.R.id.exo_bottom_bar,
                        R.id.gallery_video_bottom_dock,R.id.gallery_media_strip,R.id.gallery_frame_cursor,
                        R.id.gallery_video_top_actions,androidx.media3.ui.R.id.exo_center_controls,
                        R.id.back_button,R.id.personal_zoom_background,R.id.personal_zoom_slider,
                        R.id.personal_zoom_close,R.id.personal_zoom_anchors,R.id.personal_zoom_value,
                        androidx.media3.ui.R.id.exo_play_pause}) {
                    View view=activity.findViewById(id);if(view==null)continue;
                    Rect bounds=new Rect();boolean visible=view.getGlobalVisibleRect(bounds);
                    elements.put(new JSONObject().put("id",activity.getResources().getResourceEntryName(id))
                            .put("shown",view.isShown()).put("visibleBounds",visible)
                            .put("bounds",new JSONArray(new int[]{bounds.left,bounds.top,bounds.right,bounds.bottom})));
                }
                JSONObject state=new JSONObject().put("screen",name).put("elements",elements)
                        .put("requestedOrientation",activity.getRequestedOrientation())
                        .put("windowWidth",activity.getWindow().getDecorView().getWidth())
                        .put("windowHeight",activity.getWindow().getDecorView().getHeight());
                if(activity instanceof MainActivity) for(String fieldName:new String[]{"personalCameraFullscreen","currentFragmentPosition"}) {
                    java.lang.reflect.Field field=MainActivity.class.getDeclaredField(fieldName);field.setAccessible(true);
                    state.put(fieldName,field.get(activity));
                }
                JSONArray fragments=new JSONArray();
                if(activity instanceof androidx.fragment.app.FragmentActivity)
                for(androidx.fragment.app.Fragment fragment:((androidx.fragment.app.FragmentActivity)activity).getSupportFragmentManager().getFragments()) {
                    fragments.put(new JSONObject().put("class",fragment.getClass().getSimpleName())
                            .put("hidden",fragment.isHidden()).put("resumed",fragment.isResumed())
                            .put("tag",String.valueOf(fragment.getTag())));
                }
                state.put("fragments",fragments);
                if(activity instanceof VideoPlayerActivity) {
                    GalleryMediaStrip strip=activity.findViewById(R.id.gallery_media_strip);
                    if(strip!=null)state.put("mediaStripExpanded",strip.isExpanded()).put("mediaCount",strip.getAdapter().getItemCount());
                    androidx.media3.ui.PlayerView pv=activity.findViewById(R.id.player_view);
                    if(pv!=null && pv.getPlayer()!=null)state.put("positionMs",pv.getPlayer().getCurrentPosition())
                            .put("durationMs",pv.getPlayer().getDuration()).put("playing",pv.getPlayer().isPlaying());
                    com.google.android.material.slider.Slider timeline=activity.findViewById(R.id.material_time_slider);
                    if(timeline!=null)state.put("sliderValueMs",timeline.getValue()).put("sliderEndMs",timeline.getValueTo())
                            .put("sliderThumbWidthPx",timeline.getThumbWidth()).put("sliderThumbHeightPx",timeline.getThumbHeight())
                            .put("sliderTrackHeightPx",timeline.getTrackHeight());
                }
                data.set(state.toString());
            } catch(Exception error){throw new RuntimeException(error);}
        });
        try(FileOutputStream output=new FileOutputStream(new File(folder,name+".json"))) {
            output.write(data.get().getBytes(StandardCharsets.UTF_8));
        }
    }
    private void waitFor(BooleanSupplier condition) throws Exception {
        long deadline=SystemClock.uptimeMillis()+25000;
        while(SystemClock.uptimeMillis()<deadline){
            AtomicReference<Boolean> value=new AtomicReference<>();
            instrumentation.runOnMainSync(()->value.set(condition.getAsBoolean()));
            if(value.get())return;SystemClock.sleep(100);
        }
        fail("Product window/state did not settle within25seconds");
    }
}
