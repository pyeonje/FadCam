package com.fadcam.ui;

import static org.junit.Assert.*;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fadcam.*;
import com.fadcam.services.RecordingService;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real input on the existing owned AVD only; never a physical-phone test. */
@RunWith(AndroidJUnit4.class)
public class GalleryFunctionalDeviceTest {
 private final Instrumentation i=InstrumentationRegistry.getInstrumentation();
 private void main(Runnable r){i.runOnMainSync(r);}
 private Object field(Object o,String key){try{java.lang.reflect.Field f=(o instanceof Class?(Class<?>)o:o.getClass()).getDeclaredField(key);f.setAccessible(true);return f.get(o instanceof Class?null:o);}catch(Exception e){throw new RuntimeException(e);}}
 private void waitFor(BooleanSupplier c)throws Exception{long end=SystemClock.uptimeMillis()+30000;while(SystemClock.uptimeMillis()<end){AtomicReference<Boolean> v=new AtomicReference<>();main(()->v.set(c.getAsBoolean()));if(v.get())return;SystemClock.sleep(100);}fail("Product state did not settle in30s");}
 private Object service(){return field(RecordingService.class,"liveInstance");}
 private RecordingState state(){return service()==null?RecordingState.NONE:(RecordingState)field(service(),"recordingState");}
 private Player player(VideoPlayerActivity a){return ((PlayerView)a.findViewById(R.id.player_view)).getPlayer();}
 private void click(Activity a,int id){main(()->{View v=a.findViewById(id);assertNotNull(v);assertTrue(v.isShown());assertTrue(v.isEnabled());v.performClick();});}
 private File folder(){String run=InstrumentationRegistry.getArguments().getString("repair_run");assertTrue(run.matches("[A-Za-z0-9_-]{1,90}"));File f=new File(i.getTargetContext().getExternalFilesDir(null),"qa-gallery-repair/"+run);assertTrue(f.isDirectory()||f.mkdirs());return f;}
 private void json(String name,JSONObject data)throws Exception{try(FileOutputStream f=new FileOutputStream(new File(folder(),name+".json"))){f.write(data.toString().getBytes(StandardCharsets.UTF_8));}}
 private void snapshot(String name)throws Exception{i.waitForIdleSync();SystemClock.sleep(300);Bitmap b=i.getUiAutomation().takeScreenshot();assertNotNull(b);try(FileOutputStream f=new FileOutputStream(new File(folder(),name+".png"))){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,f));}finally{b.recycle();}}
 private void swipe(Activity a,boolean right)throws Exception{
  AtomicReference<Rect> box=new AtomicReference<>();main(()->{Rect r=new Rect();View v=a.findViewById(a instanceof VideoPlayerActivity?R.id.player_view:R.id.imageViewerImage);assertTrue(v.getGlobalVisibleRect(r));box.set(r);});
  Rect r=box.get();float from=r.left+r.width()*(right?.30f:.70f),to=r.left+r.width()*(right?.70f:.30f),y=r.top+r.height()*.50f;long down=SystemClock.uptimeMillis();
  for(int n=0;n<=12;n++){MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),n==0?0:n==12?1:2,from+(to-from)*n/12,y,0);e.setSource(InputDevice.SOURCE_TOUCHSCREEN);i.sendPointerSync(e);e.recycle();SystemClock.sleep(22);}i.waitForIdleSync();
 }
 private void restore(SharedPreferences prefs,Map<String,?> before){SharedPreferences.Editor e=prefs.edit();for(String k:new HashSet<>(prefs.getAll().keySet()))if(!before.containsKey(k))e.remove(k);for(Map.Entry<String,?> v:before.entrySet()){String k=v.getKey();Object x=v.getValue();if(x instanceof String)e.putString(k,(String)x);else if(x instanceof Boolean)e.putBoolean(k,(Boolean)x);else if(x instanceof Integer)e.putInt(k,(Integer)x);else if(x instanceof Long)e.putLong(k,(Long)x);else if(x instanceof Float)e.putFloat(k,(Float)x);else if(x instanceof Set)e.putStringSet(k,new HashSet<>((Set<String>)x));else throw new AssertionError(x);}assertTrue(e.commit());}
 @Test public void recordPauseResumeThenPageActualMixedGallerySurface()throws Exception{
  assertTrue(Build.HARDWARE.equals("ranchu")||Build.HARDWARE.equals("goldfish"));
  Context app=i.getTargetContext();SharedPreferencesManager p=SharedPreferencesManager.getInstance(app);Map<String,?> before=new HashMap<>(p.sharedPreferences.getAll());
  File photo=new File(app.getExternalFilesDir(null),"FadCam/Camera/QA/QA_FUNCTION_PHOTO_20261031.png");assertFalse(photo.exists());assertTrue(photo.getParentFile().isDirectory()||photo.getParentFile().mkdirs());
  Bitmap b=Bitmap.createBitmap(640,360,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(b);canvas.drawColor(Color.rgb(25,95,175));Paint paint=new Paint();paint.setColor(Color.WHITE);paint.setTextSize(45);canvas.drawText("OWN QA PHOTO",90,190,paint);try(FileOutputStream f=new FileOutputStream(photo)){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,f));}finally{b.recycle();}
  // The synthetic neighbour is older than the photo; the just-recorded item is newest.
  File neighbour=new File(app.getExternalFilesDir(null),"FadCam/Camera/QA/QA_FUNCTION_VIDEO_20261031.mp4");assertFalse(neighbour.exists());
  File second=new File(neighbour.getParentFile(),"QA_FUNCTION_SECOND_VIDEO_20261031.mp4");assertFalse(second.exists());
  MainActivity camera=null;VideoPlayerActivity video=null;ImageViewerActivity image=null;File recording=null;
  Instrumentation.ActivityMonitor videos=i.addMonitor(VideoPlayerActivity.class.getName(),null,false),photos=i.addMonitor(ImageViewerActivity.class.getName(),null,false);
  try{
   main(()->{p.sharedPreferences.edit().putString(Constants.PREF_CAMERA_SELECTION,CameraType.BACK.name()).putInt(Constants.PREF_VIDEO_RESOLUTION_WIDTH,1280).putInt(Constants.PREF_VIDEO_RESOLUTION_HEIGHT,720).commit();p.setSelectedBackCameraId("0");p.setSpecificVideoFrameRate(CameraType.BACK,30);p.setRecordAudioEnabled(false);});
   camera=(MainActivity)i.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final MainActivity c=camera;
   waitFor(()->c.hasWindowFocus()&&service()!=null&&field(service(),"captureSession")!=null);click(c,R.id.personal_camera_mode_video);click(c,R.id.personal_home_record);
   waitFor(()->state()==RecordingState.IN_PROGRESS);String path=(String)field(service(),"currentSegmentPath");assertNotNull(path);recording=new File(path);assertTrue(recording.getCanonicalPath().startsWith(app.getExternalFilesDir(null).getCanonicalPath()+File.separator));
   SystemClock.sleep(2000);click(c,R.id.personal_home_pause);waitFor(()->state()==RecordingState.PAUSED);SystemClock.sleep(350);click(c,R.id.personal_home_pause);waitFor(()->state()==RecordingState.IN_PROGRESS);SystemClock.sleep(2000);click(c,R.id.personal_home_record);waitFor(()->state()==RecordingState.NONE&&!RecordingService.isCameraCleanupInProgress());
   final File recorded=recording;waitFor(()->recorded.length()>0);
   try(InputStream input=new FileInputStream(recorded);OutputStream output=new FileOutputStream(neighbour)){byte[] chunk=new byte[16384];int size;while((size=input.read(chunk))!=-1)output.write(chunk,0,size);}
   try(InputStream input=new FileInputStream(recorded);OutputStream output=new FileOutputStream(second)){byte[] chunk=new byte[16384];int size;while((size=input.read(chunk))!=-1)output.write(chunk,0,size);}
   assertTrue("Own application creates the neighbour",neighbour.length()==recorded.length());assertTrue(second.setLastModified(recorded.lastModified()-3000));json("own-secondary",new JSONObject().put("path",second.getAbsolutePath()).put("bytes",second.length()));
   assertTrue("Own photo timestamp",photo.setLastModified(recorded.lastModified()-1000));assertTrue("Own video timestamp",neighbour.setLastModified(recorded.lastModified()-2000));
   json("owned-recording",new JSONObject().put("path",recorded.getAbsolutePath()).put("bytes",recorded.length()).put("pauseResume",true));
   waitFor(()->c.findViewById(R.id.personal_home_videos).isEnabled());click(c,R.id.personal_home_videos);video=(VideoPlayerActivity)videos.waitForActivityWithTimeout(15000);assertNotNull(video);final VideoPlayerActivity v=video;
   waitFor(()->v.hasWindowFocus()&&player(v)!=null&&player(v).getDuration()>0&&((GalleryMediaStrip)v.findViewById(R.id.gallery_media_strip)).getAdapter().getItemCount()>2);
   Uri recordedUri=(Uri)field(v,"currentVideoUri");assertEquals(recorded.getName(),new File(recordedUri.getPath()).getName());main(()->player(v).pause());snapshot("recorded-inline");
   // Real screen input, through the actual Window/PlayerView hierarchy and listeners installed by onCreate.
   swipe(v,true);image=(ImageViewerActivity)photos.waitForActivityWithTimeout(15000);assertNotNull("Fresh camera video must page to the next photo",image);final ImageViewerActivity a=image;
   waitFor(()->a.hasWindowFocus()&&((GalleryMediaStrip)a.findViewById(R.id.gallery_media_strip)).getAdapter().getItemCount()>2);main(()->{assertNull("Paused old viewer detaches playback callback",field(v,"playbackListenerOwner"));assertNull(field(v,"waveformListenerOwner"));android.os.Handler h=(android.os.Handler)field(v,"waveformHandler");Runnable tick=(Runnable)field(v,"waveformUpdateRunnable");assertTrue("Hidden viewer has no100ms work",h==null||tick==null||!h.hasCallbacks(tick));});snapshot("photo-after-video-swipe");
   swipe(a,true);VideoPlayerActivity older=(VideoPlayerActivity)videos.waitForActivityWithTimeout(15000);assertNotNull(older);video=older;final VideoPlayerActivity old=older;
   waitFor(()->old.hasWindowFocus()&&player(old)!=null&&player(old).getDuration()>0);assertEquals(neighbour.getName(),new File(((Uri)field(old,"currentVideoUri")).getPath()).getName());SystemClock.sleep(600);main(()->assertTrue("Previous viewer destruction must not pause the new media",player(old).getPlayWhenReady()));main(()->assertTrue("Different item starts at0, not an n-second seek",player(old).getCurrentPosition()<2500));main(()->player(old).pause());snapshot("older-video-after-photo-swipe");
   waitFor(()->((GalleryMediaStrip)old.findViewById(R.id.gallery_media_strip)).getAdapter().getItemCount()>2);
   swipe(old,true);waitFor(()->((Uri)field(old,"currentVideoUri")).getLastPathSegment().equals(second.getName()));main(()->assertTrue(player(old).getCurrentPosition()<2500));main(()->player(old).pause());snapshot("direct-next-video-no-seek");
   swipe(old,false);waitFor(()->((Uri)field(old,"currentVideoUri")).getLastPathSegment().equals(neighbour.getName()));main(()->assertTrue(player(old).getCurrentPosition()<2500));main(()->player(old).pause());snapshot("direct-previous-video-no-seek");
   swipe(old,false);ImageViewerActivity back=(ImageViewerActivity)photos.waitForActivityWithTimeout(15000);assertNotNull(back);image=back;waitFor(back::hasWindowFocus);snapshot("photo-after-reverse-swipe");
   json("functional-results",new JSONObject().put("actualRecordPauseResumeStop",true).put("freshCameraRecentUri",recordedUri.toString()).put("videoPhotoVideoBothDirections",true).put("sameActivityConsecutiveVideoBothDirectionsWithoutSecondsSeek",true).put("input","Instrumentation.sendPointerSync via actual window").put("phoneTouched",false).put("physicalFocusOrImageQualityVerified",false));
  }finally{
   if(state()==RecordingState.IN_PROGRESS||state()==RecordingState.PAUSED||state()==RecordingState.STARTING){main(()->app.startService(new Intent(app,RecordingService.class).setAction(Constants.INTENT_ACTION_STOP_RECORDING)));waitFor(()->state()==RecordingState.NONE&&!RecordingService.isCameraCleanupInProgress());}
   final VideoPlayerActivity v=video;final ImageViewerActivity a=image;final MainActivity c=camera;main(()->{if(v!=null&&!v.isFinishing())v.finish();if(a!=null&&!a.isFinishing())a.finish();if(c!=null&&!c.isFinishing())c.finish();});
   i.removeMonitor(videos);i.removeMonitor(photos);main(()->restore(p.sharedPreferences,before));
   // All own media are retained for host pull+hash verification and exact-path cleanup.
   json("own-photo",new JSONObject().put("path",photo.getAbsolutePath()).put("bytes",photo.length()));
  }
 }
}
