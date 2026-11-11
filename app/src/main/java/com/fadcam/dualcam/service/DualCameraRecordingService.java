package com.fadcam.dualcam.service;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.Manifest;
import android.app.PendingIntent;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Size;
import android.view.Surface;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.documentfile.provider.DocumentFile;

import com.fadcam.Constants;
import com.fadcam.MaximumRecordingDuration;
import com.fadcam.R;
import com.fadcam.RecordingDurationLimitController;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.dualcam.DualCameraCapability;
import com.fadcam.dualcam.DualCameraConfig;
import com.fadcam.dualcam.DualCameraState;
import com.fadcam.opengl.GLRecordingPipeline;
import com.fadcam.opengl.WatermarkInfoProvider;
import com.fadcam.utils.PhotoStorageHelper;
import com.fadcam.utils.PhotoSaveDispatch;
import com.fadcam.utils.RecordingPermissions;
import com.fadcam.utils.RecordingStoragePaths;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Foreground service for dual camera (PiP) recording.
 *
 * <p>Manages <b>two</b> {@link CameraDevice} instances (front + back) simultaneously
 * and delegates compositing/encoding to {@link GLRecordingPipeline} with PiP support.
 *
 * <h3>Intent Actions</h3>
 * <ul>
 *   <li>{@link Constants#INTENT_ACTION_START_DUAL_RECORDING}</li>
 *   <li>{@link Constants#INTENT_ACTION_STOP_DUAL_RECORDING}</li>
 *   <li>{@link Constants#INTENT_ACTION_PAUSE_DUAL_RECORDING}</li>
 *   <li>{@link Constants#INTENT_ACTION_RESUME_DUAL_RECORDING}</li>
 *   <li>{@link Constants#INTENT_ACTION_SWAP_DUAL_CAMERAS}</li>
 *   <li>{@link Constants#INTENT_ACTION_UPDATE_PIP_CONFIG}</li>
 * </ul>
 *
 * <h3>Broadcasts</h3>
 * <ul>
 *   <li>{@link Constants#BROADCAST_ON_DUAL_RECORDING_STARTED}</li>
 *   <li>{@link Constants#BROADCAST_ON_DUAL_RECORDING_STOPPED}</li>
 *   <li>{@link Constants#BROADCAST_ON_DUAL_RECORDING_PAUSED}</li>
 *   <li>{@link Constants#BROADCAST_ON_DUAL_RECORDING_RESUMED}</li>
 *   <li>{@link Constants#BROADCAST_ON_DUAL_CAMERA_ERROR}</li>
 *   <li>{@link Constants#BROADCAST_ON_DUAL_CAMERAS_SWAPPED}</li>
 * </ul>
 */
public class DualCameraRecordingService extends Service {
    private static volatile DualCameraRecordingService liveInstance;
    private final com.fadcam.utils.RecordingHaptics recordingHaptics = new com.fadcam.utils.RecordingHaptics();

    /** Includes accepted startup and pause, so every launcher controls the same session. */
    public static boolean hasActiveSession() {
        return getSessionState() != com.fadcam.RecordingState.NONE;
    }

    public static com.fadcam.RecordingState getSessionState() {
        DualCameraRecordingService service = liveInstance;
        if (service == null || service.isStopping) return com.fadcam.RecordingState.NONE;
        switch (service.state) {
            case INITIALIZING:
            case PREVIEW_ONLY: return com.fadcam.RecordingState.STARTING;
            case RECORDING: return com.fadcam.RecordingState.IN_PROGRESS;
            case PAUSED: return com.fadcam.RecordingState.PAUSED;
            default: return com.fadcam.RecordingState.NONE;
        }
    }

    private static final String TAG = "DualCamService";

    // Reuse RecordingService notification channel for consistent UX
    private static final String CHANNEL_ID = "RecordingServiceChannel";
    private static final int NOTIFICATION_ID = 2; // Different ID from RecordingService (1)

    // ── Camera fields ──────────────────────────────────────────────────

    private CameraManager cameraManager;

    /** Primary camera — fills the full frame. */
    private CameraDevice primaryCameraDevice;
    private CameraCaptureSession primarySession;
    private CaptureRequest.Builder primaryRequestBuilder;

    /** Secondary camera — rendered in PiP overlay. */
    private CameraDevice secondaryCameraDevice;
    private CameraCaptureSession secondarySession;
    private CaptureRequest.Builder secondaryRequestBuilder;

    /** Resolved camera IDs from {@link DualCameraCapability}. */
    private String frontCameraId;
    private String backCameraId;

    /** Current torch state for the primary camera. */
    private boolean isTorchOn = false;

    // ── Pipeline ───────────────────────────────────────────────────────

    /** Unified recording pipeline — same as single-camera mode, but with PiP enabled. */
    private GLRecordingPipeline recordingPipeline;

    // ── State ──────────────────────────────────────────────────────────

    private volatile DualCameraState state = DualCameraState.DISABLED;
    private DualCameraConfig config;
    private long recordingStartTime;
    private long pauseStartedAt;
    private long accumulatedPausedDurationMs;

    // ── Threading ──────────────────────────────────────────────────────

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private RecordingDurationLimitController durationLimitController;
    private SharedPreferences.OnSharedPreferenceChangeListener durationPreferenceListener;

    // ── System ─────────────────────────────────────────────────────────

    private SharedPreferencesManager prefs;
    private com.fadcam.watermark.WatermarkManager watermarkManager;
    private DualCameraCapability capability;
    private PowerManager.WakeLock wakeLock;

    // Storage location support
    private android.os.ParcelFileDescriptor safRecordingPfd;  // ParcelFileDescriptor for SAF mode
    private Uri safRecordingUri;  // SAF URI
    private String safOutputFileName;   // Filename for SAF
    @Nullable
    private String lastRecordingUriString;

    // Guard against duplicate open/close races
    private volatile boolean isStopping = false;
    private int camerasOpened = 0; // Track how many cameras have opened successfully

    private boolean sessionAudioEnabled;
    private boolean destroyed;
    private boolean releasePending;
    private boolean publishedRecording;
    private int pendingCameraOpens;
    private final Map<CameraDevice, CompletableFuture<Void>> closingCameras = new IdentityHashMap<>();
    private static final long CAMERA_CLOSE_WARNING_MS = 2500;

    // ════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ════════════════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        liveInstance = this;
        FLog.d(TAG, "onCreate");

        prefs = SharedPreferencesManager.getInstance(getApplicationContext());
        initializeDurationLimitController();
        capability = new DualCameraCapability(this);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        if (cameraManager == null) {
            FLog.e(TAG, "CameraManager unavailable — cannot start dual camera service");
            stopSelf();
            return;
        }

        startBackgroundThread();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            FLog.w(TAG, "onStartCommand: null intent/action");
            return START_STICKY;
        }

        String action = intent.getAction();
        FLog.d(TAG, "onStartCommand: action=" + action);

        switch (action) {
            case Constants.INTENT_ACTION_START_DUAL_RECORDING:
                handleStartDualRecording();
                break;

            case Constants.INTENT_ACTION_STOP_DUAL_RECORDING:
                handleStopDualRecording();
                break;

            case Constants.INTENT_ACTION_PAUSE_DUAL_RECORDING:
                handlePauseDualRecording();
                break;

            case Constants.INTENT_ACTION_RESUME_DUAL_RECORDING:
                handleResumeDualRecording();
                break;

            case Constants.INTENT_ACTION_SWAP_DUAL_CAMERAS:
                handleSwapCameras();
                break;

            case Constants.INTENT_ACTION_UPDATE_PIP_CONFIG:
                handleUpdatePipConfig();
                break;

            case Constants.INTENT_ACTION_CHANGE_SURFACE:
                handleChangeSurface(intent);
                break;

            case Constants.INTENT_ACTION_TOGGLE_RECORDING_TORCH:
                handleToggleTorch();
                break;

            case Constants.INTENT_ACTION_SET_FRONT_VIDEO_MIRROR:
                handleSetFrontVideoMirror(intent);
                break;

            case Constants.INTENT_ACTION_SET_EXPOSURE_COMPENSATION:
                handleSetExposureCompensation(intent);
                break;

            case Constants.INTENT_ACTION_TOGGLE_AE_LOCK:
                handleToggleAeLock(intent);
                break;

            case Constants.INTENT_ACTION_SET_AF_MODE:
                handleSetAfMode(intent);
                break;

            case Constants.INTENT_ACTION_TAP_TO_FOCUS:
                handleTapToFocus();
                break;

            case Constants.INTENT_ACTION_SET_ZOOM_RATIO:
                handleSetZoomRatio(intent);
                break;

            case Constants.INTENT_ACTION_CAPTURE_PHOTO:
                handleCapturePhoto();
                break;

            default:
                FLog.w(TAG, "Unknown action: " + action);
                break;
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        FLog.d(TAG, "onDestroy");
        if (liveInstance == this) liveInstance = null;
        synchronized (this) {
            destroyed = true;
            releaseDurationLimitController();
            releaseAllResources();
            clearOwnedRecordingState();
            maybeStopBackgroundThread();
        }
        releaseWakeLock();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }

    // ════════════════════════════════════════════════════════════════════
    // ACTION HANDLERS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Start dual camera recording.
     * 1. Validate permissions + capability
     * 2. Show foreground notification
     * 3. Open both cameras sequentially
     * 4. Create pipeline + start encoding
     */
    private synchronized void handleStartDualRecording() {
        if (isStopping || state != DualCameraState.DISABLED) {
            FLog.w(TAG, "Cannot start dual recording — state=" + state);
            return;
        }
        if (durationLimitController != null) {
            durationLimitController.stopSession();
        }
        lastRecordingUriString = null;

        sessionAudioEnabled = prefs.isRecordAudioEnabled();
        if (!RecordingPermissions.canRecord(this, sessionAudioEnabled)) {
            rejectStart(R.string.toast_permissions_required);
            return;
        }

        if (!capability.isSupported()) {
            FLog.w(TAG, "Dual camera not supported: " + capability.getUnsupportedReason());
            rejectStart(R.string.note_cam_dual_unsupported);
            return;
        }
        frontCameraId = capability.getConcurrentFrontCameraId();
        backCameraId = capability.getConcurrentBackCameraId();
        if (frontCameraId == null || backCameraId == null || frontCameraId.equals(backCameraId)) {
            rejectStart(R.string.note_cam_dual_unsupported);
            return;
        }

        // ── Load config ───────────────────────────────────────────────
        config = prefs.getDualCameraConfig();
        recordingHaptics.beginSession();
        state = DualCameraState.INITIALIZING;
        camerasOpened = 0;

        // ── Foreground notification ───────────────────────────────────
        startForegroundNotification();

        // ── Acquire wake lock ─────────────────────────────────────────
        acquireWakeLock();

        // ── Open cameras ──────────────────────────────────────────────
        openBothCameras();
    }

    private void rejectStart(int reasonResource) {
        isStopping = true;
        String reason = getString(reasonResource);
        broadcastError(reason);
        mainHandler.post(() -> Toast.makeText(this, reason, Toast.LENGTH_SHORT).show());
        stopSelf();
    }

    /**
     * Stop dual camera recording and clean up all resources.
     */
    private synchronized void handleStopDualRecording() {
        if (isStopping) return;
        if (durationLimitController != null && durationLimitController.stopSession()) {
            FLog.d(TAG, "Cancelled maximum recording duration for current dual session");
        }
        if (state == DualCameraState.DISABLED) {
            FLog.w(TAG, "Already stopped / disabled");
            stopSelf();
            return;
        }

        FLog.i(TAG, "Stopping dual camera recording");
        isStopping = true;
        state = DualCameraState.DISABLED;
        recordingHaptics.onRecordingStopped(this);
        releasePipelineAndCamerasAsync(saved -> {
            if (destroyed) return;
            clearOwnedRecordingState();
            releaseWakeLock();
            pauseStartedAt = 0;
            accumulatedPausedDurationMs = 0;
            broadcastRecordingComplete(saved);
            if (!saved) Toast.makeText(this, R.string.personal_recording_save_failed, Toast.LENGTH_LONG).show();
            lastRecordingUriString = null;
            broadcastAction(Constants.BROADCAST_ON_DUAL_RECORDING_STOPPED);
            stopSelf();
        });
    }

    private void handlePauseDualRecording() {
        if (state != DualCameraState.RECORDING) {
            FLog.w(TAG, "Cannot pause — state=" + state);
            return;
        }

        if (recordingPipeline != null) {
            recordingPipeline.pauseRecording();
        }
        if (watermarkManager != null) watermarkManager.pauseSensors();
        state = DualCameraState.PAUSED;
        pauseStartedAt = SystemClock.elapsedRealtime();
        if (durationLimitController != null && durationLimitController.pauseSession()) {
            FLog.d(TAG, "Paused maximum recording duration countdown");
        }
        persistRecordingTimelineState();
        broadcastActionWithTiming(Constants.BROADCAST_ON_DUAL_RECORDING_PAUSED);
        updatePausedNotification();
        FLog.i(TAG, "Dual recording paused");
    }

    private void handleResumeDualRecording() {
        if (state != DualCameraState.PAUSED) {
            FLog.w(TAG, "Cannot resume — state=" + state);
            return;
        }

        if (recordingPipeline != null) {
            recordingPipeline.resumeRecording();
        }
        if (watermarkManager != null) watermarkManager.resumeSensors(null);
        state = DualCameraState.RECORDING;
        if (pauseStartedAt > 0L) {
            accumulatedPausedDurationMs += Math.max(0L, SystemClock.elapsedRealtime() - pauseStartedAt);
            pauseStartedAt = 0L;
        }
        if (durationLimitController != null && durationLimitController.resumeSession()) {
            FLog.d(TAG, "Resumed maximum recording duration countdown");
        }
        persistRecordingTimelineState();
        broadcastActionWithTiming(Constants.BROADCAST_ON_DUAL_RECORDING_RESUMED);
        updateResumedNotification();
        FLog.i(TAG, "Dual recording resumed");
    }

    /**
     * Swap primary ↔ secondary cameras by closing and recreating capture sessions.
     * Cameras are reassigned to surfaces so the GL renderer needs no swap logic.
     */
    private void handleSwapCameras() {
        if (state != DualCameraState.RECORDING && state != DualCameraState.PAUSED) {
            FLog.w(TAG, "Cannot swap cameras — state=" + state);
            return;
        }

        DualCameraConfig.PrimaryCamera newPrimary =
                (config.getPrimaryCamera() == DualCameraConfig.PrimaryCamera.BACK)
                        ? DualCameraConfig.PrimaryCamera.FRONT
                        : DualCameraConfig.PrimaryCamera.BACK;

        config = new DualCameraConfig.Builder(config)
                .primaryCamera(newPrimary)
                .build();
        prefs.saveDualCameraConfig(config);

        // Notify renderer which camera is now front (for rotation/flip logic)
        if (recordingPipeline != null) {
            recordingPipeline.setFullscreenCameraIsFront(
                newPrimary == DualCameraConfig.PrimaryCamera.FRONT);
        }

        // Close existing sessions and stop repeating requests
        if (primarySession != null) {
            try { primarySession.stopRepeating(); primarySession.close(); } catch (Exception ignored) {}
            primarySession = null;
        }
        if (secondarySession != null) {
            try { secondarySession.stopRepeating(); secondarySession.close(); } catch (Exception ignored) {}
            secondarySession = null;
        }

        // Swap camera device references
        CameraDevice tmp = primaryCameraDevice;
        primaryCameraDevice = secondaryCameraDevice;
        secondaryCameraDevice = tmp;

        // Recreate sessions: each camera targets the SAME surface as before
        // (primaryCameraDevice → cameraInputSurface, secondaryCameraDevice → pipCameraInputSurface)
        if (recordingPipeline != null) {
            Surface ps = recordingPipeline.getPrimaryCameraInputSurface();
            Surface ss = recordingPipeline.getSecondaryCameraInputSurface();
            if (ps != null && primaryCameraDevice != null) {
                createCaptureSession(primaryCameraDevice, ps, true);
            }
            if (ss != null && secondaryCameraDevice != null) {
                createCaptureSession(secondaryCameraDevice, ss, false);
            }
        }

        broadcastAction(Constants.BROADCAST_ON_DUAL_CAMERAS_SWAPPED);
        FLog.i(TAG, "Cameras swapped — new primary: " + newPrimary);
    }

    /**
     * Hot-update PiP configuration (position, size, border) without restarting recording.
     */
    private void handleUpdatePipConfig() {
        config = prefs.getDualCameraConfig();
        if (recordingPipeline != null) {
            recordingPipeline.updateConfig(config);
        }
        FLog.d(TAG, "PiP config updated live");
    }

    /**
     * Handles a preview surface change sent from the UI (HomeFragment).
     * Forwards the surface to the recording pipeline for live preview rendering.
     *
     * @param intent Intent containing "SURFACE" extra and optional dimensions.
     */
    private void handleChangeSurface(@NonNull Intent intent) {
        Surface surface = com.fadcam.Utils.getParcelableExtraCompat(intent, "SURFACE", android.view.Surface.class);
        int surfaceW = intent.getIntExtra("SURFACE_WIDTH", 0);
        int surfaceH = intent.getIntExtra("SURFACE_HEIGHT", 0);
        boolean isFullscreenTransition = intent.getBooleanExtra("IS_FULLSCREEN_TRANSITION", false);

        if (recordingPipeline != null) {
            // Use IMMEDIATE mode for fullscreen to bypass debounce
            if (isFullscreenTransition && surface != null && surface.isValid()) {
                FLog.d(TAG, "Setting preview surface IMMEDIATE (fullscreen transition)");
                recordingPipeline.setPreviewSurfaceImmediate(surface);
            } else {
                recordingPipeline.setPreviewSurface(surface);
            }
            if (surfaceW > 0 && surfaceH > 0) {
                recordingPipeline.updateSurfaceDimensions(surfaceW, surfaceH);
            }
            FLog.d(TAG, "Preview surface updated: " +
                    (surface != null && surface.isValid() ? surfaceW + "x" + surfaceH : "null") +
                    " (immediate=" + isFullscreenTransition + ")");
        } else {
            FLog.w(TAG, "handleChangeSurface: pipeline not ready, surface change ignored");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // RUNTIME CAMERA CONTROLS (torch, zoom, exposure, AF)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Toggles the torch on whichever running camera supports flash.
     * When the primary camera (e.g. front) lacks hardware flash, the secondary
     * (back) camera's session is used instead so torch always works.
     */
    private void handleToggleTorch() {
        if (primaryRequestBuilder == null || primarySession == null) {
            FLog.w(TAG, "handleToggleTorch: no active primary session");
            return;
        }
        isTorchOn = !isTorchOn;

        // Determine which camera session supports torch
        CameraCaptureSession torchSession = primarySession;
        CaptureRequest.Builder torchBuilder = primaryRequestBuilder;
        boolean primaryHasFlash = false;
        try {
            String primaryId = (config.getPrimaryCamera() == DualCameraConfig.PrimaryCamera.BACK)
                    ? backCameraId : frontCameraId;
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(primaryId);
            Boolean flashAvail = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            primaryHasFlash = (flashAvail != null && flashAvail);
        } catch (Exception e) {
            FLog.w(TAG, "Could not check flash availability for primary camera", e);
        }

        if (!primaryHasFlash && secondarySession != null && secondaryRequestBuilder != null) {
            torchSession = secondarySession;
            torchBuilder = secondaryRequestBuilder;
            FLog.d(TAG, "Primary camera has no flash — using secondary camera torch");
        }

        torchBuilder.set(CaptureRequest.FLASH_MODE,
                isTorchOn ? CaptureRequest.FLASH_MODE_TORCH
                          : CaptureRequest.FLASH_MODE_OFF);

        try {
            torchSession.setRepeatingRequest(torchBuilder.build(), null, backgroundHandler);
            FLog.d(TAG, "Torch toggled: " + (isTorchOn ? "ON" : "OFF")
                    + " (using " + (torchSession == primarySession ? "primary" : "secondary") + " session)");
        } catch (CameraAccessException e) {
            FLog.e(TAG, "Failed to toggle torch", e);
            isTorchOn = !isTorchOn;
            return;
        }

        prefs.sharedPreferences.edit()
                .putBoolean(Constants.PREF_TORCH_STATE, isTorchOn).apply();
        Intent broadcast = new Intent(Constants.BROADCAST_ON_TORCH_STATE_CHANGED);
        broadcast.putExtra(Constants.INTENT_EXTRA_TORCH_STATE, isTorchOn);
        sendBroadcast(broadcast);
    }

    private void handleSetFrontVideoMirror(Intent intent) {
        boolean enabled = intent.getBooleanExtra(
                Constants.EXTRA_FRONT_VIDEO_MIRROR_ENABLED,
                prefs.isFrontVideoMirrorEnabled());
        prefs.setFrontVideoMirrorEnabled(enabled);
        if (recordingPipeline != null) {
            recordingPipeline.setFrontVideoMirrorEnabled(enabled);
        }
        Intent mirrorBcast = new Intent(Constants.BROADCAST_ON_MIRROR_CHANGED);
        mirrorBcast.putExtra(Constants.EXTRA_MIRROR_ENABLED, enabled);
        this.sendBroadcast(mirrorBcast.setPackage(this.getPackageName()));
        FLog.i(TAG, "Front video mirror set: " + enabled);
    }

    /**
     * Sets the exposure compensation value on the primary camera.
     */
    private void handleSetExposureCompensation(@NonNull Intent intent) {
        int ev = intent.getIntExtra(Constants.EXTRA_EXPOSURE_COMPENSATION, 0);
        try {
            prefs.setSavedExposureCompensation(ev);
        } catch (Exception ignored) {
        }
        if (primaryRequestBuilder == null || primarySession == null) return;
        primaryRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev);
        if (applyPrimaryRepeating()) {
            FLog.d(TAG, "Exposure compensation set to: " + ev);
        }
    }

    /**
     * Toggles the AE lock on the primary camera.
     */
    private void handleToggleAeLock(@NonNull Intent intent) {
        boolean lock = intent.getBooleanExtra(Constants.EXTRA_AE_LOCK, false);
        try {
            prefs.setSavedAeLock(lock);
        } catch (Exception ignored) {
        }
        if (primaryRequestBuilder == null || primarySession == null) return;
        primaryRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, lock);
        if (applyPrimaryRepeating()) {
            FLog.d(TAG, "AE lock set to: " + lock);
        }
    }

    /**
     * Sets the autofocus mode on the primary camera.
     */
    private void handleSetAfMode(@NonNull Intent intent) {
        int mode = intent.getIntExtra(Constants.EXTRA_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        try {
            prefs.setSavedAfMode(mode);
        } catch (Exception ignored) {
        }
        if (primaryRequestBuilder == null || primarySession == null) return;
        primaryRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, mode);
        if (applyPrimaryRepeating()) {
            FLog.d(TAG, "AF mode set to: " + mode);
        }
    }

    /**
     * Triggers an AF scan on the primary camera.
     */
    private void handleTapToFocus() {
        if (primaryRequestBuilder == null || primarySession == null) return;
        try {
            primaryRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_START);
            primarySession.capture(primaryRequestBuilder.build(),
                    null, backgroundHandler);
            // Reset trigger for subsequent repeating requests
            primaryRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            FLog.d(TAG, "Tap-to-focus triggered");
        } catch (CameraAccessException e) {
            FLog.e(TAG, "Tap-to-focus failed", e);
        }
    }

    private void handleCapturePhoto() {
        if (state == DualCameraState.PAUSED) {
            mainHandler.post(() -> Toast.makeText(getApplicationContext(),
                    R.string.photo_capture_paused, Toast.LENGTH_SHORT).show());
            return;
        }
        if (recordingPipeline == null || state != DualCameraState.RECORDING) {
            mainHandler.post(() -> Toast.makeText(getApplicationContext(),
                    R.string.photo_capture_preview_unavailable, Toast.LENGTH_SHORT).show());
            return;
        }
        recordingPipeline.capturePhotoFrame(bitmap -> {
            if (bitmap == null) { notifyPhotoSaveFailure(); return; }
            PhotoSaveDispatch.post(backgroundHandler, bitmap, () -> {
                Uri saved = PhotoStorageHelper.saveJpegBitmap(
                        getApplicationContext(), bitmap, false, PhotoStorageHelper.ShotSource.BACK);
                if (saved == null) { notifyPhotoSaveFailure(); return; }
                Intent completed = new Intent(Constants.ACTION_RECORDING_COMPLETE);
                completed.putExtra(Constants.EXTRA_RECORDING_SUCCESS, true);
                completed.putExtra(Constants.EXTRA_RECORDING_URI_STRING, saved.toString());
                sendBroadcast(completed);
                mainHandler.post(() -> Toast.makeText(getApplicationContext(),
                        R.string.photo_capture_saved, Toast.LENGTH_SHORT).show());
            }, this::notifyPhotoSaveFailure);
        });
    }

    private void notifyPhotoSaveFailure() {
        mainHandler.post(() -> Toast.makeText(getApplicationContext(),
                R.string.photo_capture_failed, Toast.LENGTH_SHORT).show());
    }

    private void handleSetZoomRatio(@NonNull Intent intent) {
        if (primaryRequestBuilder == null || primarySession == null) return;
        float zoom = intent.getFloatExtra(Constants.EXTRA_ZOOM_RATIO, 1.0f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            primaryRequestBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
        }
        if (applyPrimaryRepeating()) {
            FLog.d(TAG, "Zoom ratio set to: " + zoom);
        }
    }

    /**
     * Applies the current primary request builder as a repeating request.
     *
     * @return {@code true} if the request was applied successfully.
     */
    private boolean applyPrimaryRepeating() {
        try {
            primarySession.setRepeatingRequest(
                    primaryRequestBuilder.build(), null, backgroundHandler);
            return true;
        } catch (CameraAccessException e) {
            FLog.e(TAG, "Failed to apply primary repeating request", e);
            return false;
        } catch (IllegalStateException e) {
            FLog.w(TAG, "Primary session already closed", e);
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // CAMERA OPENING
    // ════════════════════════════════════════════════════════════════════

    /** Opens the second camera only after the first opens; no single-feed substitute. */
    private synchronized void openBothCameras() {
        String primaryId = config.getPrimaryCamera() == DualCameraConfig.PrimaryCamera.BACK ? backCameraId : frontCameraId;
        String secondaryId = config.getPrimaryCamera() == DualCameraConfig.PrimaryCamera.BACK ? frontCameraId : backCameraId;
        requestCameraOpen(primaryId, true, camera -> {
            primaryCameraDevice = camera;
            if (!backgroundHandler.postDelayed(() -> openSecondaryCamera(secondaryId), 300)) {
                transitionToError(getString(R.string.personal_home_recording_failed));
            }
        });
    }

    private synchronized void openSecondaryCamera(@NonNull String secondaryId) {
        if (isStopping || primaryCameraDevice == null) return;
        requestCameraOpen(secondaryId, false, camera -> {
            secondaryCameraDevice = camera;
            camerasOpened = 2;
            onCameraOpened();
        });
    }

    /** Caller holds the service lock so a stop cannot miss an outstanding open. */
    private void requestCameraOpen(String id, boolean primary, Consumer<CameraDevice> opened) {
        Handler callbacks = backgroundHandler;
        if (callbacks == null || ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            transitionToError(getString(R.string.toast_permissions_required));
            return;
        }
        pendingCameraOpens++;
        try {
            cameraManager.openCamera(id, new CameraDevice.StateCallback() {
                private boolean opening = true;

                private void finishOpening() {
                    if (opening) {
                        opening = false;
                        pendingCameraOpens--;
                    }
                }

                @Override public void onOpened(@NonNull CameraDevice camera) {
                    synchronized (DualCameraRecordingService.this) {
                        finishOpening();
                        if (isStopping) {
                            if (!closingCameras.containsKey(camera)) closeDevice(camera);
                        } else {
                            opened.accept(camera);
                        }
                        maybeStopBackgroundThread();
                    }
                }

                @Override public void onDisconnected(@NonNull CameraDevice camera) {
                    failed(camera, "disconnected");
                }

                @Override public void onError(@NonNull CameraDevice camera, int error) {
                    failed(camera, "error " + error);
                }

                private void failed(CameraDevice camera, String detail) {
                    synchronized (DualCameraRecordingService.this) {
                        finishOpening();
                        FLog.w(TAG, "Camera " + id + " " + detail);
                        if (isStopping) {
                            if (!closingCameras.containsKey(camera)) closeDevice(camera);
                        } else {
                            if (camera != primaryCameraDevice && camera != secondaryCameraDevice) {
                                if (primary) primaryCameraDevice = camera;
                                else secondaryCameraDevice = camera;
                            }
                            transitionToError(getString(R.string.personal_home_recording_failed));
                        }
                        maybeStopBackgroundThread();
                    }
                }

                @Override public void onClosed(@NonNull CameraDevice camera) {
                    synchronized (DualCameraRecordingService.this) {
                        finishOpening();
                        CompletableFuture<Void> signal = closingCameras.remove(camera);
                        if (signal != null) signal.complete(null);
                        maybeStopBackgroundThread();
                    }
                }
            }, callbacks);
        } catch (CameraAccessException | RuntimeException e) {
            pendingCameraOpens--;
            FLog.e(TAG, "Cannot open dual camera " + id, e);
            transitionToError(getString(R.string.personal_home_recording_failed));
        }
    }

    private synchronized void onCameraOpened() {
        if (isStopping || state != DualCameraState.INITIALIZING) return;
        if (primaryCameraDevice == null || secondaryCameraDevice == null) {
            transitionToError(getString(R.string.personal_home_recording_failed));
            return;
        }
        startDualRecording();
    }

    // ════════════════════════════════════════════════════════════════════
    // RECORDING SETUP
    // ════════════════════════════════════════════════════════════════════

    /**
     * Builds the {@link GLRecordingPipeline} with PiP support, prepares surfaces,
     * creates Camera2 capture sessions, and starts encoding.
     *
     * <p>Uses the same proven pipeline as single-camera recording, but with
     * {@link DualCameraConfig} to enable PiP compositing in the GL renderer.
     */
    private void startDualRecording() {
        FLog.d(TAG, "startDualRecording: setting up unified pipeline");

        try {
            // ── Resolution + codec ────────────────────────────────────
            Size resolution = prefs.getCameraResolution();
            int videoWidth = resolution.getWidth();
            int videoHeight = resolution.getHeight();
            int fps = prefs.getSpecificVideoFrameRate(
                    config.getPrimaryCamera() == DualCameraConfig.PrimaryCamera.BACK
                            ? com.fadcam.CameraType.BACK : com.fadcam.CameraType.FRONT);
            com.fadcam.VideoCodec codec = prefs.getVideoCodec();
            String orientation = prefs.getVideoOrientation();

            // ── Sensor orientation for primary camera ─────────────────
            String primaryId = (config.getPrimaryCamera() == DualCameraConfig.PrimaryCamera.BACK)
                    ? backCameraId : frontCameraId;
            int sensorOrientation = 0;
            try {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(primaryId);
                Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
                if (so != null) sensorOrientation = so;
            } catch (CameraAccessException e) {
                FLog.e(TAG, "Error reading sensor orientation", e);
            }

            // ── Output file ───────────────────────────────────────────
            File outputFile = createOutputFile();
            if (safRecordingPfd != null && safRecordingUri != null) {
                lastRecordingUriString = safRecordingUri.toString();
            } else if (outputFile != null) {
                lastRecordingUriString = Uri.fromFile(outputFile).toString();
            } else {
                lastRecordingUriString = null;
            }

            // ── Watermark provider (shared WatermarkManager) ──────────
            watermarkManager = new com.fadcam.watermark.WatermarkManager(this, prefs);
            // DualCameraRecordingService doesn't have its own LocationHelper,
            // so WatermarkManager creates one internally.
            watermarkManager.initialize(null);
            WatermarkInfoProvider watermarkProvider = watermarkManager;
            
            // ── Build unified pipeline with DualCameraConfig ──────────
            if (safRecordingPfd != null) {
                // SAF mode: use FileDescriptor constructor
                FLog.d(TAG, "Building unified pipeline with FileDescriptor (SAF mode)");
                recordingPipeline = new GLRecordingPipeline(
                        this,
                        watermarkProvider,
                        videoWidth, videoHeight,
                        fps,
                        safRecordingPfd.getFileDescriptor(),
                        Long.MAX_VALUE,     // No segment splitting for dual cam
                        1,                  // Segment number
                        null,               // No segment callback
                        null,               // Preview surface (set later if available)
                        orientation,
                        sensorOrientation,
                        codec,
                        null, null,         // No location metadata for now
                        config);            // DualCameraConfig enables PiP
            } else {
                // Internal storage mode: use file path constructor
                if (outputFile == null) {
                    transitionToError("Cannot create output file");
                    return;
                }
                FLog.d(TAG, "Building unified pipeline with file path (internal mode)");
                recordingPipeline = new GLRecordingPipeline(
                        this,
                        watermarkProvider,
                        videoWidth, videoHeight,
                        fps,
                        outputFile.getAbsolutePath(),
                        Long.MAX_VALUE,     // No segment splitting for dual cam
                        1,                  // Segment number
                        null,               // No segment callback
                        null,               // Preview surface (set later if available)
                        orientation,
                        sensorOrientation,
                        codec,
                        null, null,         // No location metadata for now
                        config);            // DualCameraConfig enables PiP
            }

            if (safRecordingPfd != null && (safRecordingUri == null || !recordingPipeline.setNextOutput(
                    new GLRecordingPipeline.OutputTarget(null, safRecordingPfd.getFileDescriptor(),
                            safRecordingUri.toString(), 1)))) {
                transitionToError(getString(R.string.personal_recording_save_failed));
                return;
            }
            recordingPipeline.setAudioEnabledForSession(sessionAudioEnabled);
            recordingPipeline.prepareSurfaces();

            Surface primarySurface = recordingPipeline.getPrimaryCameraInputSurface();
            Surface secondarySurface = recordingPipeline.getSecondaryCameraInputSurface();
            if (primarySurface == null || !primarySurface.isValid()
                    || secondarySurface == null || !secondarySurface.isValid()) {
                transitionToError(getString(R.string.personal_home_recording_failed));
                return;
            }
            createCaptureSession(primaryCameraDevice, primarySurface, true);
            if (!isStopping) createCaptureSession(secondaryCameraDevice, secondarySurface, false);

        } catch (Exception e) {
            FLog.e(TAG, "Failed to start dual recording", e);
            transitionToError("Recording setup failed: " + e.getMessage());
        }
    }

    /**
     * Creates a Camera2 capture session that targets the given surface.
     */
    @SuppressWarnings("deprecation") // legacy createCaptureSession fallback for < API 28
    private void createCaptureSession(
            @NonNull CameraDevice camera,
            @NonNull Surface targetSurface,
            boolean isPrimary) {

        try {
            CaptureRequest.Builder builder =
                    camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(targetSurface);

            // AF + AE auto
            builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            builder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON);

            CameraCaptureSession.StateCallback callback = new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                    synchronized (DualCameraRecordingService.this) {
                        if (isStopping || camera != (isPrimary ? primaryCameraDevice : secondaryCameraDevice)) {
                            closeSession(session);
                            return;
                        }
                        if (isPrimary) {
                            primarySession = session;
                            primaryRequestBuilder = builder;
                        } else {
                            secondarySession = session;
                            secondaryRequestBuilder = builder;
                        }
                        try {
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                            onSessionConfigured(isPrimary);
                        } catch (CameraAccessException | RuntimeException e) {
                            FLog.e(TAG, "Failed to start dual capture request", e);
                            transitionToError(getString(R.string.personal_home_recording_failed));
                        }
                    }
                }

                @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    synchronized (DualCameraRecordingService.this) {
                        closeSession(session);
                        transitionToError(getString(R.string.personal_home_recording_failed));
                    }
                }
            };

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                camera.createCaptureSession(new android.hardware.camera2.params.SessionConfiguration(
                        android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                        Collections.singletonList(new android.hardware.camera2.params.OutputConfiguration(targetSurface)),
                        backgroundHandler::post, callback));
            } else {
                camera.createCaptureSession(Collections.singletonList(targetSurface), callback, backgroundHandler);
            }
        } catch (CameraAccessException | RuntimeException e) {
            FLog.e(TAG, "Error creating dual capture session", e);
            transitionToError(getString(R.string.personal_home_recording_failed));
        }
    }

    /** Tracks configured sessions; starts pipeline when both are ready. */
    private int sessionsConfigured = 0;

    private synchronized void onSessionConfigured(boolean isPrimary) {
        if (isStopping || state != DualCameraState.INITIALIZING) return;
        sessionsConfigured++;
        FLog.d(TAG, "Session configured (" + (isPrimary ? "primary" : "secondary")
                + ") — " + sessionsConfigured + "/2");

        if (sessionsConfigured < 2) return;

        // Both sessions ready — start encoding
        try {
            recordingPipeline.startRecording();
            state = DualCameraState.RECORDING;
            recordingStartTime = SystemClock.elapsedRealtime();
            prefs.setRecordingInProgress(true);
            publishedRecording = true;
            recordingHaptics.onRecordingStarted(this);

            // Save start time for timer recovery
            getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(Constants.PREF_RECORDING_START_TIME, recordingStartTime)
                    .commit();

            persistRecordingTimelineState();
            startDurationLimitSession();
            broadcastActionWithTiming(Constants.BROADCAST_ON_DUAL_RECORDING_STARTED);
            FLog.i(TAG, "✅ Dual camera recording started");
        } catch (Exception e) {
            FLog.e(TAG, "Failed to start pipeline encoding", e);
            transitionToError("Encoder start failed");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Returns the current timestamp formatted for watermark display.
     * Uses the same format as RecordingService for consistency.
     *
     * @return Formatted timestamp string.
     */
    // ── Thread management ─────────────────────────────────────────────

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("DualCamBg");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    /** Late Camera2 callbacks must still reach the service after onDestroy. */
    private void maybeStopBackgroundThread() {
        if (destroyed && !releasePending && pendingCameraOpens == 0 && backgroundThread != null) {
            backgroundThread.quitSafely();
            backgroundThread = null;
            backgroundHandler = null;
        }
    }

    // ── Wake lock ─────────────────────────────────────────────────────

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FadCam:DualCamRecording");
            wakeLock.acquire(4 * 60 * 60 * 1000L); // Max 4 hours
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception e) {
                FLog.w(TAG, "Error releasing wake lock", e);
            }
            wakeLock = null;
        }
    }

    private void closeSession(@Nullable CameraCaptureSession session) {
        if (session == null) return;
        try { session.stopRepeating(); }
        catch (Exception e) { FLog.w(TAG, "Error stopping dual capture requests", e); }
        try { session.close(); }
        catch (RuntimeException e) { FLog.w(TAG, "Error closing dual capture session", e); }
    }

    private void closeDevice(@Nullable CameraDevice device) {
        if (device == null) return;
        try { device.close(); }
        catch (RuntimeException e) {
            // No confirmed close means the GL inputs must stay owned until a later callback.
            FLog.e(TAG, "Error requesting dual camera close", e);
        }
    }

    private CompletableFuture<Void> registerCloseSignal(@Nullable CameraDevice device) {
        if (device == null) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> closed = new CompletableFuture<>();
        closingCameras.put(device, closed);
        return closed;
    }

    private void clearOwnedRecordingState() {
        if (!publishedRecording) return;
        publishedRecording = false;
        prefs.setRecordingInProgress(false);
        clearRecordingTimelineState();
    }

    private synchronized void releaseAllResources() {
        recordingHaptics.onRecordingStopped(this);
        if (durationLimitController != null) durationLimitController.stopSession();
        isStopping = true;
        releasePipelineAndCamerasAsync(null);
        state = DualCameraState.DISABLED;
        sessionsConfigured = 0;
        camerasOpened = 0;
    }

    private synchronized void releasePipelineAndCamerasAsync(@Nullable Consumer<Boolean> finished) {
        if (releasePending) return;
        final boolean wasRecording = publishedRecording;
        final GLRecordingPipeline pipeline = recordingPipeline;
        final ParcelFileDescriptor fd = safRecordingPfd;
        final CameraCaptureSession primary = primarySession;
        final CameraCaptureSession secondary = secondarySession;
        final CameraDevice primaryDevice = primaryCameraDevice;
        final CameraDevice secondaryDevice = secondaryCameraDevice;
        final com.fadcam.watermark.WatermarkManager watermark = watermarkManager;
        recordingPipeline = null;
        safRecordingPfd = null;
        safRecordingUri = null;
        safOutputFileName = null;
        primarySession = null;
        secondarySession = null;
        primaryRequestBuilder = null;
        secondaryRequestBuilder = null;
        primaryCameraDevice = null;
        secondaryCameraDevice = null;
        watermarkManager = null;

        if (pipeline == null && fd == null && primary == null && secondary == null
                && primaryDevice == null && secondaryDevice == null && watermark == null) {
            if (finished != null) mainHandler.post(() -> finished.accept(false));
            maybeStopBackgroundThread();
            return;
        }

        releasePending = true;
        CompletableFuture<Void> primaryClosed = registerCloseSignal(primaryDevice);
        CompletableFuture<Void> secondaryClosed = secondaryDevice == primaryDevice
                ? primaryClosed : registerCloseSignal(secondaryDevice);
        AtomicBoolean reported = new AtomicBoolean();
        Consumer<Boolean> report = saved -> {
            if (finished != null && reported.compareAndSet(false, true)) {
                mainHandler.post(() -> finished.accept(saved));
            }
        };
        Runnable closeWarning = () -> {
            if (!primaryClosed.isDone() || !secondaryClosed.isDone()) {
                FLog.w(TAG, "Dual Camera2 close not yet acknowledged; keeping GL inputs alive");
                report.accept(false);
            }
        };
        mainHandler.postDelayed(closeWarning, CAMERA_CLOSE_WARNING_MS);

        new Thread(() -> {
            closeSession(primary);
            if (secondary != primary) closeSession(secondary);
            closeDevice(primaryDevice);
            if (secondaryDevice != primaryDevice) closeDevice(secondaryDevice);

            // The last actual onClosed schedules finalization on a separate worker, never on Camera2/UI.
            CompletableFuture.allOf(primaryClosed, secondaryClosed).thenRunAsync(() -> {
                boolean success = wasRecording && pipeline != null;
                try {
                    if (pipeline != null) pipeline.stopRecording();
                } catch (RuntimeException e) {
                    success = false;
                    FLog.e(TAG, "Error stopping dual pipeline", e);
                } finally {
                    if (fd != null) {
                        try { fd.close(); }
                        catch (Exception e) {
                            success = false;
                            FLog.e(TAG, "Error closing dual recording descriptor", e);
                        }
                    }
                    if (watermark != null) {
                        try { watermark.destroy(); }
                        catch (RuntimeException e) { FLog.w(TAG, "Error closing dual watermark", e); }
                    }
                    mainHandler.removeCallbacks(closeWarning);
                    synchronized (DualCameraRecordingService.this) {
                        releasePending = false;
                        maybeStopBackgroundThread();
                    }
                }
                report.accept(success);
            });
        }, "DualCameraClose").start();
    }

    private void initializeDurationLimitController() {
        durationLimitController = new RecordingDurationLimitController(
                new RecordingDurationLimitController.Scheduler() {
                    @Override
                    public long elapsedRealtime() {
                        return SystemClock.elapsedRealtime();
                    }

                    @Override
                    public void postDelayed(Runnable runnable, long delayMs) {
                        mainHandler.postDelayed(runnable, delayMs);
                    }

                    @Override
                    public void removeCallbacks(Runnable runnable) {
                        mainHandler.removeCallbacks(runnable);
                    }
                },
                prefs::getMaximumRecordingDurationMs,
                () -> {
                    FLog.i(TAG, "Maximum recording duration reached; stopping current dual recording");
                    handleStopDualRecording();
                });

        // Haptic tick for the final 10 seconds of the countdown (gated by the
        // master + "Buttons & controls" toggles inside Utils).
        durationLimitController.setRemainingTickListener(remainingSeconds -> {
            try {
                com.fadcam.Utils.vibrateCountdownTick(DualCameraRecordingService.this, remainingSeconds);
            } catch (Exception ignored) {
            }
        });

        durationPreferenceListener = (preferences, key) -> {
            boolean customValueKey =
                    SharedPreferencesManager.PREF_MAX_RECORDING_DURATION_CUSTOM_MINUTES.equals(key)
                            || SharedPreferencesManager.PREF_MAX_RECORDING_DURATION_CUSTOM_SECONDS.equals(key);
            if (!SharedPreferencesManager.PREF_MAX_RECORDING_DURATION_OPTION.equals(key)
                    && !customValueKey) {
                return;
            }
            if (customValueKey
                    && !MaximumRecordingDuration.OPTION_CUSTOM.equals(
                    prefs.getMaximumRecordingDurationOption())) {
                return;
            }
            if (durationLimitController.onLimitChanged()) {
                long limitMs = prefs.getMaximumRecordingDurationMs();
                if (limitMs == 0L) {
                    FLog.i(TAG, "Maximum recording duration disabled for current dual session");
                } else {
                    FLog.i(TAG, "Maximum recording duration updated for current dual session: "
                            + limitMs + " ms");
                }
            }
        };
        prefs.sharedPreferences.registerOnSharedPreferenceChangeListener(durationPreferenceListener);
    }

    private void startDurationLimitSession() {
        if (durationLimitController == null) {
            return;
        }
        long limitMs = durationLimitController.startSession();
        if (limitMs == 0L) {
            FLog.d(TAG, "Maximum recording duration is disabled for this dual session");
        } else {
            FLog.i(TAG, "Maximum recording duration scheduled for current dual session: "
                    + limitMs + " ms");
        }
    }

    private void releaseDurationLimitController() {
        if (durationLimitController != null) {
            durationLimitController.stopSession();
        }
        if (prefs != null && durationPreferenceListener != null) {
            prefs.sharedPreferences.unregisterOnSharedPreferenceChangeListener(
                    durationPreferenceListener);
            durationPreferenceListener = null;
        }
    }

    // ── Error handling ────────────────────────────────────────────────

    private synchronized void transitionToError(@NonNull String reason) {
        if (isStopping) return;
        DualCameraState previousState = state;
        FLog.e(TAG, "Dual camera error: " + reason);
        state = DualCameraState.ERROR;
        broadcastError(reason);
        if (previousState == DualCameraState.RECORDING || previousState == DualCameraState.PAUSED) {
            broadcastRecordingComplete(false);
        }

        mainHandler.post(() ->
                Toast.makeText(getApplicationContext(), reason, Toast.LENGTH_LONG).show());

        // Clean up and stop
        releaseAllResources();
        clearOwnedRecordingState();
        releaseWakeLock();
        stopSelf();
    }

    // ── Output file creation ────────────────────────────────────────────

    @Nullable
    private File createOutputFile() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String filename = "DualCam_" + timestamp + "." + Constants.RECORDING_FILE_EXTENSION;
        
        String storageMode = prefs.getStorageMode();
        
        if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode)) {
            try {
                String treeUriString = prefs.getCustomStorageUri();
                if (treeUriString == null || treeUriString.isEmpty()) {
                    FLog.e(TAG, "No custom storage location configured");
                    return null;
                }
                DocumentFile treeDoc = com.fadcam.utils.RecordingStoragePaths.getSafCameraSourceDir(
                        this, treeUriString,
                        com.fadcam.utils.RecordingStoragePaths.CameraSource.DUAL, true);
                if (treeDoc == null || !treeDoc.exists() || !treeDoc.canWrite()) {
                    FLog.e(TAG, "Cannot write to custom storage location");
                    return null;
                }
                DocumentFile videoFile = treeDoc.createFile("video/mp4", filename);
                if (videoFile == null) {
                    FLog.e(TAG, "Failed to create SAF file: " + filename);
                    return null;
                }
                safRecordingPfd = getContentResolver().openFileDescriptor(videoFile.getUri(), "w");
                safRecordingUri = videoFile.getUri();
                safOutputFileName = filename;
                FLog.d(TAG, "SAF mode: created file descriptor for " + filename);
                return null;
            } catch (Exception e) {
                FLog.e(TAG, "Error creating SAF file", e);
                return null;
            }
        } else {
            File videoDir = com.fadcam.utils.RecordingStoragePaths.getInternalCameraSourceDir(
                    this, com.fadcam.utils.RecordingStoragePaths.CameraSource.DUAL, true);
            if (videoDir == null) {
                FLog.e(TAG, "Cannot create recording directory for dual camera");
                return null;
            }
            safRecordingPfd = null;
            safRecordingUri = null;
            safOutputFileName = null;
            return new File(videoDir, filename);
        }
    }

    // ── Notification ───────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            String channelName = prefs.getNotificationChannelName();
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    CHANNEL_ID, channelName, android.app.NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            channel.enableVibration(false);
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        } catch (IllegalArgumentException e) {
            FLog.w(TAG, "Notification channel already exists, reusing: " + e.getMessage());
        }
    }

    private NotificationCompat.Builder buildDualCameraNotification() {
        String title = prefs.getNotificationTitle();
        if (title == null || title.isEmpty()) {
            title = getString(R.string.notification_video_recording);
        }
        String preset = prefs.getNotificationPreset();
        String text = prefs.getNotificationText(false);
        if (text == null || text.isEmpty()) {
            text = "Dual camera recording…";
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        int smallIconResId;
        switch (preset) {
            case SharedPreferencesManager.NOTIFICATION_PRESET_SYSTEM_UPDATE:
            case SharedPreferencesManager.NOTIFICATION_PRESET_DOWNLOADING:
                smallIconResId = android.R.drawable.stat_sys_download;
                break;
            case SharedPreferencesManager.NOTIFICATION_PRESET_SYNCING:
                smallIconResId = android.R.drawable.stat_notify_sync;
                break;
            default:
                smallIconResId = R.drawable.ic_notification_icon;
                break;
        }
        builder.setSmallIcon(smallIconResId);

        if (!SharedPreferencesManager.NOTIFICATION_PRESET_DEFAULT.equals(preset)) {
            Intent emptyIntent = new Intent();
            PendingIntent emptyPi = PendingIntent.getActivity(this, 0, emptyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.setContentIntent(emptyPi);
        }

        if (!prefs.isNotificationStopButtonHidden()) {
            Intent stopIntent = new Intent(this, DualCameraRecordingService.class);
            stopIntent.setAction(Constants.INTENT_ACTION_STOP_DUAL_RECORDING);
            PendingIntent stopPi = PendingIntent.getService(this, 2020, stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(R.drawable.stop_rounded, getString(R.string.stop_recording), stopPi);
        }

        return builder;
    }

    private void startForegroundNotification() {
        createNotificationChannel();
        Notification notification = buildDualCameraNotification().build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            if (sessionAudioEnabled) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIFICATION_ID, notification, type);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updatePausedNotification() {
        String text = prefs.getNotificationText(true);
        if (text != null && !text.isEmpty()) {
            NotificationCompat.Builder builder = buildDualCameraNotification()
                    .setContentText(text);
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, builder.build());
        }
    }

    private void updateResumedNotification() {
        startForegroundNotification();
    }

    // ── Timing persistence ────────────────────────────────────────────

    private void persistRecordingTimelineState() {
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(Constants.PREF_RECORDING_START_TIME, recordingStartTime)
            .putLong(Constants.PREF_RECORDING_PAUSE_STARTED_AT, pauseStartedAt)
            .putLong(Constants.PREF_RECORDING_ACCUMULATED_PAUSED_DURATION, accumulatedPausedDurationMs)
            .commit();
    }

    private void clearRecordingTimelineState() {
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(Constants.PREF_RECORDING_START_TIME)
            .remove(Constants.PREF_RECORDING_PAUSE_STARTED_AT)
            .remove(Constants.PREF_RECORDING_ACCUMULATED_PAUSED_DURATION)
            .apply();
        FLog.d(TAG, "Cleared dual recording timeline state");
    }

    // ── Broadcasting ──────────────────────────────────────────────────

    /**
     * Sends a regular broadcast (not LocalBroadcastManager) so that receivers
     * registered with {@code context.registerReceiver()} in HomeFragment
     * can receive the events — matching the pattern used by RecordingService.
     */
    private void broadcastAction(@NonNull String action) {
        Intent intent = new Intent(action);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    /** Sends a broadcast with recording timing extras attached. */
    private void broadcastActionWithTiming(@NonNull String action) {
        Intent intent = new Intent(action);
        intent.setPackage(getPackageName());
        intent.putExtra(Constants.INTENT_EXTRA_RECORDING_START_TIME, recordingStartTime);
        intent.putExtra(Constants.INTENT_EXTRA_RECORDING_PAUSE_STARTED_AT, pauseStartedAt);
        intent.putExtra(Constants.INTENT_EXTRA_RECORDING_ACCUMULATED_PAUSED_DURATION, accumulatedPausedDurationMs);
        sendBroadcast(intent);
    }

    private void broadcastError(@NonNull String reason) {
        Intent intent = new Intent(Constants.BROADCAST_ON_DUAL_CAMERA_ERROR);
        intent.setPackage(getPackageName());
        intent.putExtra("error_reason", reason);
        sendBroadcast(intent);
    }

    private void broadcastRecordingComplete(boolean success) {
        try {
            Intent recordingCompleteIntent = new Intent(Constants.ACTION_RECORDING_COMPLETE);
            recordingCompleteIntent.setPackage(getPackageName());
            recordingCompleteIntent.putExtra(Constants.EXTRA_RECORDING_SUCCESS, success);
            if (lastRecordingUriString != null && !lastRecordingUriString.isEmpty()) {
                recordingCompleteIntent.putExtra(Constants.EXTRA_RECORDING_URI_STRING, lastRecordingUriString);
            }
            sendBroadcast(recordingCompleteIntent);
            FLog.d(TAG, "Broadcasted ACTION_RECORDING_COMPLETE for dual recording. success=" + success);
        } catch (Exception e) {
            FLog.e(TAG, "Error broadcasting ACTION_RECORDING_COMPLETE for dual recording", e);
        }
    }
}
