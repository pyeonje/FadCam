package com.fadcam.ui;

import android.Manifest;
import android.app.PendingIntent;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.usb.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.utils.CopyIntegrity;
import com.fadcam.utils.RecordingStoragePaths;
import com.mikimn.droiduvc.StreamMode;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** In-app USB input. No external APK, built-in Camera2 preference changes, or background streaming. */
public class UsbCameraActivity extends AppCompatActivity implements TextureView.SurfaceTextureListener {
    private static final String USB_REQUEST_ID="usb_request_id";
    private static final AtomicInteger NEXT_USB_REQUEST_ID=new AtomicInteger();
    private UsbManager usb;
    private HandlerThread thread;
    private Handler worker;
    private final Handler main=new Handler(Looper.getMainLooper());
    private TextureView preview;
    private SurfaceTexture previewTexture;
    private TextView status,zoomLabel;
    private Button choose,allow,modesButton,auto,photo;
    private SeekBar focus,zoomBar;
    private UsbDevice selected;
    private long selectionRevision, cameraPermissionRevision;
    private boolean cameraPermissionPending;
    private PermissionRequest permissionRequest;
    private List<StreamMode> modes=Collections.emptyList();
    private StreamMode requested,actual;
    private volatile int generation;
    private boolean active,registered,destroyed,opening,streaming,awaitingRetry,frameReady,savingPhoto;
    private float zoom=1f;
    private long frameTimestampAtStart;
    // The USB worker alone uses and closes the native backend and its Surface.
    private UsbCameraBackend backend;
    private Surface surface;
    private final Object ownership=new Object();
    private final Set<Surface> pendingSurfaces=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<SurfaceTexture> transferredTextures=Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<SurfaceTexture> pendingTextures=Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean workerTerminated;

    private UsbCameraBackend.FocusState focusState;
    private FocusCommand pendingFocus;
    private Boolean desiredAutomatic;
    private long focusRevision;
    private boolean focusCommandPending;
    private final Runnable focusWrite=this::writeFocus;

    private static final class ConnectRequest {
        final int generation;
        final UsbManager manager;
        final UsbDevice device;
        final StreamMode mode;
        final SurfaceTexture texture;
        final Surface surface;
        ConnectRequest(int generation,UsbManager manager,UsbDevice device,StreamMode mode,
                       SurfaceTexture texture,Surface surface) {
            this.generation=generation;this.manager=manager;this.device=device;
            this.mode=mode;this.texture=texture;this.surface=surface;
        }
    }

    private static final class PermissionRequest {
        final int id;
        final UsbDevice device;
        final long revision;
        final PendingIntent reply;
        PermissionRequest(int id,UsbDevice device,long revision,PendingIntent reply) {
            this.id=id;this.device=device;this.revision=revision;this.reply=reply;
        }
    }

    private static final class FocusCommand {
        final int generation,progress;
        final long revision;
        final Boolean automatic;
        FocusCommand(int generation,long revision,Boolean automatic,int progress) {
            this.generation=generation;this.revision=revision;this.automatic=automatic;this.progress=progress;
        }
    }

    private String permissionAction(){return getPackageName()+".USB_CAMERA_PERMISSION";}
    private final ActivityResultLauncher<String> cameraPermission=registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),granted->{
                if(!cameraPermissionPending)return;
                cameraPermissionPending=false;
                if(!active||destroyed||selected==null||cameraPermissionRevision!=selectionRevision)return;
                if(granted&&hasCameraPermission())requestUsbPermission();
                else showPermissionRequired(false,true);
            });
    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            if(permissionAction().equals(intent.getAction())){handleUsbPermission(intent);return;}
            if(!active||destroyed)return;
            if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())
                    && sameDevice(selected,intentDevice(intent)))selectDevice(null);
            refreshDevices();
        }
    };
    @Override public void onCreate(Bundle saved){super.onCreate(saved);
        usb=(UsbManager)getSystemService(USB_SERVICE);
        startWorker();
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.BLACK);root.setPadding(dp(12),0,dp(12),dp(10));
        root.setOnApplyWindowInsetsListener((v,insets)->{int left,top,right,bottom;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());left=safe.left;top=safe.top;right=safe.right;bottom=safe.bottom;}else{left=insets.getSystemWindowInsetLeft();top=insets.getSystemWindowInsetTop();right=insets.getSystemWindowInsetRight();bottom=insets.getSystemWindowInsetBottom();}v.setPadding(left+dp(12),top,right+dp(12),bottom+dp(10));return insets;});
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);Button back=button("‹");back.setContentDescription(getString(android.R.string.cancel));back.setOnClickListener(v->finish());header.addView(back,new LinearLayout.LayoutParams(dp(56),dp(48)));TextView title=text(getString(R.string.usb_camera_title),20);header.addView(title);root.addView(header);
        FrameLayout area=new FrameLayout(this);preview=new TextureView(this);preview.setSurfaceTextureListener(this);area.addView(preview,new FrameLayout.LayoutParams(-1,-1));root.addView(area,new LinearLayout.LayoutParams(-1,0,1));
        ScaleGestureDetector pinch=new ScaleGestureDetector(this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector d){if(!streaming)return false;zoom=Math.max(1f,Math.min(8f,zoom*d.getScaleFactor()));transform();return true;}});
        preview.setOnTouchListener((v,e)->{pinch.onTouchEvent(e);if(e.getActionMasked()==MotionEvent.ACTION_UP)v.performClick();return true;});
        status=text(getString(R.string.usb_camera_connect),14);status.setMaxLines(3);root.addView(status);
        LinearLayout connectionRow=new LinearLayout(this);choose=button(getString(R.string.usb_camera_choose));allow=button(getString(R.string.usb_camera_allow));connectionRow.addView(choose,new LinearLayout.LayoutParams(0,-2,1));connectionRow.addView(allow,new LinearLayout.LayoutParams(0,-2,1));root.addView(connectionRow);
        modesButton=button(getString(R.string.usb_camera_modes));root.addView(modesButton);modesButton.setEnabled(false);modesButton.setOnClickListener(v->showModes());
        LinearLayout focusRow=new LinearLayout(this);auto=button("—");auto.setEnabled(false);auto.setContentDescription(getString(R.string.usb_camera_focus_unavailable));focusRow.addView(auto,new LinearLayout.LayoutParams(dp(72),dp(52)));focus=new SeekBar(this);focus.setMax(1000);focus.setEnabled(false);focus.setContentDescription(getString(R.string.usb_camera_focus_unavailable));focusRow.addView(focus,new LinearLayout.LayoutParams(0,dp(52),1));root.addView(focusRow);
        zoomLabel=text(getString(R.string.usb_camera_zoom,1f),14);root.addView(zoomLabel);zoomBar=new SeekBar(this);zoomBar.setMax(700);zoomBar.setEnabled(false);zoomBar.setContentDescription(getString(R.string.usb_camera_zoom,1f));zoomBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int value,boolean user){if(user&&streaming){zoom=1f+value/100f;transform();}}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});root.addView(zoomBar);
        photo=button(getString(R.string.usb_camera_photo));photo.setEnabled(false);photo.setOnClickListener(v->savePhoto());root.addView(photo);setContentView(root);
        choose.setOnClickListener(v->chooseDevice());allow.setOnClickListener(v->requestPermission());
        auto.setOnClickListener(v->{
            if(!streaming||focusState==null||!focusState.autoSupported)return;
            boolean current=desiredAutomatic!=null?desiredAutomatic:focusState.automatic;
            queueFocus(!current,0);
        });
        focus.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int value,boolean fromUser){
                if(fromUser&&streaming&&focusState!=null&&focusState.manualSupported)queueFocus(null,value);
            }
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){main.removeCallbacks(focusWrite);writeFocus();}
        });
    }
    TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextColor(Color.WHITE);t.setTextSize(size);return t;}
    Button button(String value){Button b=new Button(this);b.setText(value);b.setAllCaps(false);return b;}
    int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override protected void onStart(){
        super.onStart();
        active=true;
        IntentFilter filter=new IntentFilter(permissionAction());
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(this,receiver,filter,ContextCompat.RECEIVER_NOT_EXPORTED);
        registered=true;
        refreshDevices();
    }

    @Override protected void onResume(){super.onResume();refreshDevices();}

    @Override protected void onStop(){
        active=false;
        if(registered){unregisterReceiver(receiver);registered=false;}
        cancelUsbPermission();
        stopSession();
        super.onStop();
    }

    @Override protected void onDestroy(){
        if(!destroyed){
            destroyed=true;
            active=false;
            cameraPermissionPending=false;
            cancelUsbPermission();
            stopSession();
            main.removeCallbacksAndMessages(null);
            quitWorker();
        }
        super.onDestroy();
    }

    void startWorker(){
        thread=new HandlerThread("FadCam-USB"){
            @Override public void run(){try{super.run();}finally{finishWorker();}}
        };
        thread.start();
        worker=new Handler(thread.getLooper());
    }

    boolean postWorker(Runnable work){return worker!=null&&worker.post(work);}
    void quitWorker(){if(thread!=null)thread.quitSafely();}

    // The finalizer also owns releases registered after the queue started rejecting posts.
    void finishWorker(){
        closeBackend();
        synchronized(ownership){
            for(Surface pending:pendingSurfaces)pending.release();
            pendingSurfaces.clear();
            for(SurfaceTexture texture:pendingTextures)texture.release();
            pendingTextures.clear();
            workerTerminated=true;
        }
    }

    private boolean hasCameraPermission(){
        return ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED;
    }

    private static boolean sameDevice(UsbDevice first,UsbDevice second){
        if(first==null||second==null)return false;
        return first==second||(Objects.equals(first.getDeviceName(),second.getDeviceName())
                &&first.getDeviceId()==second.getDeviceId()&&first.getVendorId()==second.getVendorId()
                &&first.getProductId()==second.getProductId());
    }

    private static UsbDevice intentDevice(Intent intent){
        if(Build.VERSION.SDK_INT>=33)return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE,UsbDevice.class);
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    List<UsbDevice> devices(){
        List<UsbDevice> list=new ArrayList<>();
        if(usb!=null)for(UsbDevice device:usb.getDeviceList().values())if(UsbCameraBackend.isVideoDevice(device))list.add(device);
        list.sort(Comparator.comparing(UsbDevice::getDeviceName));
        return list;
    }

    void refreshDevices(){
        if(!active||destroyed)return;
        List<UsbDevice> list=devices();
        if(selected==null||list.stream().noneMatch(device->sameDevice(selected,device)))
            selectDevice(list.isEmpty()?null:list.get(0));
        choose.setEnabled(!list.isEmpty());
        allow.setEnabled(selected!=null&&!streaming&&!opening);
        if(selected==null)status.setText(R.string.usb_camera_connect);
        else if(!hasCameraPermission())showPermissionRequired(false,false);
        else if(!usb.hasPermission(selected))showPermissionRequired(true,false);
        else {allow.setText(R.string.usb_camera_retry);openSelected();}
    }

    void selectDevice(UsbDevice device){
        if((selected==null&&device==null)||sameDevice(selected,device))return;
        cancelUsbPermission();
        stopSession();
        selectionRevision++;
        selected=device;
        allow.setEnabled(selected!=null);
        clearModes();
    }

    private void clearModes(){
        modes=Collections.emptyList();
        requested=null;
        awaitingRetry=false;
        modesButton.setEnabled(false);
    }

    void chooseDevice(){
        List<UsbDevice> choices=devices();
        if(choices.isEmpty())return;
        String[] labels=new String[choices.size()];
        for(int n=0;n<labels.length;n++)labels[n]=deviceLabel(choices.get(n));
        new AlertDialog.Builder(this).setTitle(R.string.usb_camera_choose).setItems(labels,(dialog,index)->{
            UsbDevice choice=choices.get(index);
            if(!active||destroyed)return;
            if(devices().stream().noneMatch(device->sameDevice(choice,device))){refreshDevices();return;}
            selectDevice(choice);
            requestPermission();
        }).setNegativeButton(android.R.string.cancel,null).show();
    }

    String deviceLabel(UsbDevice device){
        String name=device.getProductName();
        if(name==null||name.trim().isEmpty())name=getString(R.string.usb_camera_unknown_device);
        return name+String.format(Locale.ROOT," (%04x:%04x)",device.getVendorId(),device.getProductId());
    }

    private void showPermissionRequired(boolean cameraAllowed,boolean denied){
        if(opening||streaming)stopSession();
        clearModes();
        if(!cameraAllowed)cancelUsbPermission();
        allow.setText(cameraAllowed?R.string.usb_camera_allow:R.string.usb_camera_allow_camera);
        status.setText(cameraAllowed?(denied?R.string.usb_camera_denied:R.string.usb_camera_allow)
                :(denied?R.string.usb_camera_camera_denied:R.string.usb_camera_camera_required));
    }

    void requestPermission(){
        if(!active||destroyed||selected==null)return;
        if(!hasCameraPermission()){
            showPermissionRequired(false,false);
            if(!cameraPermissionPending){
                cameraPermissionPending=true;
                cameraPermissionRevision=selectionRevision;
                cameraPermission.launch(Manifest.permission.CAMERA);
            }
        }else requestUsbPermission();
    }

    void requestUsbPermission(){
        if(!active||destroyed||selected==null)return;
        if(!hasCameraPermission()){showPermissionRequired(false,false);return;}
        if(usb.hasPermission(selected)){allow.setText(R.string.usb_camera_retry);startConnection(awaitingRetry);return;}
        showPermissionRequired(true,false);
        if(permissionRequest!=null)return;
        int id=NEXT_USB_REQUEST_ID.incrementAndGet();
        Intent action=new Intent(permissionAction()).setPackage(getPackageName()).putExtra(USB_REQUEST_ID,id);
        PendingIntent reply=PendingIntent.getBroadcast(this,id,action,
                Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0);
        PermissionRequest request=new PermissionRequest(id,selected,selectionRevision,reply);
        permissionRequest=request;
        try{usb.requestPermission(request.device,request.reply);}
        catch(RuntimeException unavailable){cancelUsbPermission();showPermissionRequired(true,true);}
    }

    private void cancelUsbPermission(){
        if(permissionRequest!=null){permissionRequest.reply.cancel();permissionRequest=null;}
    }

    private void handleUsbPermission(Intent intent){
        PermissionRequest request=permissionRequest;
        if(!active||destroyed||request==null||request.revision!=selectionRevision
                ||intent.getIntExtra(USB_REQUEST_ID,0)!=request.id||!sameDevice(request.device,selected)
                ||!sameDevice(request.device,intentDevice(intent)))return;
        cancelUsbPermission();
        if(!hasCameraPermission())showPermissionRequired(false,false);
        else if(!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false)||!usb.hasPermission(request.device))
            showPermissionRequired(true,true);
        else {allow.setText(R.string.usb_camera_retry);startConnection(false);}
    }

    UsbCameraBackend openBackend(UsbManager manager,UsbDevice device)throws Exception{
        return new UsbCameraBackend.Native(manager,device);
    }
    Surface createSurface(SurfaceTexture texture){return new Surface(texture);}
    void openSelected(){startConnection(false);}

    private void startConnection(boolean force){
        if(!active||destroyed||selected==null||previewTexture==null)return;
        if(!hasCameraPermission()){showPermissionRequired(false,false);return;}
        if(!usb.hasPermission(selected)){showPermissionRequired(true,false);return;}
        if(!force&&(opening||streaming||awaitingRetry))return;
        stopSession();
        SurfaceTexture texture=previewTexture;
        final Surface next;
        try{next=createSurface(texture);}
        catch(RuntimeException unavailable){connectionFailed();return;}
        ConnectRequest request=new ConnectRequest(generation,usb,selected,requested,texture,next);
        opening=true;
        awaitingRetry=false;
        allow.setEnabled(false);
        status.setText(R.string.usb_camera_opening);
        synchronized(ownership){
            if(!workerTerminated){
                pendingSurfaces.add(next);
                if(postWorker(()->connect(request)))return;
                pendingSurfaces.remove(next);
            }
        }
        // The rejected task never gave this Surface to native code.
        next.release();
        connectionFailed();
        quitWorker();
    }

    private boolean current(ConnectRequest request){
        return active&&!destroyed&&request.generation==generation&&sameDevice(request.device,selected);
    }

    private boolean canContinue(ConnectRequest request){
        if(request.generation!=generation)return false;
        boolean cameraAllowed=hasCameraPermission();
        if(cameraAllowed&&request.manager.hasPermission(request.device))return request.generation==generation;
        main.post(()->{if(current(request))showPermissionRequired(cameraAllowed,false);});
        return false;
    }

    private void connectionFailed(){
        opening=false;
        awaitingRetry=true;
        allow.setEnabled(selected!=null);
        status.setText(R.string.usb_camera_failed);
    }

    private void connect(ConnectRequest request){
        UsbCameraBackend opened=null;
        boolean kept=false;
        try{
            if(!canContinue(request))return;
            opened=openBackend(request.manager,request.device);
            if(!canContinue(request))return;
            List<StreamMode> available=Collections.unmodifiableList(new ArrayList<>(opened.modes()));
            if(!canContinue(request))return;
            main.post(()->{if(current(request)){modes=available;modesButton.setEnabled(!available.isEmpty());}});
            if(available.isEmpty())throw new IllegalStateException("No renderable USB modes");
            StreamMode mode=available.contains(request.mode)?request.mode:initialMode(available);
            if(!canContinue(request))return;
            // TextureView can report an update after a resize without consuming a camera frame.
            long previousFrameTimestamp=request.texture.getTimestamp();
            StreamMode negotiated=Objects.requireNonNull(opened.start(request.surface,mode));
            if(!canContinue(request))return;
            backend=opened;
            surface=request.surface;
            kept=true;
            main.post(()->{
                if(!current(request))return;
                actual=negotiated;
                opening=false;
                streaming=true;
                frameTimestampAtStart=previousFrameTimestamp;
                allow.setEnabled(false);
                zoomBar.setEnabled(true);
                status.setText(deviceLabel(request.device)+" · "+modeLabel(negotiated));
                transform();
                checkPhotoFrame(request.texture);
            });
            if(!canContinue(request))return;
            UsbCameraBackend.FocusState state;
            try{state=opened.focusState();}catch(Exception|LinkageError unavailable){state=null;}
            if(!canContinue(request))return;
            final UsbCameraBackend.FocusState found=state;
            main.post(()->{if(current(request))bindFocus(found);});
        }catch(Exception|LinkageError failure){
            main.post(()->{if(current(request))connectionFailed();});
        }finally{
            synchronized(ownership){pendingSurfaces.remove(request.surface);}
            if(!kept)closeResources(opened,request.surface);
        }
    }

    static StreamMode initialMode(List<StreamMode> values){
        return Collections.min(values,Comparator.comparingLong(mode->Math.abs((long)mode.getWidth()*mode.getHeight()-1280L*720)
                +Math.abs(mode.getFps()-30)*10000L));
    }
    static String modeLabel(StreamMode mode){
        return mode.getWidth()+" × "+mode.getHeight()+" · "+mode.getFps()+" fps · "+mode.getFormat().name();
    }
    void showModes(){
        List<StreamMode> choices=modes;
        UsbDevice device=selected;
        if(!active||destroyed||choices.isEmpty())return;
        String[] labels=new String[choices.size()];
        for(int n=0;n<labels.length;n++)labels[n]=modeLabel(choices.get(n));
        new AlertDialog.Builder(this).setTitle(R.string.usb_camera_modes)
                .setItems(labels,(dialog,index)->selectMode(device,choices.get(index)))
                .setNegativeButton(android.R.string.cancel,null).show();
    }
    void selectMode(UsbDevice device,StreamMode mode){
        if(!active||destroyed||!sameDevice(device,selected)||!modes.contains(mode))return;
        requested=mode;
        startConnection(true);
    }

    void bindFocus(UsbCameraBackend.FocusState state){
        focusState=state;
        if(state!=null)focus.setProgress(state.progress);
        renderFocus();
    }

    private void renderFocus(){
        boolean canAuto=streaming&&focusState!=null&&focusState.autoSupported;
        boolean canManual=streaming&&focusState!=null&&focusState.manualSupported;
        focus.setEnabled(canManual);
        focus.setContentDescription(getString(canManual?R.string.usb_camera_manual_focus:R.string.usb_camera_focus_unavailable));
        auto.setEnabled(canAuto);
        boolean automatic=desiredAutomatic!=null?desiredAutomatic:focusState!=null&&focusState.automatic;
        auto.setText(canAuto||canManual?(automatic?"AF":"MF"):"—");
        auto.setContentDescription(getString(canAuto
                ?(automatic?R.string.usb_camera_switch_to_mf:R.string.usb_camera_switch_to_af)
                :(canManual?R.string.usb_camera_manual_focus:R.string.usb_camera_focus_unavailable)));
    }

    private void queueFocus(Boolean automatic,int progress){
        pendingFocus=new FocusCommand(generation,++focusRevision,automatic,progress);
        desiredAutomatic=automatic!=null&&automatic;
        main.removeCallbacks(focusWrite);
        renderFocus();
        if(automatic!=null)writeFocus();else main.postDelayed(focusWrite,65);
    }

    void writeFocus(){
        if(!streaming||focusCommandPending||pendingFocus==null)return;
        FocusCommand command=pendingFocus;
        pendingFocus=null;
        focusCommandPending=true;
        if(!postWorker(()->runFocus(command)))completeFocus(command,null,true);
    }

    private void runFocus(FocusCommand command){
        if(command.generation!=generation)return;
        UsbCameraBackend.FocusState state=null;
        boolean failed=false;
        if(backend==null)failed=true;
        else try{
            state=command.automatic==null?backend.setFocus(command.progress):backend.setAutoFocus(command.automatic);
        }catch(Exception|LinkageError error){
            failed=true;
            if(command.generation==generation)try{state=backend.focusState();}catch(Exception|LinkageError ignored){}
        }
        final UsbCameraBackend.FocusState found=state;
        final boolean error=failed;
        main.post(()->completeFocus(command,found,error));
    }

    private void completeFocus(FocusCommand command,UsbCameraBackend.FocusState state,boolean failed){
        if(!active||destroyed||command.generation!=generation)return;
        focusCommandPending=false;
        if(command.revision==focusRevision){
            desiredAutomatic=null;
            bindFocus(state);
            if(failed)status.setText(R.string.usb_camera_control_failed);
            else if(actual!=null&&selected!=null)status.setText(deviceLabel(selected)+" · "+modeLabel(actual));
        }
        writeFocus();
    }

    void stopSession(){
        generation++;
        opening=false;
        streaming=false;
        actual=null;
        frameReady=false;
        focusCommandPending=false;
        pendingFocus=null;
        desiredAutomatic=null;
        main.removeCallbacks(focusWrite);
        if(!destroyed&&photo!=null){
            photo.setEnabled(false);
            zoomBar.setEnabled(false);
            allow.setEnabled(selected!=null);
            bindFocus(null);
        }
        if(!postWorker(this::closeBackend))quitWorker();
    }

    private void closeBackend(){
        UsbCameraBackend owned=backend;
        Surface target=surface;
        backend=null;
        surface=null;
        closeResources(owned,target);
    }

    private static void closeResources(UsbCameraBackend backend,Surface surface){
        try{if(backend!=null)backend.close();}
        catch(Exception|LinkageError ignored){}
        finally{if(surface!=null)surface.release();}
    }

    void transform(){
        if(actual==null||preview.getWidth()==0||preview.getHeight()==0)return;
        float width=preview.getWidth(),height=preview.getHeight();
        float fit=Math.min(width/actual.getWidth(),height/actual.getHeight());
        Matrix matrix=new Matrix();
        matrix.setScale(actual.getWidth()*fit/width*zoom,actual.getHeight()*fit/height*zoom,width/2,height/2);
        preview.setTransform(matrix);
        String label=getString(R.string.usb_camera_zoom,zoom);
        zoomLabel.setText(label);
        zoomBar.setContentDescription(label);
        zoomBar.setProgress(Math.round((zoom-1f)*100));
    }

    void savePhoto(){
        if(!streaming||!frameReady||savingPhoto||!preview.isAvailable())return;
        Bitmap bitmap=preview.getBitmap();
        if(bitmap==null)return;
        savingPhoto=true;
        photo.setEnabled(false);
        // Capture the selected tree at the shutter action, not later on the I/O worker.
        String configuredTree=SharedPreferencesManager.getInstance(this).getCustomStorageUri();
        if(!postWorker(()->{
            boolean saved=false;
            try{
                savePhotoBitmap(bitmap,configuredTree);
                saved=true;
                RecordsFragment.requestRefresh();
            }catch(IOException|RuntimeException failure){
                com.fadcam.FLog.w("UsbCameraActivity","Could not save USB preview JPEG",failure);
            }finally{bitmap.recycle();}
            final boolean done=saved;
            main.post(()->completePhoto(done));
        })){
            bitmap.recycle();
            completePhoto(false);
        }
    }

    private Uri savePhotoBitmap(Bitmap bitmap,String configuredTree)throws IOException{
        if(configuredTree!=null&&!configuredTree.isEmpty()){
            Uri tree=Uri.parse(configuredTree);
            // Match the gallery's selected source. A readable but unwritable tree cannot
            // silently redirect to an internal file that this gallery would not scan.
            for(UriPermission grant:getContentResolver().getPersistedUriPermissions()){
                if(tree.equals(grant.getUri())&&grant.isReadPermission()){
                    if(!grant.isWritePermission())throw new IOException("Selected photo tree is read-only");
                    return savePhotoToTree(bitmap,configuredTree);
                }
            }
        }
        File camera=RecordingStoragePaths.getInternalCategoryDir(this,RecordingStoragePaths.Category.CAMERA,true);
        if(camera==null)throw new IOException("No internal camera directory");
        File folder=new File(camera,"USB");
        if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("No USB photo directory");
        File file=File.createTempFile("USB_",".jpg",folder);
        try{
            try(OutputStream output=new FileOutputStream(file)){writePhotoJpeg(bitmap,output);}
            return Uri.fromFile(file);
        }catch(IOException|RuntimeException failure){
            file.delete(); // Only this shutter's incomplete file.
            throw failure;
        }
    }

    private Uri savePhotoToTree(Bitmap bitmap,String configuredTree)throws IOException{
        DocumentFile root=RecordingStoragePaths.getSafBaseDir(this,configuredTree);
        if(root==null)throw new IOException("Selected photo tree unavailable");
        root=resolveIndexedSafRoot(root);
        DocumentFile camera=RecordingStoragePaths.findOrCreateChildDirectory(root,Constants.RECORDING_SUBDIR_CAMERA,true);
        if(camera==null)throw new IOException("No Camera directory in selected tree");
        DocumentFile usb=RecordingStoragePaths.findOrCreateChildDirectory(camera,"USB",true);
        if(usb==null)throw new IOException("No USB directory in selected tree");
        Uri folder=usb.getUri();
        Set<String> existingIds=listUsbPhotoDocumentIds(folder);
        String filename="USB_"+UUID.randomUUID()+".jpg";
        DocumentFile photo=usb.createFile("image/jpeg",filename);
        if(photo==null)throw new IOException("Cannot create JPEG document");
        Uri uri=photo.getUri();
        // A broken provider may return an existing document from createDocument().
        // Do not even open it for write unless its identity and unique name match this shutter.
        if(!"content".equals(uri.getScheme())||!Objects.equals(folder.getAuthority(),uri.getAuthority())
                ||!DocumentsContract.isTreeUri(uri)
                ||!DocumentsContract.getTreeDocumentId(folder).equals(DocumentsContract.getTreeDocumentId(uri))
                ||DocumentsContract.getDocumentId(folder).equals(DocumentsContract.getDocumentId(uri))
                ||existingIds.contains(DocumentsContract.getDocumentId(uri))
                ||!filename.equals(photo.getName())){
            throw new IOException("Provider did not return a fresh JPEG document");
        }
        try{
            MessageDigest digest=CopyIntegrity.sha256();
            OutputStream raw=getContentResolver().openOutputStream(uri,"w");
            if(raw==null)throw new IOException("Cannot open JPEG document");
            long bytes;
            try(PhotoWriteDigest output=new PhotoWriteDigest(raw,digest)){
                writePhotoJpeg(bitmap,output);
                bytes=output.bytes;
            }
            // Read the exact URI again after close: neither close nor reported provider size
            // proves that the bytes originally supplied by the encoder reached storage.
            if(bytes==0||!CopyIntegrity.matches(getContentResolver().openInputStream(uri),bytes,digest.digest())){
                throw new IOException("Stored USB JPEG does not match encoded bytes");
            }
            return uri;
        }catch(IOException|RuntimeException failure){
            boolean deleted=false;
            try{deleted=photo.delete();}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}
            if(!deleted)rememberIncompleteUsbPhoto(uri);
            throw failure;
        }
    }

    private Set<String> listUsbPhotoDocumentIds(Uri folder)throws IOException{
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(folder,DocumentsContract.getDocumentId(folder));
        Set<String> result=new HashSet<>();
        try(android.database.Cursor cursor=getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},null,null,null)){
            Bundle extras=cursor==null?null:cursor.getExtras();
            if(cursor==null||(extras!=null&&(extras.getBoolean(DocumentsContract.EXTRA_LOADING)
                    ||extras.containsKey(DocumentsContract.EXTRA_ERROR)))){
                throw new IOException("USB photo directory is not fully listed");
            }
            while(cursor.moveToNext()){
                String id=cursor.getString(0);
                if(id==null)throw new IOException("USB photo document identity unavailable");
                result.add(id);
            }
        }
        return result;
    }

    private void rememberIncompleteUsbPhoto(Uri uri){
        // A provider may deny deletion. Retain the exact URI for manual recovery;
        // do not retry deletion automatically, as ownership could change.
        SharedPreferences prefs=getSharedPreferences("usb_photo_recovery",MODE_PRIVATE);
        synchronized(prefs){
            Set<String> pending=new HashSet<>(prefs.getStringSet("pending_uris",Collections.emptySet()));
            pending.add(uri.toString());
            if(!prefs.edit().putStringSet("pending_uris",pending).commit()){
                com.fadcam.FLog.w("UsbCameraActivity","Could not persist USB JPEG recovery URI: "+uri);
            }
        }
    }

    private static final class PhotoWriteDigest extends DigestOutputStream {
        long bytes;
        PhotoWriteDigest(OutputStream output,MessageDigest digest){super(output,digest);}
        @Override public void write(int b)throws IOException{
            super.write(b);
            bytes++;
        }
        @Override public void write(byte[] data,int offset,int length)throws IOException{
            super.write(data,offset,length);
            bytes+=length;
        }
    }

    private DocumentFile resolveIndexedSafRoot(DocumentFile root)throws IOException{
        Uri parent=root.getUri();
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(parent,DocumentsContract.getDocumentId(parent));
        String nestedId=null;
        try(android.database.Cursor cursor=getContentResolver().query(children,new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){
            Bundle extras=cursor==null?null:cursor.getExtras();
            if(cursor==null||(extras!=null&&(extras.getBoolean(DocumentsContract.EXTRA_LOADING)
                    ||extras.containsKey(DocumentsContract.EXTRA_ERROR)))){
                throw new IOException("Selected photo tree is not fully listed");
            }
            while(cursor.moveToNext()){
                if(Constants.RECORDING_DIRECTORY.equals(cursor.getString(1))
                        &&DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))){
                    if(nestedId!=null)throw new IOException("Ambiguous recording root");
                    nestedId=cursor.getString(0);
                    if(nestedId==null)throw new IOException("Recording folder identity unavailable");
                }
            }
        }
        if(nestedId==null)return root;
        DocumentFile nested=RecordingStoragePaths.findOrCreateChildDirectory(root,Constants.RECORDING_DIRECTORY,false);
        if(nested==null||!nestedId.equals(DocumentsContract.getDocumentId(nested.getUri())))
            throw new IOException("Recording folder changed during photo save");
        return nested;
    }

    private static void writePhotoJpeg(Bitmap bitmap,OutputStream output)throws IOException{
        if(!bitmap.compress(Bitmap.CompressFormat.JPEG,95,output))throw new IOException("JPEG encoding failed");
        output.flush();
    }

    private void completePhoto(boolean saved){
        savingPhoto=false;
        if(active&&!destroyed){
            photo.setEnabled(streaming&&frameReady);
            Toast.makeText(this,saved?R.string.usb_camera_saved:R.string.usb_camera_photo_failed,Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture,int width,int height){
        if(destroyed)return;
        previewTexture=texture;
        openSelected();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture,int width,int height){
        if(!destroyed&&texture==previewTexture)transform();
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture){
        if(texture==previewTexture){
            previewTexture=null;
            if(!destroyed)stopSession();
        }
        synchronized(ownership){
            if(transferredTextures.contains(texture))return false;
            if(workerTerminated)return true; // No native user remains; TextureView can release it.
            transferredTextures.add(texture);
            pendingTextures.add(texture);
            if(!postWorker(()->{
                synchronized(ownership){if(pendingTextures.remove(texture))texture.release();}
            }))quitWorker();
            return false;
        }
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture){checkPhotoFrame(texture);}

    private void checkPhotoFrame(SurfaceTexture texture){
        if(!active||destroyed||!streaming||frameReady||texture!=previewTexture)return;
        if(texture.getTimestamp()==frameTimestampAtStart)return;
        frameReady=true;
        photo.setEnabled(!savingPhoto);
    }
}
