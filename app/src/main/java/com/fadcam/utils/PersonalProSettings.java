package com.fadcam.utils;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.util.Range;
import com.fadcam.CameraType;
import com.fadcam.SharedPreferencesManager;

/** Per-camera pro values applied to the existing FadCam capture request. */
public final class PersonalProSettings {
    public static final String ACTION_APPLY = "com.fadcam.ACTION_APPLY_PERSONAL_PRO_SETTINGS";
    private static final String PRO_ENABLED = "personal_camera_pro_enabled";
    /** Ordinary Photo/Video use automatic capture without erasing the Pro preset. */
    public static boolean isEnabled(SharedPreferencesManager p) { return p.sharedPreferences.getBoolean(PRO_ENABLED,true); }
    public static void setEnabled(SharedPreferencesManager p,boolean enabled) { p.sharedPreferences.edit().putBoolean(PRO_ENABLED,enabled).apply(); }
    private PersonalProSettings() { }
    private static String key(CameraType camera, String value) {
        return "personal_pro_" + (camera == CameraType.FRONT ? "front_" : "back_") + value;
    }
    public static int iso(SharedPreferencesManager p, CameraType c) { return p.sharedPreferences.getInt(key(c,"iso"),0); }
    public static long exposure(SharedPreferencesManager p, CameraType c) { return p.sharedPreferences.getLong(key(c,"exposure"),0); }
    private static String focusKey(SharedPreferencesManager p, CameraType c) {
        if(c!=CameraType.BACK)return key(c,"focus");
        String lens=p.getSelectedBackCameraId();
        // Preserve the imported single-rear-lens preference on the first lens
        // that uses it. Other lenses start in AF instead of borrowing its D value.
        String migrated=key(c,"focus_migrated_lens");
        if(!p.sharedPreferences.contains(migrated)) {
            android.content.SharedPreferences.Editor editor=p.sharedPreferences.edit().putString(migrated,lens);
            if(p.sharedPreferences.contains(key(c,"focus")))editor.putFloat(key(c,"focus_lens_")+lens,p.sharedPreferences.getFloat(key(c,"focus"),-1f));
            editor.apply();
        }
        return key(c,"focus_lens_")+lens;
    }
    public static float focus(SharedPreferencesManager p, CameraType c) {
        float value=p.sharedPreferences.getFloat(focusKey(p,c),-1f);
        return Float.isFinite(value) && value>=0?value:-1f;
    }
    public static int wb(SharedPreferencesManager p, CameraType c) { return p.sharedPreferences.getInt(key(c,"wb"),CaptureRequest.CONTROL_AWB_MODE_AUTO); }
    public static boolean manualExposure(SharedPreferencesManager p, CameraType c) { return iso(p,c)>0 && exposure(p,c)>0; }
    public static void saveExposure(SharedPreferencesManager p, CameraType c, int iso, long exposure) {
        p.sharedPreferences.edit().putInt(key(c,"iso"),iso).putLong(key(c,"exposure"),exposure).apply();
        if (iso>0 && exposure>0) p.setSavedAeLock(false);
    }
    public static void saveFocus(SharedPreferencesManager p, CameraType c, float focus) {
        float value=Float.isFinite(focus) && focus>=0?focus:-1f;
        p.sharedPreferences.edit().putFloat(focusKey(p,c),value).apply();
        if (value<0) p.setSavedAfMode(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
    }
    public static int kelvin(SharedPreferencesManager p, CameraType c) {return p.sharedPreferences.getInt(key(c,"kelvin"),0);}
    public static void saveKelvin(SharedPreferencesManager p,CameraType c,int kelvin) {
        int value=Math.max(2300,Math.min(10000,kelvin));
        p.sharedPreferences.edit().putInt(key(c,"kelvin"),value).putInt(key(c,"wb"),CaptureRequest.CONTROL_AWB_MODE_OFF).apply();
    }
    public static void saveWb(SharedPreferencesManager p, CameraType c, int mode) {
        p.sharedPreferences.edit().putInt(key(c,"wb"),mode).putInt(key(c,"kelvin"),0).apply();
    }
    public static boolean supportsKelvin(CameraCharacteristics c) {
        return c!=null && contains(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
                && contains(c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES),CaptureRequest.CONTROL_AWB_MODE_OFF);
    }
    public static boolean usesKelvin(SharedPreferencesManager p,CameraType camera,CameraCharacteristics c) {
        return isEnabled(p) && kelvin(p,camera)>0 && supportsKelvin(c);
    }
    /** Saved white balance survives a lens switch; only supported settings reach this lens. */
    public static int effectiveWb(SharedPreferencesManager p,CameraType camera,CameraCharacteristics c) {
        if(usesKelvin(p,camera,c))return CaptureRequest.CONTROL_AWB_MODE_OFF;
        int saved=isEnabled(p)?wb(p,camera):CaptureRequest.CONTROL_AWB_MODE_AUTO;
        return saved!=CaptureRequest.CONTROL_AWB_MODE_OFF && c!=null && contains(c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES),saved)
                ?saved:CaptureRequest.CONTROL_AWB_MODE_AUTO;
    }
    private static final class ColorDefaults {
        final Integer mode;final android.hardware.camera2.params.RggbChannelVector gains;
        final android.hardware.camera2.params.ColorSpaceTransform transform, calibrated;
        ColorDefaults(CaptureRequest.Builder b,PersonalCameraTelemetry.Snapshot actual) {
            mode=b.get(CaptureRequest.COLOR_CORRECTION_MODE);gains=b.get(CaptureRequest.COLOR_CORRECTION_GAINS);transform=b.get(CaptureRequest.COLOR_CORRECTION_TRANSFORM);
            calibrated=actual!=null && actual.autoWhiteBalance && actual.colorTransform!=null?actual.colorTransform:transform;
        }
    }
    private static final java.util.Map<CaptureRequest.Builder,ColorDefaults> colorDefaults=java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static boolean contains(int[] values, int value) {
        if (values!=null) for(int v:values) if(v==value)return true;
        return false;
    }
    public static boolean supportsExposure(CameraCharacteristics c, int fps) {
        if(c==null || fps>=120) return false;
        Range<Integer> iso=c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        Range<Long> exposure=c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        return contains(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
                && contains(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES),CaptureRequest.CONTROL_AE_MODE_OFF)
                && iso!=null && exposure!=null && exposure.getLower()<=1_000_000_000L/Math.max(1,fps);
    }
    public static boolean usesManualExposure(SharedPreferencesManager p, CameraCharacteristics c, int fps) {
        return isEnabled(p) && manualExposure(p,p.getCameraSelection()) && supportsExposure(c,fps);
    }
    public static boolean supportsFocus(CameraCharacteristics c) {
        if(c==null)return false;
        Float near=c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        return near!=null && Float.isFinite(near) && near>0 && contains(c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),CaptureRequest.CONTROL_AF_MODE_OFF);
    }
    public static float maximumFocusDiopters(CameraCharacteristics c) {
        return supportsFocus(c)?c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE):0f;
    }
    /** App scale: 0.00 is infinity (0D), 1.00 is this lens's nearest focus. */
    public static float focusDiopters(float normalized, CameraCharacteristics c) {
        return Math.max(0f,Math.min(1f,Float.isFinite(normalized)?normalized:0f))*maximumFocusDiopters(c);
    }
    public static float normalizedFocus(float diopters, CameraCharacteristics c) {
        float max=maximumFocusDiopters(c);
        return max>0 && Float.isFinite(diopters)?Math.max(0f,Math.min(1f,diopters/max)):0f;
    }
    public static boolean usesManualFocus(SharedPreferencesManager p, CameraType camera, CameraCharacteristics c) {
        return isEnabled(p) && supportsFocus(c) && focus(p,camera)>=0;
    }
    public static final int FOCUS_AREA_CENTER=0, FOCUS_AREA_MULTI=1;
    public static int focusArea(SharedPreferencesManager p,CameraType camera) {
        return p.sharedPreferences.getInt(focusKey(p,camera)+"_area",FOCUS_AREA_CENTER)==FOCUS_AREA_MULTI?FOCUS_AREA_MULTI:FOCUS_AREA_CENTER;
    }
    public static void saveFocusArea(SharedPreferencesManager p,CameraType camera,int area) {
        p.sharedPreferences.edit().putInt(focusKey(p,camera)+"_area",area==FOCUS_AREA_MULTI?FOCUS_AREA_MULTI:FOCUS_AREA_CENTER).apply();
    }
    public static boolean supportsFocusArea(CameraCharacteristics c) {
        if(c==null)return false;
        Integer count=c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
        android.graphics.Rect active=c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        return count!=null && count>0 && active!=null && !active.isEmpty()
                && automaticFocusMode(c,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)!=CaptureRequest.CONTROL_AF_MODE_OFF;
    }
    public static boolean supportsMultiFocusArea(CameraCharacteristics c) {
        return supportsFocusArea(c) && c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)>1;
    }
    public static int effectiveFocusArea(SharedPreferencesManager p,CameraType camera,CameraCharacteristics c) {
        return focusArea(p,camera)==FOCUS_AREA_MULTI && supportsMultiFocusArea(c)?FOCUS_AREA_MULTI:FOCUS_AREA_CENTER;
    }
    /** Public Camera2 area policy, not Samsung's proprietary subject selection algorithm. */
    public static void applyFocusArea(CaptureRequest.Builder b,CameraCharacteristics c,SharedPreferencesManager p) {
        if(b==null || !supportsFocusArea(c))return;
        if(usesManualFocus(p,p.getCameraSelection(),c)) { b.set(CaptureRequest.CONTROL_AF_REGIONS,null);return; }
        android.graphics.Rect bounds=new android.graphics.Rect(c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE));
        android.graphics.Rect crop=b.get(CaptureRequest.SCALER_CROP_REGION);
        if(crop!=null && !crop.isEmpty())bounds.intersect(crop);
        if(!isEnabled(p)) {
            b.set(CaptureRequest.CONTROL_AF_REGIONS,new android.hardware.camera2.params.MeteringRectangle[]{new android.hardware.camera2.params.MeteringRectangle(bounds,0)});return;
        }
        boolean multi=effectiveFocusArea(p,p.getCameraSelection(),c)==FOCUS_AREA_MULTI;
        if(!multi) {
            int w=Math.max(1,bounds.width()/5),h=Math.max(1,bounds.height()/5);
            android.graphics.Rect center=new android.graphics.Rect(bounds.centerX()-w/2,bounds.centerY()-h/2,bounds.centerX()-w/2+w,bounds.centerY()-h/2+h);
            b.set(CaptureRequest.CONTROL_AF_REGIONS,new android.hardware.camera2.params.MeteringRectangle[]{new android.hardware.camera2.params.MeteringRectangle(center,1000)});return;
        }
        int count=Math.min(9,c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF));
        int cols=(int)Math.ceil(Math.sqrt(count)),rows=(int)Math.ceil(count/(double)cols);
        java.util.ArrayList<android.hardware.camera2.params.MeteringRectangle> areas=new java.util.ArrayList<>();
        for(int i=0;i<count;i++) {
            int col=i%cols,row=i/cols;
            android.graphics.Rect area=new android.graphics.Rect(bounds.left+col*bounds.width()/cols,bounds.top+row*bounds.height()/rows,
                    bounds.left+(col+1)*bounds.width()/cols,bounds.top+(row+1)*bounds.height()/rows);
            if(!area.isEmpty())areas.add(new android.hardware.camera2.params.MeteringRectangle(area,1000));
        }
        b.set(CaptureRequest.CONTROL_AF_REGIONS,areas.toArray(new android.hardware.camera2.params.MeteringRectangle[0]));
    }
    /** Resolve automatic AF without accidentally treating an old OFF preference as MF. */
    public static int automaticFocusMode(CameraCharacteristics c, int preferred) {
        int[] modes=c!=null?c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES):null;
        if(preferred!=CaptureRequest.CONTROL_AF_MODE_OFF && contains(modes,preferred))return preferred;
        return contains(modes,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)?CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                :contains(modes,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)?CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                :contains(modes,CaptureRequest.CONTROL_AF_MODE_AUTO)?CaptureRequest.CONTROL_AF_MODE_AUTO:CaptureRequest.CONTROL_AF_MODE_OFF;
    }
    /** Returns whether the current request really uses manual exposure. */
    public static boolean apply(CaptureRequest.Builder b, CameraCharacteristics c, SharedPreferencesManager p, int fps) {
        if(b==null || c==null)return false;
        CameraType camera=p.getCameraSelection();
        boolean pro=isEnabled(p);
        boolean manual=pro && manualExposure(p,camera) && supportsExposure(c,fps);
        if(manual) {
            Range<Integer> range=c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            Range<Long> times=c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            int iso=Math.max(range.getLower(),Math.min(range.getUpper(),iso(p,camera)));
            long frame=1_000_000_000L/Math.max(1,fps);
            long exposure=Math.max(times.getLower(),Math.min(Math.min(times.getUpper(),frame),exposure(p,camera)));
            if(iso!=iso(p,camera) || exposure!=exposure(p,camera)) saveExposure(p,camera,iso,exposure);
            b.set(CaptureRequest.CONTROL_AE_LOCK,false);
            b.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_SENSITIVITY,iso);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME,exposure);
            b.set(CaptureRequest.SENSOR_FRAME_DURATION,frame);
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,0);
        } else {
            if(!pro)b.set(CaptureRequest.CONTROL_AE_LOCK,false);
            b.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.SENSOR_SENSITIVITY,null);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME,null);
            b.set(CaptureRequest.SENSOR_FRAME_DURATION,null);
        }
        float focus=focus(p,camera);
        if(pro && focus>=0 && supportsFocus(c)) {
            b.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            b.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF);
            float clamped=Math.min(c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),focus);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE,clamped);
        } else {
            b.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE,null);
            int mode=pro?p.getSavedAfMode():CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
            int[] modes=c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            // AF return must not leave a previous manual request in AF OFF when
            // the newly selected lens does not offer continuous-video AF.
            mode=automaticFocusMode(c,mode);
            if(contains(modes,mode))b.set(CaptureRequest.CONTROL_AF_MODE,mode);
        }
        applyFocusArea(b,c,p);
        int wb=effectiveWb(p,camera,c);
        if(usesKelvin(p,camera,c)) {
            ColorDefaults defaults=colorDefaults.get(b);
            if(defaults==null) {
                defaults=new ColorDefaults(b,PersonalCameraTelemetry.current(camera,camera==CameraType.FRONT?"front":p.getSelectedBackCameraId()));
                colorDefaults.put(b,defaults);
            }
            b.set(CaptureRequest.CONTROL_AWB_LOCK,false);b.set(CaptureRequest.CONTROL_AWB_MODE,CaptureRequest.CONTROL_AWB_MODE_OFF);
            b.set(CaptureRequest.COLOR_CORRECTION_MODE,CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS,PersonalWhiteBalance.gains(kelvin(p,camera)));
            b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM,defaults.calibrated!=null?defaults.calibrated
                    :new android.hardware.camera2.params.ColorSpaceTransform(new int[]{1,1,0,1,0,1,0,1,1,1,0,1,0,1,0,1,1,1}));
        } else {
            ColorDefaults defaults=colorDefaults.remove(b);
            if(defaults!=null) {
                b.set(CaptureRequest.COLOR_CORRECTION_MODE,defaults.mode);b.set(CaptureRequest.COLOR_CORRECTION_GAINS,defaults.gains);b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM,defaults.transform);
            }
            if(wb!=CaptureRequest.CONTROL_AWB_MODE_OFF && contains(c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES),wb)) {
                b.set(CaptureRequest.CONTROL_AWB_LOCK,false);b.set(CaptureRequest.CONTROL_AWB_MODE,wb);
            }
        }
        return manual;
    }
}
