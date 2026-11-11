package com.fadcam.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import com.fadcam.*;
import com.fadcam.services.RecordingService;
import com.fadcam.utils.CameraLensCatalog;
import com.fadcam.utils.DeviceHelper;
import com.google.android.material.button.MaterialButton;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** Quick camera tools use the same saved preset as the background launcher. */
final class PersonalQuickControls {
    private final Context context;
    private final SharedPreferencesManager prefs;
    private final BooleanSupplier ready, idle;
    private final Runnable changed, closeAdjustment;
    private final View root;
    private final MaterialButton quality, mic, torch, stabilization, grid, more;
    private boolean landscape, recordingUi;
    private CameraCharacteristics capabilities;
    private PopupWindow popup;
    PersonalQuickControls(Context c, View root, SharedPreferencesManager p, BooleanSupplier ready,
            BooleanSupplier idle, Runnable changed, Runnable closeAdjustment) {
        context=c;this.root=root;prefs=p;this.ready=ready;this.idle=idle;this.changed=changed;this.closeAdjustment=closeAdjustment;
        quality=root.findViewById(R.id.personal_quality_control);mic=root.findViewById(R.id.personal_mic_control);
        torch=root.findViewById(R.id.personal_torch_control);stabilization=root.findViewById(R.id.personal_stabilization_control);
        grid=root.findViewById(R.id.personal_grid_control);more=root.findViewById(R.id.personal_camera_more);
        more.setOnClickListener(v -> showMore());
        mic.setIconResource(R.drawable.personal_camera_mic);
        stabilization.setIconResource(R.drawable.personal_camera_stabilization);stabilization.setText("");
        for(MaterialButton button:new MaterialButton[]{mic,grid,torch,stabilization,more}) {
            button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);button.setIconPadding(0);
            button.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);button.setIconTint(android.content.res.ColorStateList.valueOf(Color.WHITE));
        }
        quality.setOnClickListener(v -> showQuality());
        mic.setIconResource(R.drawable.personal_camera_mic);mic.setIconTint(android.content.res.ColorStateList.valueOf(Color.WHITE));mic.setText("");mic.setIconSize(dp(24));
        grid.setIconResource(R.drawable.ic_grid);grid.setIconTint(android.content.res.ColorStateList.valueOf(Color.WHITE));grid.setText("");grid.setIconSize(dp(24));
        grid.setOnClickListener(v -> {
            boolean on=!prefs.sharedPreferences.getBoolean("personal_camera_grid",false);
            prefs.sharedPreferences.edit().putBoolean("personal_camera_grid",on).apply();
            root.findViewById(R.id.personal_camera_guides).setVisibility(on?View.VISIBLE:View.GONE);refresh(capabilities);
        });
        mic.setOnClickListener(v -> {
            if(!idle.getAsBoolean())return;
            closeAdjustment.run();LinearLayout panel=panel(context.getString(R.string.personal_mic));
            for(boolean audio:new boolean[]{true,false}) {
                MaterialButton button=button(context.getString(audio?R.string.personal_phone_mic:R.string.personal_silent_video),prefs.isRecordAudioEnabled()==audio);
                button.setOnClickListener(w -> {if(!idle.getAsBoolean())return;prefs.setRecordAudioEnabled(audio);dismiss();changed.run();});panel.addView(button);
            }
            show(panel);
        });
        torch.setOnClickListener(v -> {
            if(ready.getAsBoolean())context.startService(new Intent(context,RecordingService.class).setAction(Constants.INTENT_ACTION_TOGGLE_RECORDING_TORCH));
            torch.postDelayed(() -> refresh(capabilities),200);
        });
        stabilization.setOnClickListener(v -> {
            if(!ready.getAsBoolean() || !supportsStabilization(capabilities))return;
            boolean on=!prefs.isVideoStabilizationEnabled();prefs.setVideoStabilizationEnabled(on);
            context.startService(new Intent(context,RecordingService.class).setAction(Constants.INTENT_ACTION_SET_VIDEO_STABILIZATION)
                    .putExtra(Constants.EXTRA_VIDEO_STABILIZATION_ENABLED,on));refresh(capabilities);
        });
    }
    void refresh(CameraCharacteristics c) {
        capabilities=c;
        quality.setText(sizeLabel(prefs.getCameraResolution())+"\n"+prefs.getSpecificVideoFrameRate(prefs.getCameraSelection()));
        quality.setEnabled(c!=null && idle.getAsBoolean());mic.setEnabled(idle.getAsBoolean());
        torch.setEnabled(ready.getAsBoolean() && c!=null && Boolean.TRUE.equals(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)));
        torch.setIconResource(prefs.sharedPreferences.getBoolean(Constants.PREF_TORCH_STATE,false)?R.drawable.personal_camera_flash:R.drawable.personal_camera_flash_off);
        torch.setIconTint(android.content.res.ColorStateList.valueOf(Color.WHITE));torch.setText("");torch.setIconSize(dp(24));
        boolean stabilized=supportsStabilization(c);
        stabilization.setEnabled(ready.getAsBoolean() && stabilized);stabilization.setIconTint(android.content.res.ColorStateList.valueOf(stabilized && prefs.isVideoStabilizationEnabled()?0xFFFFD400:Color.WHITE));
        boolean guides=prefs.sharedPreferences.getBoolean("personal_camera_grid",false);
        root.findViewById(R.id.personal_camera_guides).setVisibility(guides?View.VISIBLE:View.GONE);
        grid.setSelected(guides);layoutTools();
    }
    static String sizeLabel(Size size) {
        int width=Math.max(size.getWidth(),size.getHeight());
        return width>=7680?"8K":width>=3840?"UHD":width>=1920?"FHD":"HD";
    }
    private static boolean supportsStabilization(CameraCharacteristics c) {
        return c!=null && (com.fadcam.utils.PersonalProSettings.contains(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION),android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                || com.fadcam.utils.PersonalProSettings.contains(c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES),android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON));
    }
    private boolean cameraSupports(Size size,int fps) {
        if(capabilities==null)return false;
        StreamConfigurationMap map=capabilities.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if(map==null)return false;
        if(fps>=120) {
            // RecordingService deliberately uses standard sessions on Samsung.
            // Advertising constrained-high-speed rates here would save a preset
            // that this pipeline cannot actually deliver, despite HAL support.
            if(DeviceHelper.isSamsung())return false;
            try {
                CameraManager manager=(CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
                if(prefs.getCameraSelection()==CameraType.BACK && CameraLensCatalog.resolve(manager,prefs.getSelectedBackCameraId()).physicalId!=null)return false;
                for(Size s:map.getHighSpeedVideoSizes())if(s.equals(size))for(Range<Integer> r:map.getHighSpeedVideoFpsRangesFor(size))if(r.contains(fps))return true;
            } catch(Exception unavailable) { return false; }
            return false;
        }
        Size[] sizes=map.getOutputSizes(SurfaceTexture.class);
        if(sizes==null || !Arrays.asList(sizes).contains(size))return false;
        long duration=map.getOutputMinFrameDuration(SurfaceTexture.class,size);
        if(duration>1_000_000_000L/fps+1000)return false;
        Range<Integer>[] ranges=capabilities.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if(ranges!=null)for(Range<Integer> r:ranges)if(r.contains(fps))return true;
        return false;
    }
    private boolean supports(Size size,int fps) {
        if(!cameraSupports(size,fps))return false;
        try {
            MediaFormat format=MediaFormat.createVideoFormat(prefs.getVideoCodec().getMimeType(),size.getWidth(),size.getHeight());
            format.setInteger(MediaFormat.KEY_FRAME_RATE,fps);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            return new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)!=null;
        } catch(RuntimeException unavailable) {return false;}
    }
    void setRecordingUi(boolean recording) {if(recordingUi!=recording){recordingUi=recording;layoutTools();}}
    void reflow(boolean value) {landscape=value;layoutTools();}
    private void layoutTools() {
        PersonalCameraLayout g=new PersonalCameraLayout(context);
        LinearLayout tools=root.findViewById(R.id.personal_quick_tools);
        tools.setOrientation(landscape?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);tools.setGravity(Gravity.CENTER);
        android.widget.FrameLayout.LayoutParams position=(android.widget.FrameLayout.LayoutParams)tools.getLayoutParams();
        position.width=landscape?g.px(127):android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
        position.height=landscape?android.view.ViewGroup.LayoutParams.WRAP_CONTENT:g.px(127);
        position.gravity=Gravity.TOP|Gravity.START;
        position.setMargins(0,landscape?g.px(recordingUi?52:234):g.px(108),0,0);
        position.setMarginStart(g.px(landscape?108:recordingUi?52:234));position.setMarginEnd(0);tools.setLayoutParams(position);
        grid.setVisibility(View.GONE);root.findViewById(R.id.personal_home_settings).setVisibility(View.GONE);
        quality.setVisibility(recordingUi?View.GONE:View.VISIBLE);
        for(MaterialButton button:new MaterialButton[]{stabilization,mic,quality,torch}) {
            LinearLayout.LayoutParams size=(LinearLayout.LayoutParams)button.getLayoutParams();size.width=size.height=g.px(127);button.setLayoutParams(size);
            button.setPadding(0,0,0,0);button.setMinHeight(0);button.setMinimumHeight(0);button.setInsetTop(0);button.setInsetBottom(0);
            button.setIconSize(g.px(90));button.setIconPadding(0);button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
            button.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);button.setGravity(Gravity.CENTER);button.setLetterSpacing(0);
        }
        quality.setLineSpacing(-g.px(4),1f);
        quality.setTextSize(g.textSp(context,32));quality.setTypeface(android.os.Build.VERSION.SDK_INT>=28?android.graphics.Typeface.create(android.graphics.Typeface.create("sec-num-fixed",android.graphics.Typeface.NORMAL),600,false):android.graphics.Typeface.create("sec-num-fixed",android.graphics.Typeface.BOLD));quality.setIncludeFontPadding(false);
        android.widget.FrameLayout.LayoutParams menu=(android.widget.FrameLayout.LayoutParams)more.getLayoutParams();menu.width=menu.height=g.px(180);
        menu.gravity=landscape?Gravity.TOP|Gravity.END:Gravity.TOP|Gravity.END;menu.topMargin=landscape?g.px(54):g.px(82);
        menu.setMarginEnd(landscape?g.px(738):g.px(56));more.setLayoutParams(menu);more.setIconSize(g.px(90));
    }
    private void showMore() {
        LinearLayout panel=new LinearLayout(context);panel.setOrientation(LinearLayout.VERTICAL);panel.setGravity(Gravity.CENTER);
        GradientDrawable background=new GradientDrawable();background.setColor(0xE0101010);background.setCornerRadius(dp(28));background.setStroke(dp(1),0x30FFFFFF);panel.setBackground(background);
        MaterialButton close=button("×",false);close.setContentDescription(context.getString(R.string.personal_pro_close));close.setOnClickListener(v -> dismiss());panel.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));
        for(int id:new int[]{R.id.personal_grid_control,R.id.personal_stabilization_control,R.id.personal_mic_control,R.id.personal_quality_control,R.id.personal_torch_control,R.id.personal_home_settings}) {
            MaterialButton original=root.findViewById(id);MaterialButton entry=button(original.getText().toString(),false);
            android.graphics.drawable.Drawable image=original.getIcon();if(image!=null && image.getConstantState()!=null)entry.setIcon(image.getConstantState().newDrawable().mutate());
            entry.setIconTint(original.getIconTint());entry.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);entry.setIconPadding(0);entry.setIconSize(dp(24));entry.setContentDescription(original.getContentDescription());entry.setEnabled(original.isEnabled());
            entry.setOnClickListener(v -> {dismiss();original.performClick();});panel.addView(entry,new LinearLayout.LayoutParams(dp(48),dp(48)));
        }
        dismiss();popup=new PopupWindow(panel,dp(48),android.view.ViewGroup.LayoutParams.WRAP_CONTENT,true);popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        PersonalCameraLayout g=new PersonalCameraLayout(context);popup.showAtLocation(root,landscape?Gravity.TOP|Gravity.END:Gravity.TOP|Gravity.END,landscape?g.px(738):g.px(56),landscape?g.px(48):g.px(82));
    }

    private void showQuality() {
        if(!idle.getAsBoolean())return;
        closeAdjustment.run();Size current=prefs.getCameraResolution();int fps=prefs.getSpecificVideoFrameRate(prefs.getCameraSelection());
        LinearLayout panel=panel(context.getString(R.string.personal_video_size));
        LinearLayout sizes=row(context.getString(R.string.personal_size));
        for(Size size:new Size[]{new Size(7680,4320),new Size(3840,2160),new Size(1920,1080),new Size(1280,720)}) {
            MaterialButton b=button(sizeLabel(size),size.equals(current));boolean supported=supports(size,fps);b.setEnabled(supported);b.setAlpha(supported?1:.3f);
            if(!supported)b.setContentDescription(sizeLabel(size)+": "+context.getString(R.string.personal_unavailable_quality));
            b.setOnClickListener(v -> {if(!idle.getAsBoolean())return;prefs.sharedPreferences.edit().putInt(Constants.PREF_VIDEO_RESOLUTION_WIDTH,size.getWidth())
                    .putInt(Constants.PREF_VIDEO_RESOLUTION_HEIGHT,size.getHeight()).apply();dismiss();changed.run();});sizes.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));
        }
        panel.addView(sizes);
        LinearLayout rates=row("FPS");
        for(int rate:new int[]{120,60,30,24}) {
            MaterialButton b=button(Integer.toString(rate),rate==fps);boolean supported=supports(current,rate);b.setEnabled(supported);b.setAlpha(supported?1:.3f);
            b.setOnClickListener(v -> {if(!idle.getAsBoolean())return;prefs.setSpecificVideoFrameRate(prefs.getCameraSelection(),rate);dismiss();changed.run();});rates.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));
        }
        panel.addView(rates);show(panel);
    }
    private LinearLayout panel(String title) {
        LinearLayout panel=new LinearLayout(context);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(18),dp(12),dp(18),dp(18));
        GradientDrawable background=new GradientDrawable();background.setColor(0xF0212121);background.setCornerRadius(dp(28));panel.setBackground(background);
        LinearLayout heading=new LinearLayout(context);heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView text=new TextView(context);text.setText(title);text.setGravity(Gravity.CENTER_VERTICAL);text.setTextSize(20);text.setTextColor(Color.WHITE);heading.addView(text,new LinearLayout.LayoutParams(0,dp(56),1));
        MaterialButton close=button("×",false);close.setOnClickListener(v->dismiss());heading.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));panel.addView(heading);return panel;
    }
    private LinearLayout row(String name) {
        LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);TextView label=new TextView(context);label.setText(name);label.setGravity(Gravity.CENTER_VERTICAL);label.setTextColor(Color.WHITE);row.addView(label,new LinearLayout.LayoutParams(dp(48),dp(48)));return row;
    }
    private MaterialButton button(String title,boolean selected) {
        MaterialButton b=new MaterialButton(context);
        b.setText(title);b.setTextColor(selected?0xFFFFD400:Color.WHITE);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(0,0,0,0);b.setTextSize(15);b.setCornerRadius(dp(28));
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(selected?0xCC050505:Color.TRANSPARENT));return b;
    }
    private void show(LinearLayout panel) {
        dismiss();popup=new PopupWindow(panel,Math.min(dp(360),Math.max(dp(240),root.getWidth()-dp(24))),android.view.ViewGroup.LayoutParams.WRAP_CONTENT,true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));popup.setElevation(dp(8));boolean landscape=root.getWidth()>root.getHeight();popup.showAtLocation(root,landscape?Gravity.START|Gravity.CENTER_VERTICAL:Gravity.TOP|Gravity.CENTER_HORIZONTAL,landscape?dp(64):0,landscape?0:dp(64));
    }
    void dismiss() {if(popup!=null){popup.dismiss();popup=null;}}
    private int dp(int v){return Math.round(v*context.getResources().getDisplayMetrics().density);}
}
