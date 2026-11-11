package com.fadcam.ui;

import android.Manifest;
import android.app.PendingIntent;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.usb.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.fadcam.R;
import com.mikimn.droiduvc.StreamMode;
import java.util.*;

/** In-app USB input. No external APK, built-in Camera2 preference changes, or background streaming. */
public class UsbCameraActivity extends AppCompatActivity implements TextureView.SurfaceTextureListener {
    private UsbManager usb;
    private HandlerThread thread;
    private Handler worker;
    private final Handler main=new Handler(Looper.getMainLooper());
    private TextureView preview;
    private TextView status,zoomLabel;
    private Button choose,allow,modesButton,auto,photo;
    private SeekBar focus,zoomBar;
    private UsbDevice selected;
    private List<StreamMode> modes=Collections.emptyList();
    private StreamMode requested,actual;
    private volatile int generation;
    private boolean active,registered,streaming,writingFocus,focusCommandPending,frameReady,savingPhoto;
    private float zoom=1f;
    private UsbCameraBackend backend; // USB worker owns this reference and all native calls.
    private Surface surface;
    private UsbCameraBackend.FocusState focusState;
    private final Runnable focusWrite=()->writeFocus();
    private int pendingFocus;
    private String permissionAction(){return getPackageName()+".USB_CAMERA_PERMISSION";}
    private final ActivityResultLauncher<String> cameraPermission=registerForActivityResult(new ActivityResultContracts.RequestPermission(),granted->{if(granted)requestUsbPermission();else status.setText(R.string.usb_camera_denied);});
    private final BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){
        if(permissionAction().equals(intent.getAction())){
            if(selected!=null&&usb.hasPermission(selected))openSelected();else status.setText(R.string.usb_camera_denied);
        }else refreshDevices();
    }};
    @Override public void onCreate(Bundle saved){super.onCreate(saved);
        usb=(UsbManager)getSystemService(USB_SERVICE);
        thread=new HandlerThread("FadCam-USB");thread.start();worker=new Handler(thread.getLooper());
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.BLACK);root.setPadding(dp(12),0,dp(12),dp(10));
        root.setOnApplyWindowInsetsListener((v,insets)->{int left,top,right,bottom;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());left=safe.left;top=safe.top;right=safe.right;bottom=safe.bottom;}else{left=insets.getSystemWindowInsetLeft();top=insets.getSystemWindowInsetTop();right=insets.getSystemWindowInsetRight();bottom=insets.getSystemWindowInsetBottom();}v.setPadding(left+dp(12),top,right+dp(12),bottom+dp(10));return insets;});
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);Button back=button("‹");back.setContentDescription(getString(android.R.string.cancel));back.setOnClickListener(v->finish());header.addView(back,new LinearLayout.LayoutParams(dp(56),dp(48)));TextView title=text(getString(R.string.usb_camera_title),20);header.addView(title);root.addView(header);
        FrameLayout area=new FrameLayout(this);preview=new TextureView(this);preview.setSurfaceTextureListener(this);area.addView(preview,new FrameLayout.LayoutParams(-1,-1));root.addView(area,new LinearLayout.LayoutParams(-1,0,1));
        ScaleGestureDetector pinch=new ScaleGestureDetector(this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector d){zoom=Math.max(1f,Math.min(8f,zoom*d.getScaleFactor()));transform();return true;}});
        preview.setOnTouchListener((v,e)->{pinch.onTouchEvent(e);if(e.getActionMasked()==MotionEvent.ACTION_UP)v.performClick();return true;});
        status=text(getString(R.string.usb_camera_connect),14);status.setMaxLines(3);root.addView(status);
        LinearLayout connectionRow=new LinearLayout(this);choose=button(getString(R.string.usb_camera_choose));allow=button(getString(R.string.usb_camera_allow));connectionRow.addView(choose,new LinearLayout.LayoutParams(0,-2,1));connectionRow.addView(allow,new LinearLayout.LayoutParams(0,-2,1));root.addView(connectionRow);
        modesButton=button(getString(R.string.usb_camera_modes));root.addView(modesButton);modesButton.setEnabled(false);modesButton.setOnClickListener(v->showModes());
        LinearLayout focusRow=new LinearLayout(this);auto=button("AF");auto.setEnabled(false);focusRow.addView(auto,new LinearLayout.LayoutParams(dp(72),dp(52)));focus=new SeekBar(this);focus.setMax(1000);focus.setEnabled(false);focus.setContentDescription(getString(R.string.personal_pro_focus_label));focusRow.addView(focus,new LinearLayout.LayoutParams(0,dp(52),1));root.addView(focusRow);
        zoomLabel=text(getString(R.string.usb_camera_zoom,1f),14);root.addView(zoomLabel);zoomBar=new SeekBar(this);zoomBar.setMax(700);zoomBar.setContentDescription(getString(R.string.usb_camera_zoom,1f));zoomBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int value,boolean user){if(user){zoom=1f+value/100f;transform();}}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});root.addView(zoomBar);
        photo=button(getString(R.string.usb_camera_photo));photo.setEnabled(false);photo.setOnClickListener(v->savePhoto());root.addView(photo);setContentView(root);
        choose.setOnClickListener(v->chooseDevice());allow.setOnClickListener(v->requestPermission());
        auto.setOnClickListener(v->{if(focusState==null||!focusState.autoSupported||!streaming)return;final boolean next=!focusState.automatic;final int token=generation;auto.setEnabled(false);worker.post(()->{if(token!=generation||backend==null)return;try{UsbCameraBackend.FocusState state=backend.setAutoFocus(next);postFocus(token,state);}catch(Exception e){postControlFailure(token);}});});
        focus.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int value,boolean user){if(user&&streaming){pendingFocus=value;writingFocus=true;main.removeCallbacks(focusWrite);main.postDelayed(focusWrite,65);}}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){if(writingFocus){main.removeCallbacks(focusWrite);focusWrite.run();}}});
    }
    TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextColor(Color.WHITE);t.setTextSize(size);return t;}
    Button button(String value){Button b=new Button(this);b.setText(value);b.setAllCaps(false);return b;}
    int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override protected void onStart(){super.onStart();active=true;IntentFilter f=new IntentFilter(permissionAction());f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);ContextCompat.registerReceiver(this,receiver,f,ContextCompat.RECEIVER_NOT_EXPORTED);registered=true;refreshDevices();}
    @Override protected void onStop(){active=false;if(registered){unregisterReceiver(receiver);registered=false;}stopSession();super.onStop();}
    @Override protected void onDestroy(){stopSession();main.removeCallbacksAndMessages(null);worker.post(()->thread.quitSafely());super.onDestroy();}
    List<UsbDevice> devices(){List<UsbDevice> result=new ArrayList<>();if(usb!=null)for(UsbDevice d:usb.getDeviceList().values())if(UsbCameraBackend.isVideoDevice(d))result.add(d);result.sort(Comparator.comparing(UsbDevice::getDeviceName));return result;}
    void refreshDevices(){List<UsbDevice> list=devices();if(selected!=null){boolean present=false;for(UsbDevice d:list)if(d.getDeviceName().equals(selected.getDeviceName())){present=true;break;}if(!present){stopSession();selected=null;requested=null;}}
        if(selected==null&&!list.isEmpty())selected=list.get(0);allow.setEnabled(selected!=null);choose.setEnabled(!list.isEmpty());if(selected==null)status.setText(R.string.usb_camera_connect);else if(!streaming&&active){if(usb.hasPermission(selected))openSelected();else status.setText(R.string.usb_camera_allow);}}
    void chooseDevice(){List<UsbDevice> list=devices();String[] labels=new String[list.size()];for(int n=0;n<labels.length;n++)labels[n]=deviceLabel(list.get(n));new androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.usb_camera_choose).setItems(labels,(d,n)->{stopSession();selected=list.get(n);requested=null;requestPermission();}).setNegativeButton(android.R.string.cancel,null).show();}
    String deviceLabel(UsbDevice d){String name=d.getProductName();return (name==null?"USB camera":name)+String.format(Locale.ROOT," (%04x:%04x)",d.getVendorId(),d.getProductId());}
    void requestPermission(){if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){cameraPermission.launch(Manifest.permission.CAMERA);return;}requestUsbPermission();}
    void requestUsbPermission(){if(!active||selected==null)return;if(usb.hasPermission(selected)){openSelected();return;}Intent action=new Intent(permissionAction()).setPackage(getPackageName());PendingIntent reply=PendingIntent.getBroadcast(this,0,action,PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0));usb.requestPermission(selected,reply);}
    UsbCameraBackend openBackend(UsbDevice device)throws Exception{return new UsbCameraBackend.Native(usb,device);}
    void openSelected(){if(!active||selected==null||!preview.isAvailable()||!usb.hasPermission(selected)||ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
        stopSession();final int token=generation;final UsbDevice device=selected;final Surface next=new Surface(preview.getSurfaceTexture());status.setText(R.string.usb_camera_opening);
        worker.post(()->{if(generation!=token){next.release();return;}try{surface=next;backend=openBackend(device);List<StreamMode> supported=backend.modes();if(supported.isEmpty())throw new IllegalStateException("No renderable modes");StreamMode mode=requested!=null&&supported.contains(requested)?requested:initialMode(supported);StreamMode negotiated=backend.start(next,mode);UsbCameraBackend.FocusState fs;try{fs=backend.focusState();}catch(Exception unavailable){fs=new UsbCameraBackend.FocusState(false,false,false,0);}final UsbCameraBackend.FocusState found=fs;main.post(()->{if(!active||generation!=token)return;modes=supported;actual=negotiated;streaming=true;photo.setEnabled(frameReady);modesButton.setEnabled(true);status.setText(deviceLabel(device)+" · "+modeLabel(negotiated));bindFocus(found);transform();});}catch(Exception|LinkageError error){closeBackend();main.post(()->{if(active&&generation==token)status.setText(R.string.usb_camera_failed);});}});
    }
    static StreamMode initialMode(List<StreamMode> values){return Collections.min(values,Comparator.comparingLong(m->Math.abs((long)m.getWidth()*m.getHeight()-1280L*720)+Math.abs(m.getFps()-30)*10000L));}
    static String modeLabel(StreamMode m){return m.getWidth()+" × "+m.getHeight()+" · "+m.getFps()+" fps · "+m.getFormat().name();}
    void showModes(){String[] labels=new String[modes.size()];for(int n=0;n<labels.length;n++)labels[n]=modeLabel(modes.get(n));new androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.usb_camera_modes).setItems(labels,(d,n)->{requested=modes.get(n);openSelected();}).setNegativeButton(android.R.string.cancel,null).show();}
    void bindFocus(UsbCameraBackend.FocusState s){focusState=s;focus.setEnabled(streaming&&s.manualSupported);focus.setProgress(s.progress);auto.setEnabled(streaming&&s.autoSupported);auto.setText(s.automatic?"AF":"MF");if(!s.manualSupported&&!s.autoSupported)auto.setContentDescription(getString(R.string.usb_camera_no_focus));}
    void writeFocus(){if(!writingFocus||!streaming||focusCommandPending)return;writingFocus=false;focusCommandPending=true;final int token=generation,value=pendingFocus;
        worker.post(()->{if(token!=generation||backend==null)return;try{UsbCameraBackend.FocusState state=backend.setFocus(value);main.post(()->{if(active&&token==generation){focusCommandPending=false;if(!writingFocus)bindFocus(state);else writeFocus();}});}catch(Exception e){main.post(()->{if(active&&token==generation){focusCommandPending=false;writingFocus=false;status.setText(R.string.usb_camera_control_failed);}});}});
    }
    void postFocus(int token,UsbCameraBackend.FocusState s){main.post(()->{if(active&&token==generation)bindFocus(s);});}
    void postControlFailure(int token){main.post(()->{if(active&&token==generation){status.setText(R.string.usb_camera_control_failed);auto.setEnabled(focusState!=null&&focusState.autoSupported);}});}
    void stopSession(){generation++;streaming=false;frameReady=false;writingFocus=false;focusCommandPending=false;main.removeCallbacks(focusWrite);if(photo!=null)photo.setEnabled(false);if(focus!=null)focus.setEnabled(false);if(auto!=null)auto.setEnabled(false);if(modesButton!=null)modesButton.setEnabled(false);if(worker!=null)worker.post(this::closeBackend);}
    void closeBackend(){try{if(backend!=null)backend.close();}catch(Exception|LinkageError ignored){}finally{backend=null;if(surface!=null){surface.release();surface=null;}}}
    void transform(){if(actual==null||preview.getWidth()==0||preview.getHeight()==0)return;float w=preview.getWidth(),h=preview.getHeight(),fit=Math.min(w/actual.getWidth(),h/actual.getHeight());Matrix m=new Matrix();m.setScale(actual.getWidth()*fit/w*zoom,actual.getHeight()*fit/h*zoom,w/2,h/2);preview.setTransform(m);zoomLabel.setText(getString(R.string.usb_camera_zoom,zoom));zoomBar.setProgress(Math.round((zoom-1f)*100));}
    void savePhoto(){if(!streaming||!frameReady||savingPhoto||!preview.isAvailable())return;Bitmap bitmap=preview.getBitmap();if(bitmap==null)return;savingPhoto=true;photo.setEnabled(false);worker.post(()->{boolean saved=false;java.io.File file=null;try{java.io.File folder=new java.io.File(getExternalFilesDir(null),"FadCam/Camera/USB");if(!folder.isDirectory()&&!folder.mkdirs())throw new java.io.IOException("No output folder");file=java.io.File.createTempFile("USB_",".jpg",folder);try(java.io.FileOutputStream output=new java.io.FileOutputStream(file)){if(!bitmap.compress(Bitmap.CompressFormat.JPEG,95,output))throw new java.io.IOException("JPEG failed");}saved=true;RecordsFragment.requestRefresh();}catch(Exception e){if(file!=null)file.delete();}finally{bitmap.recycle();}final boolean done=saved;main.post(()->{savingPhoto=false;if(active){photo.setEnabled(streaming&&frameReady);Toast.makeText(this,done?R.string.usb_camera_saved:R.string.usb_camera_photo_failed,Toast.LENGTH_SHORT).show();}});});}
    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture,int width,int height){openSelected();}
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture,int width,int height){transform();}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture){stopSession();worker.post(texture::release);return false;}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture){if(streaming){frameReady=true;photo.setEnabled(!savingPhoto);}}
}
