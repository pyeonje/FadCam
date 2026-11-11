package com.fadcam.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.util.Range;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.fadcam.CameraType;
import com.fadcam.FLog;
import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.RecordingControlIntents;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.services.RecordingService;
import com.fadcam.utils.CameraLensCatalog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** Personal UI adapter for upstream FadCam's existing Camera2 runtime controls.
 * Zoom changes the capture request, never a second TextureView-only scale.
 */
final class PersonalCameraControls {
    private final Context context;
    private final SharedPreferencesManager prefs;
    private final BooleanSupplier sessionReady, lensSwitchReady;
    private final Runnable lensChanged;
    private final TextureView preview;
    private final View focusRing;
    private final LinearLayout presets, zoomAnchors;
    private final View root;
    private final MaterialButton zoomValue, adjust;
    private final SeekBar zoomSlider;
    private final PersonalProControls pro;
    private final PersonalQuickControls quick;
    private boolean landscape;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ScaleGestureDetector scaleDetector;
    private final List<Integer> afModes = new ArrayList<>();
    private CameraType camera;
    private CameraCharacteristics cameraCharacteristics;
    private float minZoom = 1f, maxZoom = 1f, zoom = 1f, baseRatio = 1f;
    private String selectedLens;
    private final List<CameraLensCatalog.Lens> lenses = new ArrayList<>();
    private int minEv, maxEv;
    private float evStep;
    private boolean aeSupported, focusSupported, multiTouch, moved, zoomQueued;
    private float downX, downY;
    private long downTime;
    private AlertDialog dialog, focusDialog;
    private final Runnable hideFocus;
    private final Runnable sendZoom;

    @SuppressLint("ClickableViewAccessibility")
    PersonalCameraControls(Context context, View root, SharedPreferencesManager prefs,
            BooleanSupplier sessionReady, BooleanSupplier lensSwitchReady, Runnable lensChanged) {
        this.context = new androidx.appcompat.view.ContextThemeWrapper(context,
                R.style.ThemeOverlay_PersonalSamsungTypography);
        this.root = root;
        this.prefs = prefs;
        this.sessionReady = sessionReady;
        this.lensSwitchReady = lensSwitchReady; this.lensChanged = lensChanged;
        preview = root.findViewById(R.id.personal_home_preview);
        focusRing = root.findViewById(R.id.personal_focus_ring);
        hideFocus = () -> focusRing.setVisibility(View.GONE);
        sendZoom = () -> {
            zoomQueued = false;
            dispatch(RecordingControlIntents.setZoomRatio(context, zoom, 0f, 0f));
        };
        presets = root.findViewById(R.id.personal_zoom_presets);
        zoomAnchors = root.findViewById(R.id.personal_zoom_anchors);
        zoomValue = root.findViewById(R.id.personal_zoom_value);
        zoomSlider = root.findViewById(R.id.personal_zoom_slider);
        ((PersonalCameraRuler)zoomSlider).setZoomScale(true);
        adjust = root.findViewById(R.id.personal_camera_adjust);
        pro = new PersonalProControls(this.context, root, prefs, sessionReady);
        quick = new PersonalQuickControls(this.context, root, prefs, sessionReady, lensSwitchReady, lensChanged, pro::hide);
        landscape = context.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector detector) {
                        if (sessionReady.getAsBoolean()) setZoom(zoom * detector.getScaleFactor(), false);
                        return true;
                    }
                    @Override public void onScaleEnd(ScaleGestureDetector detector) {
                        // The pinch ends when either finger lifts, even if the other
                        // finger stays on the view. Commit only a changed final ratio.
                        if(zoomQueued)flushZoom();
                    }
                });
        scaleDetector.setQuickScaleEnabled(false);
        preview.setOnTouchListener(this::onTouch);
        zoomValue.setOnClickListener(v -> {
            boolean show = zoomSlider.getVisibility() != View.VISIBLE;
            if (show) pro.hide();
            zoomSlider.setVisibility(show ? View.VISIBLE : View.GONE);
            root.findViewById(R.id.personal_zoom_presets_container).setVisibility(show ? View.GONE : View.VISIBLE);
            updateZoomValueVisibility(root);layoutZoomControls();
        });
        root.findViewById(R.id.personal_zoom_close).setOnClickListener(v -> {
            if(zoomQueued)flushZoom();zoomSlider.setVisibility(View.GONE);
            updateZoomValueVisibility(root);layoutZoomControls();
        });
        zoomSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && sessionReady.getAsBoolean()) {
                    setZoom((float) (minZoom * Math.pow(maxZoom / minZoom, progress / 1000.0)), false);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { flushZoom(); }
        });
        adjust.setOnClickListener(v -> showAdjustments());
        root.findViewById(R.id.personal_pro_focus).setOnLongClickListener(v->{showAdjustments();return true;});
        root.findViewById(R.id.personal_pro_ev).setOnLongClickListener(v->{showAdjustments();return true;});
        adjust.setVisibility(View.GONE);
        refresh();
    }

    void setRecordingUi(boolean recording) {quick.setRecordingUi(recording);}

    void refresh() {
        CameraType selected = prefs.getCameraSelection();
        if (selected != camera) cancelGesture(false);
        camera = selected;
        minZoom = 1f;
        baseRatio = 1f; lenses.clear(); selectedLens = prefs.getSelectedBackCameraId();
        maxZoom = 1f;
        minEv = maxEv = 0;
        evStep = prefs.getExposureCompensationStep();
        aeSupported = focusSupported = false;
        afModes.clear();
        CameraCharacteristics proCapabilities = null;
        // Dual capture has no upstream runtime-control handlers. Never route it to
        // a second single-camera service or pretend these controls are supported.
        if (camera == null || !camera.isDual()) {
            try {
                CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                String id = prefs.resolveSelectedCameraId(manager,camera);
                if(camera!=CameraType.FRONT && id!=null)selectedLens=id;
                if (camera != CameraType.FRONT) {
                    lenses.addAll(CameraLensCatalog.rear(manager));
                    baseRatio = CameraLensCatalog.baseRatio(manager, id);
                }
                if (id != null) {
                    CameraCharacteristics c = manager.getCameraCharacteristics(id);
                    proCapabilities = c;
                    Range<Float> range = Build.VERSION.SDK_INT >= 30
                            ? c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) : null;
                    if (range != null) {
                        minZoom = range.getLower(); maxZoom = range.getUpper();
                    } else {
                        Float digital = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
                        maxZoom = digital != null ? Math.max(1f, digital) : 1f;
                    }
                    Range<Integer> ev = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
                    if (ev != null) { minEv = ev.getLower(); maxEv = ev.getUpper(); }
                    android.util.Rational step = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
                    if (step != null) evStep = step.floatValue();
                    aeSupported = Boolean.TRUE.equals(c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE));
                    int[] modes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                    if (modes != null) for (int mode : modes) {
                        if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                                || mode == CaptureRequest.CONTROL_AF_MODE_AUTO) {
                            afModes.add(mode);
                        }
                    }
                    Integer regions = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
                    focusSupported = afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)
                            && regions != null && regions > 0;
                }
            } catch (Exception e) {
                FLog.w("PersonalControls", "Camera capabilities unavailable", e);
            }
        }
        zoom = clamp(prefs.getSpecificZoomRatio(camera), minZoom, maxZoom);
        presets.removeAllViews();
        if (landscape) java.util.Collections.reverse(lenses);
        presets.setOrientation(landscape ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        for (CameraLensCatalog.Lens lens : lenses) {
            androidx.appcompat.widget.AppCompatTextView button = new androidx.appcompat.widget.AppCompatTextView(context);
            button.setGravity(android.view.Gravity.CENTER);
            button.setTextSize(new PersonalCameraLayout(context).textSp(context,48f));
            button.setTypeface(android.graphics.Typeface.create("sec",android.graphics.Typeface.BOLD));
            button.setIncludeFontPadding(false);
            // The common rail owns its single moving highlight. Unselected lenses
            // have no independent circular background, matching the stock capture.
            button.setBackground(null);
            button.setClickable(true); button.setFocusable(true);
            androidx.core.view.ViewCompat.setAccessibilityDelegate(button, new androidx.core.view.AccessibilityDelegateCompat() {
                @Override public void onInitializeAccessibilityNodeInfo(View host,
                        androidx.core.view.accessibility.AccessibilityNodeInfoCompat info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(android.widget.Button.class.getName());
                }
            });
            button.setText(lens.label);
            button.setContentDescription(context.getString(R.string.personal_lens_choice, lens.label, ratioText(lens.baseRatio)));
            button.setPadding(0, 0, 0, 0);
            button.setTextColor(android.graphics.Color.WHITE);
            button.setMinimumWidth(0);
            button.setTag(lens);
            button.setOnClickListener(v -> selectLens(lens));
            presets.addView(button, new LinearLayout.LayoutParams(new PersonalCameraLayout(context).px(136), new PersonalCameraLayout(context).px(120)));
        }
        cameraCharacteristics = proCapabilities;
        pro.refresh(proCapabilities);
        quick.refresh(proCapabilities);
        pro.reflow(landscape);
        bindZoomAnchors();
        updateZoomLabel();
        updateEnabled();
        layoutZoomControls();
    }

    void updateEnabled() {
        pro.updateEnabled();
        quick.refresh(cameraCharacteristics);
        boolean ready = sessionReady.getAsBoolean();
        boolean zoomable = ready && maxZoom > minZoom;
        for (int i = 0; i < presets.getChildCount(); i++) {
            View button = presets.getChildAt(i);
            CameraLensCatalog.Lens lens = (CameraLensCatalog.Lens) button.getTag();
            boolean supported = lens.supports(prefs.getCameraResolution(), prefs.getSpecificVideoFrameRate(camera));
            boolean enabled = ready && supported && (lensSwitchReady.getAsBoolean() || isSelected(lens) && zoomable);
            button.setEnabled(enabled); button.setAlpha(enabled || isSelected(lens) ? 1f : .4f);
            button.setContentDescription(context.getString(supported ? R.string.personal_lens_choice : R.string.personal_lens_unavailable,
                    lens.label, ratioText(lens.baseRatio)));
        }
        zoomValue.setEnabled(zoomable);
        zoomSlider.setEnabled(zoomable);
        for(int i=0;i<zoomAnchors.getChildCount();i++)
            ((android.widget.FrameLayout)zoomAnchors.getChildAt(i)).getChildAt(0).setEnabled(zoomable);
        adjust.setEnabled(ready && (minEv < maxEv || aeSupported || afModes.size() > 1));
        if (!ready) cancelGesture(false);
        updateZoomValueVisibility(root);
    }

    private boolean onTouch(View view, MotionEvent event) {
        if (!sessionReady.getAsBoolean()) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            multiTouch = moved = false;
            downX = event.getX(); downY = event.getY(); downTime = event.getEventTime();
            view.getParent().requestDisallowInterceptTouchEvent(true);
            if (context instanceof MainActivity) ((MainActivity) context).setPreviewGestureInProgress(true, zoom);
        }
        if (event.getPointerCount() > 1) multiTouch = true;
        scaleDetector.onTouchEvent(event);
        if (action == MotionEvent.ACTION_MOVE && Math.hypot(event.getX() - downX, event.getY() - downY)
                > ViewConfiguration.get(context).getScaledTouchSlop()) moved = true;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (multiTouch && zoomQueued) flushZoom();
            view.getParent().requestDisallowInterceptTouchEvent(false);
            if (context instanceof MainActivity) ((MainActivity) context).setPreviewGestureInProgress(false, zoom);
            if (action == MotionEvent.ACTION_UP && !multiTouch && !moved
                    && event.getEventTime() - downTime < ViewConfiguration.getLongPressTimeout() && focusSupported && !com.fadcam.utils.PersonalProSettings.usesManualFocus(prefs,camera,cameraCharacteristics)) {
                android.graphics.PointF point = focusPoint(event.getX(), event.getY(), view.getWidth(), view.getHeight());
                if (point == null) return true; // The dimmed aspect-fit border is not camera content.
                dispatch(RecordingControlIntents.tapToFocus(context, point.x, point.y));
                focusRing.setX(clamp(event.getX() - dp(32), 0, Math.max(0, view.getWidth() - dp(64))));
                focusRing.setY(clamp(event.getY() - dp(32), 0, Math.max(0, view.getHeight() - dp(64))));
                focusRing.setVisibility(View.VISIBLE); // Request feedback, not a fabricated AF success signal.
                handler.removeCallbacks(hideFocus);
                handler.postDelayed(hideFocus, 1200);
                view.performClick();
            }
        }
        return true;
    }

    private android.graphics.PointF focusPoint(float x, float y, int width, int height) {
        android.util.Size resolution = prefs.getCameraResolution();
        boolean portrait = SharedPreferencesManager.ORIENTATION_PORTRAIT.equals(prefs.getVideoOrientation());
        if(RecordingService.hasPreviewOnlySession() && !RecordingService.hasActiveSession()) {
            int rotation=com.fadcam.utils.CameraDisplayOrientation.currentRotation(context);
            portrait=rotation!=android.view.Surface.ROTATION_90 && rotation!=android.view.Surface.ROTATION_270;
        }
        float ar = portrait ? (float) Math.min(resolution.getWidth(), resolution.getHeight()) / Math.max(resolution.getWidth(), resolution.getHeight())
                : (float) Math.max(resolution.getWidth(), resolution.getHeight()) / Math.min(resolution.getWidth(), resolution.getHeight());
        int contentWidth = width, contentHeight = height;
        if (Math.abs((float) width / Math.max(1, height) - ar) >= .01f) {
            if ((float) width / Math.max(1, height) > ar) contentWidth = Math.round(height * ar);
            else contentHeight = Math.round(width / ar);
        }
        float left = (width - contentWidth) / 2, top = (height - contentHeight) / 2;
        if (x < left || y < top || x > left + contentWidth || y > top + contentHeight) return null;
        return new android.graphics.PointF((x - left) / Math.max(1, contentWidth), (y - top) / Math.max(1, contentHeight));
    }

    private void setZoom(float value, boolean immediate) {
        if (!Float.isFinite(value)) return;
        zoom = clamp(value, minZoom, maxZoom);
        prefs.setSpecificZoomRatio(camera, zoom);
        if (camera == CameraType.BACK) prefs.sharedPreferences.edit().putFloat("personal_lens_zoom_" + selectedLens, zoom).apply();
        prefs.setSpecificPan(camera, 0f, 0f);
        updateZoomLabel();
        if (immediate) flushZoom();
        else if (!zoomQueued) { zoomQueued = true; handler.postDelayed(sendZoom, 50); }
    }

    private void flushZoom() {
        handler.removeCallbacks(sendZoom);
        sendZoom.run();
    }

    static void updateZoomValueVisibility(View root) {
        View label=root.findViewById(R.id.personal_zoom_value);
        boolean manual=root.findViewById(R.id.personal_pro_panel).getVisibility()==View.VISIBLE;
        boolean adjusting=!manual && root.findViewById(R.id.personal_zoom_slider).getVisibility()==View.VISIBLE;
        label.setVisibility(manual || Boolean.TRUE.equals(label.getTag()) && !adjusting?View.GONE:View.VISIBLE);
        for(int id:new int[]{R.id.personal_zoom_background,R.id.personal_zoom_close,R.id.personal_zoom_anchors}) {
            View view=root.findViewById(id);if(view!=null)view.setVisibility(adjusting?View.VISIBLE:View.GONE);
        }
        View items=root.findViewById(R.id.personal_pro_items);
        View photoMode=root.findViewById(R.id.personal_camera_mode_photo);
        View videoMode=root.findViewById(R.id.personal_camera_mode_video);
        boolean simpleMode=photoMode!=null && photoMode.isSelected() || videoMode!=null && videoMode.isSelected();
        if(items!=null)((View)items.getParent()).setVisibility(simpleMode?View.GONE:adjusting?View.INVISIBLE:View.VISIBLE);
        View more=root.findViewById(R.id.personal_camera_more);
        if(more!=null)more.setVisibility(adjusting || manual?View.INVISIBLE:View.VISIBLE);
        if(!manual)root.findViewById(R.id.personal_zoom_presets_container).setVisibility(adjusting?View.GONE:View.VISIBLE);
    }

    private void bindZoomAnchors() {
        zoomAnchors.removeAllViews();
        // These are display stops, not physical-lens claims. Only expose stops
        // inside the selected Camera2 camera's actual local zoom range.
        float[] stops=baseRatio>1.5f?new float[]{1,2,5}:new float[]{1,2,4,10};
        List<Float> supported=new ArrayList<>();
        for(float stop:stops)if(stop>=minZoom && stop<=maxZoom)supported.add(stop);
        if(supported.isEmpty())supported.add(minZoom);
        if(landscape)java.util.Collections.reverse(supported);
        PersonalCameraLayout g=new PersonalCameraLayout(context);
        for(float local:supported) {
            androidx.appcompat.widget.AppCompatTextView button=new androidx.appcompat.widget.AppCompatTextView(context);
            float displayed=baseRatio*local;
            button.setText(String.format(Locale.getDefault(),Math.abs(displayed-Math.round(displayed))<.001f?"%.0f":"%.1f",displayed));
            button.setTag(local);button.setGravity(android.view.Gravity.CENTER);
            button.setTextSize(g.textSp(context,48));button.setTypeface(android.graphics.Typeface.create("sec-num-fixed",android.graphics.Typeface.BOLD));
            button.setIncludeFontPadding(false);button.setBackgroundResource(R.drawable.personal_zoom_anchor);
            button.setContentDescription(context.getString(R.string.personal_zoom_value,ratioText(displayed)));
            button.setClickable(true);button.setFocusable(true);
            androidx.core.view.ViewCompat.setAccessibilityDelegate(button,new androidx.core.view.AccessibilityDelegateCompat(){
                @Override public void onInitializeAccessibilityNodeInfo(View host,androidx.core.view.accessibility.AccessibilityNodeInfoCompat info){
                    super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(android.widget.Button.class.getName());
                }
            });
            button.setOnClickListener(v->{if(sessionReady.getAsBoolean())setZoom(local,true);});
            android.widget.FrameLayout slot=new android.widget.FrameLayout(context);
            slot.addView(button,new android.widget.FrameLayout.LayoutParams(g.px(128),g.px(96),android.view.Gravity.CENTER));
            zoomAnchors.addView(slot,new LinearLayout.LayoutParams(landscape?-1:0,landscape?0:-1,1));
        }
    }

    private void layoutZoomControls() {
        PersonalCameraLayout g=new PersonalCameraLayout(context);
        boolean expanded=zoomSlider.getVisibility()==View.VISIBLE;
        zoomAnchors.setOrientation(landscape?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        // Physical Samsung landscape reference: tick x2284, value x2060,
        // presets x2460 on 3120x1440; rotate the same axes for portrait.
        zoomBounds(zoomSlider,landscape?168:-1,landscape?-1:168,
                landscape?android.view.Gravity.END:android.view.Gravity.BOTTOM,
                landscape?0:144,landscape?192:0,landscape?752:144,landscape?192:752);
        zoomBounds(root.findViewById(R.id.personal_zoom_background),landscape?168:-1,landscape?-1:168,
                landscape?android.view.Gravity.END:android.view.Gravity.BOTTOM,
                landscape?0:48,landscape?48:0,landscape?752:48,landscape?48:752);
        zoomBounds(root.findViewById(R.id.personal_zoom_close),88,88,
                landscape?android.view.Gravity.END|android.view.Gravity.TOP:android.view.Gravity.END|android.view.Gravity.BOTTOM,
                0,landscape?88:0,landscape?792:88,landscape?0:792);
        zoomBounds(zoomAnchors,landscape?128:-1,landscape?-1:128,
                landscape?android.view.Gravity.END:android.view.Gravity.BOTTOM,
                landscape?0:144,landscape?144:0,landscape?596:144,landscape?144:596);
        zoomBounds(zoomValue,280,112,expanded?(landscape?android.view.Gravity.END|android.view.Gravity.CENTER_VERTICAL:android.view.Gravity.BOTTOM|android.view.Gravity.CENTER_HORIZONTAL):android.view.Gravity.END|android.view.Gravity.BOTTOM,
                0,0,expanded?(landscape?920:0):(landscape?696:0),expanded?(landscape?0:920):(landscape?0:756));
        TextView close=root.findViewById(R.id.personal_zoom_close);
        close.setTextSize(g.textSp(context,52.5f));close.setIncludeFontPadding(false);
        close.setTypeface(android.graphics.Typeface.create("sec",android.graphics.Typeface.NORMAL));
        zoomValue.setTextSize(g.textSp(context,expanded?64:48));
        zoomValue.setTextColor(expanded?0xFFFFD400:0xFFFFFFFF);
        ((PersonalCameraRuler)zoomSlider).setVertical(landscape);
    }
    private void zoomBounds(View view,int width,int height,int gravity,int start,int top,int end,int bottom) {
        PersonalCameraLayout g=new PersonalCameraLayout(context);
        android.widget.FrameLayout.LayoutParams p=(android.widget.FrameLayout.LayoutParams)view.getLayoutParams();
        p.width=width<0?width:g.px(width);p.height=height<0?height:g.px(height);p.gravity=gravity;
        p.setMargins(0,g.px(top),0,g.px(bottom));p.setMarginStart(g.px(start));p.setMarginEnd(g.px(end));view.setLayoutParams(p);
    }

    private void updateZoomLabel() {
        String label = ratioText(baseRatio * zoom);
        zoomValue.setText(label);
        zoomValue.setContentDescription(context.getString(R.string.personal_zoom_value, label));
        zoomValue.setTag(!lenses.isEmpty() && Math.abs(baseRatio*zoom-1f)<.001f);
        updateZoomValueVisibility((View)preview.getParent());
        zoomSlider.setProgress(maxZoom > minZoom
                ? Math.round((float) (Math.log(zoom / minZoom) / Math.log(maxZoom / minZoom)) * 1000) : 0);
        for(int i=0;i<zoomAnchors.getChildCount();i++) {
            TextView button=(TextView)((android.widget.FrameLayout)zoomAnchors.getChildAt(i)).getChildAt(0);boolean selected=Math.abs((Float)button.getTag()-zoom)<.001f;
            button.setSelected(selected);button.setTextColor(selected?0xFFFFD400:0xFFFFFFFF);button.setEnabled(sessionReady.getAsBoolean());
        }
        int selectedIndex=-1;
        for (int i = 0; i < presets.getChildCount(); i++) {
            View button = presets.getChildAt(i);
            boolean selected = isSelected((CameraLensCatalog.Lens) button.getTag());
            ((TextView) button).setTextColor(0xFFFFFFFF);
            button.setSelected(selected);
            if(selected)selectedIndex=i;
        }
        ((PersonalLensSelector)presets).selectIndex(selectedIndex);
    }

    private boolean isSelected(CameraLensCatalog.Lens lens) {
        return selectedLens.equals(lens.id) || Math.abs(lens.baseRatio - baseRatio) < .05f;
    }

    private void selectLens(CameraLensCatalog.Lens lens) {
        if(sessionReady.getAsBoolean() && isSelected(lens) && maxZoom>minZoom) {zoomValue.performClick();return;}
        if (!sessionReady.getAsBoolean() || !lensSwitchReady.getAsBoolean()
                || !lens.supports(prefs.getCameraResolution(), prefs.getSpecificVideoFrameRate(camera))
                || lens.id.equals(selectedLens)) return;
        cancelGesture(false);
        prefs.sharedPreferences.edit().putFloat("personal_lens_zoom_" + selectedLens, zoom).apply();
        prefs.setSelectedBackCameraId(lens.id);
        prefs.setSpecificZoomRatio(camera, prefs.sharedPreferences.getFloat("personal_lens_zoom_" + lens.id, 1f));
        prefs.setSpecificPan(camera, 0f, 0f);
        lensChanged.run(); // Stops/reopens only idle preview, never an encoder or active recording.
        refresh();
    }

    private void showAdjustments() {
        if (!sessionReady.getAsBoolean()) return;
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(12), dp(24), dp(12));
        TextView exposure = new TextView(context);
        SeekBar ev = new SeekBar(context);
        ev.setId(R.id.personal_exposure_slider);
        int saved = Math.max(minEv, Math.min(maxEv, prefs.getSavedExposureCompensation()));
        exposure.setText(context.getString(R.string.personal_exposure_value, saved * evStep));
        ev.setContentDescription(exposure.getText());
        ev.setMax(maxEv - minEv);
        ev.setProgress(saved - minEv);
        boolean manual = com.fadcam.utils.PersonalProSettings.usesManualExposure(prefs, cameraCharacteristics, prefs.getSpecificVideoFrameRate(camera));
        ev.setEnabled(minEv < maxEv && !prefs.isAeLockedSaved() && !manual);
        panel.addView(exposure); panel.addView(ev, new LinearLayout.LayoutParams(-1, dp(48)));
        ev.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || !sessionReady.getAsBoolean()) return;
                int index = progress + minEv;
                prefs.setSavedExposureCompensation(index);
                exposure.setText(context.getString(R.string.personal_exposure_value, index * evStep));
                ev.setContentDescription(exposure.getText());
                dispatch(RecordingControlIntents.setExposureCompensation(context, index));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        SwitchMaterial lock = new SwitchMaterial(context);
        lock.setId(R.id.personal_ae_lock);
        lock.setText(R.string.personal_ae_lock);
        lock.setChecked(prefs.isAeLockedSaved());
        lock.setEnabled(aeSupported && !manual);
        lock.setOnCheckedChangeListener((button, checked) -> {
            if (!sessionReady.getAsBoolean()) return;
            prefs.setSavedAeLock(checked);
            ev.setEnabled(minEv < maxEv && !checked);
            dispatch(RecordingControlIntents.toggleAeLock(context, checked));
        });
        panel.addView(lock);
        if (!afModes.isEmpty()) {
            MaterialButton af = new MaterialButton(context);
            af.setId(R.id.personal_af_picker);
            af.setText(focusLabel(prefs.getSavedAfMode()));
            af.setContentDescription(context.getString(R.string.personal_af_mode));
            af.setOnClickListener(v -> {
                CharSequence[] options = new CharSequence[afModes.size()];
                for (int i = 0; i < options.length; i++) options[i] = context.getString(focusLabel(afModes.get(i)));
                focusDialog = new MaterialAlertDialogBuilder(context).setTitle(R.string.personal_af_mode)
                        .setSingleChoiceItems(options, afModes.indexOf(prefs.getSavedAfMode()), (picker, index) -> {
                            if (sessionReady.getAsBoolean()) {
                                int mode = afModes.get(index);
                                com.fadcam.utils.PersonalProSettings.saveFocus(prefs, camera, -1f);
                                prefs.setSavedAfMode(mode);
                                dispatch(RecordingControlIntents.setAfMode(context, mode));
                                af.setText(focusLabel(mode));
                            }
                            picker.dismiss();
                        }).show();
            });
            panel.addView(af);
        }
        dialog = new MaterialAlertDialogBuilder(context).setTitle(R.string.personal_camera_adjust)
                .setView(panel).setPositiveButton(R.string.personal_camera_done, null).show();
    }

    private int focusLabel(int mode) {
        return mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO ? R.string.personal_af_continuous
                : mode == CaptureRequest.CONTROL_AF_MODE_AUTO ? R.string.personal_af_auto : R.string.personal_af_off;
    }

    private void dispatch(Intent intent) {
        if (!sessionReady.getAsBoolean()) return;
        try { context.startService(intent); }
        catch (RuntimeException e) { FLog.w("PersonalControls", "Camera control unavailable", e); }
    }

    void cancelGesture() {cancelGesture(true);}
    private void cancelGesture(boolean settleZoom) {
        boolean sameCamera=camera==prefs.getCameraSelection();
        boolean sameLens=camera==CameraType.FRONT || java.util.Objects.equals(selectedLens,prefs.getSelectedBackCameraId());
        // A same-lens UI reflow must not discard the final accepted capture ratio.
        // Camera switches/unready sessions still discard their stale queued command.
        if(settleZoom && zoomQueued && sameCamera && sameLens && sessionReady.getAsBoolean())flushZoom();
        pro.hide(); quick.dismiss();
        zoomSlider.setVisibility(View.GONE);updateZoomValueVisibility(root);layoutZoomControls();
        handler.removeCallbacks(sendZoom);
        zoomQueued = false;
        handler.removeCallbacks(hideFocus);
        focusRing.setVisibility(View.GONE);
        preview.getParent().requestDisallowInterceptTouchEvent(false);
        if (context instanceof MainActivity) ((MainActivity) context).setPreviewGestureInProgress(false, zoom);
        if (dialog != null) { dialog.dismiss(); dialog = null; }
        if (focusDialog != null) { focusDialog.dismiss(); focusDialog = null; }
    }

    void refreshLiveValues() { pro.refreshLiveValues(); }

    void reflow(boolean landscape) {
        boolean directionChanged=this.landscape!=landscape;
        this.landscape=landscape;
        ((PersonalCameraRuler)zoomSlider).setVertical(landscape);
        // Rotation changes layout, not camera capabilities. Reuse the existing
        // lens/zoom views instead of re-querying Camera2 and replacing every chip.
        if(directionChanged) {
            java.util.Collections.reverse(lenses);
            reverseChildren(presets);
            reverseChildren(zoomAnchors);
        }
        presets.setOrientation(landscape?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        for(int index=0;index<zoomAnchors.getChildCount();index++) {
            View slot=zoomAnchors.getChildAt(index);
            slot.setLayoutParams(new LinearLayout.LayoutParams(landscape?-1:0,landscape?0:-1,1));
        }
        pro.reflow(landscape);quick.reflow(landscape);
        updateZoomLabel();updateEnabled();layoutZoomControls();
    }

    private static void reverseChildren(LinearLayout row) {
        List<View> children=new ArrayList<>();
        for(int index=0;index<row.getChildCount();index++)children.add(row.getChildAt(index));
        row.removeAllViews();
        for(int index=children.size()-1;index>=0;index--)row.addView(children.get(index));
    }

    void release() { cancelGesture(false); preview.setOnTouchListener(null); }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private String ratioText(float value) { return String.format(Locale.getDefault(), "%.1f×", value); }
}
