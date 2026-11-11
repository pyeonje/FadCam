package com.fadcam.ui;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.os.Looper;
import android.util.Range;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;
import com.fadcam.CameraType;
import com.fadcam.R;
import com.fadcam.RecordingControlIntents;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.utils.PersonalProSettings;
import com.google.android.material.button.MaterialButton;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** One selected pro adjustment over the preview; no permanent side panel. */
final class PersonalProControls {
    private final Context context;
    private final SharedPreferencesManager prefs;
    private final BooleanSupplier ready;
    private final View panel, lensPicker, zoomLabel, zoomPicker, more;
    private final TextView value;
    private final SeekBar slider;
    private final MaterialButton auto;
    private final MaterialButton[] buttons = new MaterialButton[5];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable apply;
    private CameraCharacteristics capabilities;
    private CameraType camera;
    private String lens;
    private final List<Integer> wbModes = new ArrayList<>();
    private final List<Long> isoStops = new ArrayList<>(), shutterStops = new ArrayList<>();
    private int selected = -1;
    private boolean queued;
    private androidx.appcompat.widget.PopupMenu focusAreaMenu;

    PersonalProControls(Context context, View root, SharedPreferencesManager prefs, BooleanSupplier ready) {
        this.context=context; this.prefs=prefs; this.ready=ready;
        panel=root.findViewById(R.id.personal_pro_panel);more=root.findViewById(R.id.personal_camera_more);
        lensPicker=root.findViewById(R.id.personal_zoom_presets_container);zoomLabel=root.findViewById(R.id.personal_zoom_value);zoomPicker=root.findViewById(R.id.personal_zoom_slider);
        value=root.findViewById(R.id.personal_pro_selected_value);
        slider=root.findViewById(R.id.personal_pro_slider);
        auto=root.findViewById(R.id.personal_pro_auto);
        apply=() -> { queued=false; if(ready.getAsBoolean()) context.startService(RecordingControlIntents.applyProSettings(context)); };
        value.setOnClickListener(v -> showFocusAreaMenu());
        int[] ids={R.id.personal_pro_iso,R.id.personal_pro_speed,R.id.personal_pro_ev,R.id.personal_pro_focus,R.id.personal_pro_wb};
        for(int i=0;i<5;i++) {
            buttons[i]=root.findViewById(ids[i]);
            final int item=i;
            buttons[i].setOnClickListener(v -> {
                if(!ready.getAsBoolean())return;
                if(zoomPicker.getVisibility()==View.VISIBLE)zoomLabel.performClick();
                if(selected==item && panel.getVisibility()==View.VISIBLE) hide();
                else { ((PersonalCameraRuler)slider).cancelGesture();dismissFocusAreaMenu();selected=item; panel.setVisibility(View.VISIBLE); more.setVisibility(View.INVISIBLE);lensPicker.setVisibility(View.INVISIBLE);zoomLabel.setVisibility(View.INVISIBLE);zoomPicker.setVisibility(View.GONE);PersonalCameraControls.updateZoomValueVisibility(root);bindSlider(); updateLabels(); }
            });
        }
        root.findViewById(R.id.personal_pro_reset).setOnClickListener(v -> {
            if(!ready.getAsBoolean())return;
            PersonalProSettings.saveExposure(prefs,camera,0,0);PersonalProSettings.saveFocus(prefs,camera,-1);PersonalProSettings.saveFocusArea(prefs,camera,PersonalProSettings.FOCUS_AREA_CENTER);
            PersonalProSettings.saveWb(prefs,camera,CaptureRequest.CONTROL_AWB_MODE_AUTO);
            prefs.setSavedExposureCompensation(0);prefs.setSavedAeLock(false);flush();hide();updateEnabled();
        });
        root.findViewById(R.id.personal_pro_close).setOnClickListener(v -> hide());
        auto.setOnClickListener(v -> {
            if(!ready.getAsBoolean())return;
            if(selected==0 || selected==1) PersonalProSettings.saveExposure(prefs,camera,0,0);
            else if(selected==2) { prefs.setSavedExposureCompensation(0); context.startService(RecordingControlIntents.setExposureCompensation(context,0)); }
            else if(selected==3) {
                if(PersonalProSettings.usesManualFocus(prefs,camera,capabilities)) PersonalProSettings.saveFocus(prefs,camera,-1f);
                else {
                    com.fadcam.utils.PersonalCameraTelemetry.Snapshot snapshot=snapshot();
                    // A missing first result must not make the MF button inert.
                    // Use the displayed ruler value until real AF telemetry exists.
                    float distance=snapshot!=null && snapshot.focus!=null?snapshot.focus:
                            PersonalProSettings.focusDiopters(slider.getProgress()/100f,capabilities);
                    PersonalProSettings.saveFocus(prefs,camera,PersonalProSettings.focusDiopters(PersonalProSettings.normalizedFocus(distance,capabilities),capabilities));
                }
            }
            else if(selected==4) PersonalProSettings.saveWb(prefs,camera,CaptureRequest.CONTROL_AWB_MODE_AUTO);
            flush(); updateLabels(); updateEnabled(); bindSlider();
        });
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean user) {
                if(user && selected>=0 && panel.getVisibility()==View.VISIBLE && ready.getAsBoolean()) change(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {
                // Enter MF at the first accepted ruler touch, including an endpoint
                // drag that cannot increment progress. Never leave an AF label floating.
                if(selected==3 && ready.getAsBoolean() && PersonalProSettings.supportsFocus(capabilities))change(bar.getProgress());
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {if(selected>=0 && ready.getAsBoolean())flush();}
        });
    }
    void refresh(CameraCharacteristics characteristics) {
        CameraType next=prefs.getCameraSelection();
        String nextLens=next==CameraType.FRONT?"front":prefs.getSelectedBackCameraId();
        if(next!=camera || !java.util.Objects.equals(nextLens,lens)) {
            // A delayed old-lens adjustment must not be delivered to its successor.
            handler.removeCallbacks(apply);queued=false;hide();
        }
        camera=next;lens=nextLens;capabilities=characteristics;
        wbModes.clear();
        if(capabilities!=null) {
            int[] modes=capabilities.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
            if(modes!=null) for(int mode:modes) if(mode!=CaptureRequest.CONTROL_AWB_MODE_OFF) wbModes.add(mode);
        }
        buildStops();updateLabels(); updateEnabled();
        if(panel.getVisibility()==View.VISIBLE) bindSlider();
    }
    void updateEnabled() {
        boolean active=ready.getAsBoolean();
        boolean exposure=PersonalProSettings.supportsExposure(capabilities,fps());
        buttons[0].setEnabled(active && exposure); buttons[1].setEnabled(active && exposure);
        Range<Integer> ev=capabilities!=null ? capabilities.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE):null;
        buttons[2].setEnabled(active && ev!=null && ev.getLower()<ev.getUpper()
                && !PersonalProSettings.usesManualExposure(prefs,capabilities,fps()) && !prefs.isAeLockedSaved());
        buttons[3].setEnabled(active && (PersonalProSettings.supportsFocus(capabilities) || PersonalProSettings.supportsFocusArea(capabilities)));
        slider.setEnabled(active && (selected!=3 || PersonalProSettings.supportsFocus(capabilities)));
        buttons[4].setEnabled(active && (PersonalProSettings.supportsKelvin(capabilities) || wbModes.size()>1));
        if(!active || (selected>=0 && !buttons[selected].isEnabled())) hide();
    }
    private void bindSlider() {
        if(selected<0 || capabilities==null)return;
        slider.setEnabled(ready.getAsBoolean() && (selected!=3 || PersonalProSettings.supportsFocus(capabilities)));
        com.fadcam.utils.PersonalCameraTelemetry.Snapshot actual=snapshot();
        slider.setMax(selected==0 ? Math.max(0,isoStops.size()-1) : selected==1 ? Math.max(0,shutterStops.size()-1)
                : selected==2 ? evRange().getUpper()-evRange().getLower() : selected==4 ? (PersonalProSettings.supportsKelvin(capabilities)?77:Math.max(0,wbModes.size()-1)) : 100);
        ((PersonalCameraRuler)slider).setFocusScale(selected==3);
        int progress=0;
        if(selected==0) {
            Range<Integer> r=capabilities.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            int saved=PersonalProSettings.iso(prefs,camera);
            progress=nearest(isoStops,Math.max(r.getLower(),saved>0?saved:actual!=null&&actual.iso!=null?actual.iso:r.getLower()));
        } else if(selected==1) {
            Range<Long> r=capabilities.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            long saved=PersonalProSettings.exposure(prefs,camera);
            progress=nearest(shutterStops,Math.max(r.getLower(),saved>0?saved:actual!=null&&actual.exposure!=null?actual.exposure:r.getLower()));
        } else if(selected==2)progress=prefs.getSavedExposureCompensation()-evRange().getLower();
        else if(selected==3) {
            float saved=PersonalProSettings.focus(prefs,camera);
            progress=Math.round(PersonalProSettings.normalizedFocus(saved>=0?saved:actual!=null&&actual.focus!=null?actual.focus:0,capabilities)*100);
        }
        else if(selected==4)progress=PersonalProSettings.supportsKelvin(capabilities)?(Math.max(2300,PersonalProSettings.kelvin(prefs,camera)>0?PersonalProSettings.kelvin(prefs,camera):5000)-2300)/100:Math.max(0,wbModes.indexOf(PersonalProSettings.wb(prefs,camera)));
        slider.setProgress(progress);
        auto.setText(selected==3 ? (PersonalProSettings.usesManualFocus(prefs,camera,capabilities)?"MF":"AF") : context.getString(selected==2 ? R.string.personal_pro_reset : R.string.personal_pro_auto));
        updateSelectedValue();
    }
    private void change(int progress) {
        if(selected==3 && !PersonalProSettings.supportsFocus(capabilities))return;
        if(selected==0 || selected==1) {
            Range<Integer> isoRange=capabilities.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            Range<Long> timeRange=capabilities.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            int iso=PersonalProSettings.iso(prefs,camera);
            long exposure=PersonalProSettings.exposure(prefs,camera);
            com.fadcam.utils.PersonalCameraTelemetry.Snapshot actual=snapshot();
            if(iso<=0)iso=Math.max(isoRange.getLower(),Math.min(isoRange.getUpper(),actual!=null&&actual.iso!=null?actual.iso:100));
            if(exposure<=0)exposure=Math.max(timeRange.getLower(),Math.min(maxExposure(),actual!=null&&actual.exposure!=null?actual.exposure:1_000_000_000L/(2*Math.max(1,fps()))));
            if(selected==0)iso=isoStops.get(progress).intValue();
            else exposure=shutterStops.get(progress);
            PersonalProSettings.saveExposure(prefs,camera,iso,exposure);
        } else if(selected==2) {
            int ev=progress+evRange().getLower();prefs.setSavedExposureCompensation(ev);
            context.startService(RecordingControlIntents.setExposureCompensation(context,ev));
        } else if(selected==3) PersonalProSettings.saveFocus(prefs,camera,PersonalProSettings.focusDiopters(progress/100f,capabilities));
        else if(selected==4) {if(PersonalProSettings.supportsKelvin(capabilities))PersonalProSettings.saveKelvin(prefs,camera,2300+progress*100);else PersonalProSettings.saveWb(prefs,camera,wbModes.get(progress));}
        updateLabels(); updateSelectedValue(); updateEnabled();
        if(selected!=2 && !queued) { queued=true;handler.postDelayed(apply,50); }
    }
    private com.fadcam.utils.PersonalCameraTelemetry.Snapshot snapshot() {
        return com.fadcam.utils.PersonalCameraTelemetry.current(camera,camera==CameraType.FRONT?"front":prefs.getSelectedBackCameraId());
    }
    void refreshLiveValues() {updateLabels();if(panel.getVisibility()==View.VISIBLE)updateSelectedValue();}
    private void updateLabels() {
        boolean manual=PersonalProSettings.usesManualExposure(prefs,capabilities,fps());
        com.fadcam.utils.PersonalCameraTelemetry.Snapshot snapshot=snapshot();
        String iso=manual?Integer.toString(PersonalProSettings.iso(prefs,camera)):snapshot!=null&&snapshot.iso!=null?snapshot.iso+" ᴬ":"A";
        String speed=manual?shutter(PersonalProSettings.exposure(prefs,camera)):snapshot!=null&&snapshot.exposure!=null?shutter(snapshot.exposure)+" ᴬ":"S A";
        String[] labels={"ISO "+iso,speed,String.format(Locale.getDefault(),"%+.1f",prefs.getSavedExposureCompensation()*evStep()),
                PersonalProSettings.usesManualFocus(prefs,camera,capabilities)?"MF":"AF","WB "+(PersonalProSettings.usesKelvin(prefs,camera,capabilities)?PersonalProSettings.kelvin(prefs,camera)+"K":PersonalProSettings.effectiveWb(prefs,camera,capabilities)==CaptureRequest.CONTROL_AWB_MODE_AUTO?"A":wbLabel(PersonalProSettings.effectiveWb(prefs,camera,capabilities)))};
        for(int i=0;i<5;i++) {
            boolean active=i==selected && panel.getVisibility()==View.VISIBLE;
            if(!labels[i].contentEquals(buttons[i].getText()) || !(buttons[i].getText() instanceof android.text.Spanned)) {
                android.text.SpannableString label=new android.text.SpannableString(labels[i]);
                label.setSpan(new android.text.style.TypefaceSpan("sec"),0,label.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                label.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.NORMAL),0,label.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                int start=i==0?4:i==4?3:0;
                if(start<label.length())label.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),start,label.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                buttons[i].setText(label);
            }
            buttons[i].setCornerRadius(dp(15));
            buttons[i].setBackgroundTintList(android.content.res.ColorStateList.valueOf(active?0xFFFFD400:android.graphics.Color.TRANSPARENT));
            buttons[i].setTextColor(active?0xFF111111:0xFFFFFFFF);
            String description=context.getString(new int[]{R.string.personal_pro_iso_label,R.string.personal_pro_speed_label,
                    R.string.personal_pro_ev_label,R.string.personal_pro_focus_label,R.string.personal_pro_wb_label}[i])+": "+labels[i];
            if(!description.contentEquals(buttons[i].getContentDescription()==null?"":buttons[i].getContentDescription()))buttons[i].setContentDescription(description);
        }
    }
    private void updateSelectedValue() {
        if(selected<0)return;
        com.fadcam.utils.PersonalCameraTelemetry.Snapshot actual=snapshot();
        String text=selected==0?(PersonalProSettings.usesManualExposure(prefs,capabilities,fps())?Integer.toString(PersonalProSettings.iso(prefs,camera)):actual!=null&&actual.iso!=null?Integer.toString(actual.iso):"A")
                : selected==1?(PersonalProSettings.usesManualExposure(prefs,capabilities,fps())?shutter(PersonalProSettings.exposure(prefs,camera)):actual!=null&&actual.exposure!=null?shutter(actual.exposure):"A")
                : selected==3?(PersonalProSettings.usesManualFocus(prefs,camera,capabilities)?String.format(Locale.US,"%.2f",PersonalProSettings.normalizedFocus(PersonalProSettings.focus(prefs,camera),capabilities)):focusAreaLabel())
                : buttons[selected].getText().toString();
        if(!text.contentEquals(value.getText()))value.setText(text);
        if(selected==3)auto.setText(PersonalProSettings.usesManualFocus(prefs,camera,capabilities)?"MF":"AF");
        value.setClickable(selected==3 && !PersonalProSettings.usesManualFocus(prefs,camera,capabilities) && PersonalProSettings.supportsFocusArea(capabilities));
        auto.setEnabled(selected!=3 || PersonalProSettings.supportsFocus(capabilities));
        CharSequence description=buttons[selected].getContentDescription();
        if(description!=null && !description.equals(slider.getContentDescription()))slider.setContentDescription(description);
    }
    private String focusAreaLabel() {
        if(!PersonalProSettings.supportsFocusArea(capabilities))return "AF";
        return context.getString(PersonalProSettings.effectiveFocusArea(prefs,camera,capabilities)==PersonalProSettings.FOCUS_AREA_MULTI?R.string.personal_focus_multi:R.string.personal_focus_center);
    }
    private void showFocusAreaMenu() {
        if(selected!=3 || !ready.getAsBoolean() || PersonalProSettings.usesManualFocus(prefs,camera,capabilities)
                || !PersonalProSettings.supportsFocusArea(capabilities))return;
        dismissFocusAreaMenu();
        focusAreaMenu=new androidx.appcompat.widget.PopupMenu(context,value);
        focusAreaMenu.getMenu().add(0,PersonalProSettings.FOCUS_AREA_CENTER,0,R.string.personal_focus_center);
        focusAreaMenu.getMenu().add(0,PersonalProSettings.FOCUS_AREA_MULTI,1,R.string.personal_focus_multi).setEnabled(PersonalProSettings.supportsMultiFocusArea(capabilities));
        focusAreaMenu.setOnMenuItemClickListener(item -> {
            if(selected!=3 || !ready.getAsBoolean() || !PersonalProSettings.supportsFocusArea(capabilities))return false;
            if(item.getItemId()==PersonalProSettings.FOCUS_AREA_MULTI && !PersonalProSettings.supportsMultiFocusArea(capabilities))return false;
            PersonalProSettings.saveFocusArea(prefs,camera,item.getItemId());flush();updateSelectedValue();return true;
        });
        focusAreaMenu.show();
    }
    private void dismissFocusAreaMenu() {if(focusAreaMenu!=null){focusAreaMenu.dismiss();focusAreaMenu=null;}}
    private void buildStops() {
        isoStops.clear();shutterStops.clear();
        if(capabilities==null)return;
        Range<Integer> iso=capabilities.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        Range<Long> exposure=capabilities.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if(iso!=null) {
            for(long stop:new long[]{50,64,80,100,125,160,200,250,320,400,500,640,800,1000,1250,1600,2000,2500,3200,4000,5000,6400,8000,10000,12800,16000,25600})
                if(stop>=iso.getLower() && stop<=iso.getUpper())isoStops.add(stop);
            if(isoStops.isEmpty())isoStops.add(iso.getLower().longValue());
        }
        if(exposure!=null) {
            for(int denominator:new int[]{12000,10000,8000,6400,5000,4000,3200,2500,2000,1600,1250,1000,800,640,500,400,320,250,200,160,125,100,80,60,50,40,30,25,24}) {
                long ns=1_000_000_000L/denominator;if(ns>=exposure.getLower() && ns<=maxExposure())shutterStops.add(ns);
            }
            if(shutterStops.isEmpty())shutterStops.add(exposure.getLower());
        }
    }
    private int nearest(List<Long> stops,long value) {
        int best=0;for(int i=1;i<stops.size();i++)if(Math.abs(stops.get(i)-value)<Math.abs(stops.get(best)-value))best=i;return best;
    }
    void reflow(boolean landscape) {
        ((PersonalCameraRuler)slider).cancelGesture();dismissFocusAreaMenu();
        PersonalCameraLayout g=new PersonalCameraLayout(context);
        for(MaterialButton button:buttons) {button.getLayoutParams().height=g.px(128);button.setTextSize(g.textSp(context,43.875f));}
        View reset=((View)panel.getParent()).findViewById(R.id.personal_pro_reset);reset.getLayoutParams().width=reset.getLayoutParams().height=g.px(128);
        android.widget.FrameLayout.LayoutParams outer=(android.widget.FrameLayout.LayoutParams)panel.getLayoutParams();
        outer.width=landscape?g.px(222):android.view.ViewGroup.LayoutParams.MATCH_PARENT;outer.height=landscape?android.view.ViewGroup.LayoutParams.MATCH_PARENT:g.px(292);
        outer.gravity=landscape?android.view.Gravity.END:android.view.Gravity.BOTTOM;
        outer.setMargins(landscape?0:g.px(48),landscape?g.px(48):0,0,landscape?g.px(48):g.px(1162));outer.setMarginEnd(landscape?g.px(698):g.px(48));panel.setLayoutParams(outer);
        android.widget.FrameLayout.LayoutParams ruler=(android.widget.FrameLayout.LayoutParams)slider.getLayoutParams();
        ruler.width=android.view.ViewGroup.LayoutParams.MATCH_PARENT;ruler.height=landscape?android.view.ViewGroup.LayoutParams.MATCH_PARENT:g.px(195);
        ruler.gravity=landscape?android.view.Gravity.END:android.view.Gravity.BOTTOM;ruler.setMargins(landscape?0:g.px(75),landscape?g.px(195):0,0,landscape?g.px(195):0);ruler.setMarginStart(landscape?0:g.px(75));ruler.setMarginEnd(0);slider.setLayoutParams(ruler);((PersonalCameraRuler)slider).setVertical(landscape);
        android.widget.FrameLayout.LayoutParams text=(android.widget.FrameLayout.LayoutParams)value.getLayoutParams();text.width=g.px(600);text.height=g.px(105);text.gravity=landscape?android.view.Gravity.CENTER:android.view.Gravity.TOP|android.view.Gravity.CENTER_HORIZONTAL;value.setLayoutParams(text);value.setRotation(landscape?270:0);value.setTranslationX(landscape?-g.px(46):0);
        auto.setTypeface(android.graphics.Typeface.create("sec",android.graphics.Typeface.BOLD));auto.setIncludeFontPadding(false);
        android.widget.FrameLayout.LayoutParams automatic=(android.widget.FrameLayout.LayoutParams)auto.getLayoutParams();automatic.width=g.px(180);automatic.height=g.px(144);automatic.gravity=landscape?android.view.Gravity.BOTTOM|android.view.Gravity.CENTER_HORIZONTAL:android.view.Gravity.TOP|android.view.Gravity.START;automatic.bottomMargin=landscape?g.px(56):0;auto.setLayoutParams(automatic);
        MaterialButton close=((View)panel.getParent()).findViewById(R.id.personal_pro_close);close.setTypeface(android.graphics.Typeface.create("sec",android.graphics.Typeface.NORMAL));close.setIncludeFontPadding(false);close.setTextSize(g.textSp(context,52.5f));
        android.widget.FrameLayout.LayoutParams closePosition=(android.widget.FrameLayout.LayoutParams)close.getLayoutParams();closePosition.width=closePosition.height=g.px(180);closePosition.gravity=landscape?android.view.Gravity.TOP|android.view.Gravity.CENTER_HORIZONTAL:android.view.Gravity.TOP|android.view.Gravity.END;close.setLayoutParams(closePosition);
    }
    private int dp(int v){return Math.round(v*context.getResources().getDisplayMetrics().density);}
    private String wbLabel(int mode) {
        int id=mode==CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT ? R.string.personal_wb_incandescent
                : mode==CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT || mode==CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT ? R.string.personal_wb_fluorescent
                : mode==CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT ? R.string.personal_wb_daylight
                : mode==CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT ? R.string.personal_wb_cloudy
                : mode==CaptureRequest.CONTROL_AWB_MODE_SHADE ? R.string.personal_wb_shade
                : mode==CaptureRequest.CONTROL_AWB_MODE_TWILIGHT ? R.string.personal_wb_twilight : R.string.personal_pro_auto;
        return context.getString(id);
    }
    private int fps(){return prefs.getSpecificVideoFrameRate(camera);}
    private Range<Integer> evRange(){return capabilities.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);}
    private float evStep(){ android.util.Rational r=capabilities!=null?capabilities.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP):null;return r!=null?r.floatValue():prefs.getExposureCompensationStep();}
    private long maxExposure(){return Math.min(capabilities.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE).getUpper(),1_000_000_000L/Math.max(1,fps()));}
    private int logProgress(double v,double min,double max){return max>min?Math.round((float)(Math.log(v/min)/Math.log(max/min))*1000):0;}
    private long logValue(int progress,double min,double max){return Math.round(min*Math.pow(max/min,progress/1000.0));}
    private String shutter(long ns){return ns>0?"1/"+Math.max(1,Math.round(1_000_000_000.0/ns)):context.getString(R.string.personal_pro_auto);}
    private void flush(){handler.removeCallbacks(apply);apply.run();}
    void hide(){
        dismissFocusAreaMenu();((PersonalCameraRuler)slider).cancelGesture();
        if(queued) flush(); else handler.removeCallbacks(apply);
        queued=false;panel.setVisibility(View.GONE);more.setVisibility(View.VISIBLE);selected=-1;
        if(zoomPicker.getVisibility()!=View.VISIBLE)lensPicker.setVisibility(View.VISIBLE);PersonalCameraControls.updateZoomValueVisibility((View)panel.getParent());updateLabels();
    }
    void release(){hide();}
}
