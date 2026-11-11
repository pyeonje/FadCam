package com.fadcam.ui;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import com.fadcam.CameraType;
import com.fadcam.Constants;
import com.fadcam.FLog;
import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.RecordingState;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.Utils;
import com.fadcam.dualcam.service.DualCameraRecordingService;
import com.fadcam.services.RecordingService;
import com.fadcam.utils.ServiceUtils;
import com.fadcam.utils.ServiceStartPolicy;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.Locale;

/** Simple personal recording screen. Only an explicit record-button tap starts capture. */
public class PersonalHomeFragment extends Fragment {
    private static final String TAG = "PersonalHome";
    private static final int MODE_PHOTO = 0, MODE_VIDEO = 1, MODE_PRO_VIDEO = 2;
    private static final String SAVED_CAMERA_MODE = "personal.camera.mode";
    private int cameraMode = MODE_PRO_VIDEO;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferencesManager prefs;
    private RecordingState state;
    private long startTime, pauseTime, pausedDuration;
    private boolean registered;
    private TextView timer, status, summary, hint;
    private MaterialButton recordButton, pauseButton, cameraButton;
    private String pendingAction;
    private long pendingIssuedAt, frozenStopElapsed;
    private boolean lastCleanup;
    private TextureView preview;
    private PersonalCameraControls cameraControls;
    private Integer previousRequestedOrientation, idlePreviewDisplayRotation;
    private int pendingStartDisplayOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private View previewClock;
    private Surface previewSurface;
    private Class<?> previewOwner;
    private Surface attachedSurface;
    private int attachedWidth, attachedHeight;
    private int attachedDisplayRotation = -1;
    private boolean previewForeground;
    private boolean previewOnlyOwned;
    private boolean previewStartPending;
    private boolean previewStopPending;
    private boolean previewFailed, cameraLandscape;
    private RecentMediaLauncher recentMedia;
    private final Runnable previewStartTimeout = () -> {
        if (previewStartPending) {
            previewStartPending = false;
            previewFailed = true;
            stopIdlePreview();
            FLog.w(TAG, "Idle preview did not start; retry on next foreground entry");
        }
    };

    private final TextureView.SurfaceTextureListener previewListener = new TextureView.SurfaceTextureListener() {
        @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture texture, int width, int height) {
            releasePreviewSurface();
            previewSurface = new Surface(texture);
            syncPreview();
        }

        @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture texture, int width, int height) {
            syncPreview();
        }

        @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture texture) {
            releasePreviewSurface();
            return true;
        }

        @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture texture) { }
    };

    private final ActivityResultLauncher<String[]> permissions = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (hasCapturePermissions()) {
                    completeSetup();
                    Toast.makeText(requireContext(), R.string.personal_home_permissions_ready, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(requireContext(), R.string.personal_home_permissions_needed, Toast.LENGTH_LONG).show();
                }
                render();
            });

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (timer == null) return;
            renderTimer();
            if (pendingAction != null && SystemClock.elapsedRealtime() - pendingIssuedAt >= 10_000L) {
                // Missing/failed service acknowledgement must never leave optimistic UI forever.
                pendingAction = null;
                state = null;
                queryState();
                render();
            }
            if (lastCleanup != RecordingService.isCameraCleanupInProgress()) render();
            if(cameraControls!=null)cameraControls.refreshLiveValues();
            // A recording stop broadcasts before encoder cleanup finishes. Resume idle
            // preview only after the real service reports cleanup/cooldown complete.
            if (state == RecordingState.NONE) {
                syncIdleOrientation(getResources().getConfiguration());
                syncPreview();
            }
            handler.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver recordingReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (timer == null) return;
            String action = intent.getAction();
            FLog.d(TAG, "Received recording event: " + action);
            if (Constants.BROADCAST_ON_PREVIEW_ONLY_STARTED.equals(action)) {
                if (previewStopPending) return;
                previewStartPending = false;
                handler.removeCallbacks(previewStartTimeout);
                if (!previewForeground || isHidden()) stopIdlePreview();
                else syncPreview();
                return;
            }
            if (Constants.BROADCAST_ON_PREVIEW_ONLY_STOPPED.equals(action)) {
                // An old session can finish after the next one has started.
                if (RecordingService.hasPreviewOnlySession()) return;
                boolean expectedStop = previewStopPending;
                previewStopPending = false;
                previewStartPending = false;
                handler.removeCallbacks(previewStartTimeout);
                // A stop during recording startup is the normal handoff, not a failure.
                if (!expectedStop && previewOnlyOwned && state == RecordingState.NONE
                        && !RecordingService.hasActiveSession()) previewFailed = true;
                return;
            }
            if (Constants.BROADCAST_ON_RECORDING_STATE_CALLBACK.equals(action)) {
                // An idle single-camera service must not overwrite an active dual session.
                if (DualCameraRecordingService.hasActiveSession()) return;
                RecordingState reported = Utils.getSerializableExtraCompat(intent,
                        Constants.INTENT_EXTRA_RECORDING_STATE, RecordingState.class);
                if (reported == null) {
                    FLog.w(TAG, "Recording state callback is missing its enum state");
                    return;
                }
                if (reported == RecordingState.NONE && state != RecordingState.NONE) frozenStopElapsed = elapsedRecordingMillis();
                state = reported;
                FLog.d(TAG, "Recording state synchronized: " + state);
            } else if (Constants.BROADCAST_ON_RECORDING_STOPPED.equals(action)
                    || Constants.BROADCAST_ON_DUAL_RECORDING_STOPPED.equals(action)) {
                if (state != RecordingState.NONE) frozenStopElapsed = elapsedRecordingMillis();
                state = RecordingState.NONE;
                previewFailed = false;
                previewStartPending = false;
            } else if (Constants.BROADCAST_ON_RECORDING_PAUSED.equals(action)
                    || Constants.BROADCAST_ON_DUAL_RECORDING_PAUSED.equals(action)) {
                state = RecordingState.PAUSED;
            } else if (Constants.BROADCAST_ON_RECORDING_STARTED.equals(action)
                    || Constants.BROADCAST_ON_DUAL_RECORDING_STARTED.equals(action)
                    || Constants.BROADCAST_ON_RECORDING_RESUMED.equals(action)
                    || Constants.BROADCAST_ON_DUAL_RECORDING_RESUMED.equals(action)) {
                // Single-camera STARTED also announces STARTING before its
                // encoder/session is ready. Honor the service's actual state.
                RecordingState reported = Utils.getSerializableExtraCompat(intent,
                        Constants.INTENT_EXTRA_RECORDING_STATE, RecordingState.class);
                state = reported != null ? reported : RecordingState.IN_PROGRESS;
                previewStartPending = false;
                previewFailed = false;
            } else {
                pendingAction = null;
                state = null;
                Toast.makeText(context, R.string.personal_home_recording_failed, Toast.LENGTH_LONG).show();
                queryState();
                return;
            }
            acknowledgePendingAction();
            readTimeline(intent);
            render();
        }
    };

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_personal_home, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        prefs = SharedPreferencesManager.getInstance(requireContext());
        cameraMode = Math.max(MODE_PHOTO, Math.min(MODE_PRO_VIDEO,
                savedInstanceState != null ? savedInstanceState.getInt(SAVED_CAMERA_MODE, MODE_PRO_VIDEO)
                        : prefs.sharedPreferences.getInt(SAVED_CAMERA_MODE, MODE_PRO_VIDEO)));
        com.fadcam.utils.PersonalProSettings.setEnabled(prefs,cameraMode==MODE_PRO_VIDEO);
        timer = view.findViewById(R.id.personal_home_timer);
        status = view.findViewById(R.id.personal_home_status);
        summary = view.findViewById(R.id.personal_home_summary);
        hint = view.findViewById(R.id.personal_home_hint);
        recordButton = view.findViewById(R.id.personal_home_record);
        pauseButton = view.findViewById(R.id.personal_home_pause);
        cameraButton = view.findViewById(R.id.personal_home_camera);
        preview = view.findViewById(R.id.personal_home_preview);
        previewClock = view.findViewById(R.id.personal_home_clock);
        preview.setSurfaceTextureListener(previewListener);
        cameraControls = new PersonalCameraControls(requireContext(), view, prefs, this::cameraControlsReady,
                () -> state == RecordingState.NONE && !RecordingService.hasActiveSession(), () -> {
                    previewFailed = false; previewOnlyOwned = true; stopIdlePreview(); render();
                });
        recordButton.setOnClickListener(v -> performRecordAction());
        pauseButton.setOnClickListener(v -> performPauseAction());
        cameraButton.setOnClickListener(v -> chooseCamera());
        int[] modeIds = {R.id.personal_camera_mode_photo, R.id.personal_camera_mode_video, R.id.personal_camera_mode_pro_video};
        for (int mode = MODE_PHOTO; mode <= MODE_PRO_VIDEO; mode++) {
            final int selectedMode = mode;
            view.findViewById(modeIds[mode]).setOnClickListener(v -> selectCameraMode(selectedMode));
        }
        view.findViewById(R.id.personal_home_settings).setOnClickListener(v -> openSettings());
        recentMedia=new RecentMediaLauncher(this,view.findViewById(R.id.personal_home_videos));
        view.findViewById(R.id.personal_home_videos).setOnClickListener(v -> {
            if(RecordingService.isRecordingFinalizing() || "STOP".equals(pendingAction))return;
            if(state==RecordingState.PAUSED) return;
            if(state==RecordingState.IN_PROGRESS) {
                Class<?> owner=DualCameraRecordingService.hasActiveSession()?DualCameraRecordingService.class:RecordingService.class;
                requireContext().startService(new Intent(requireContext(),owner).setAction(Constants.INTENT_ACTION_CAPTURE_PHOTO));
            } else if(recentMedia!=null)recentMedia.open();
        });
        view.findViewById(R.id.personal_home_shortcuts).setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) requireActivity()).switchFragment(4, true);
                OverlayNavUtil.show(requireActivity(), new PersonalAppearanceFragment(), "PersonalAppearanceFragment");
            }
        });
        reflowCameraControls(getResources().getConfiguration());
        render();
    }

    @Override public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(SAVED_CAMERA_MODE, cameraMode);
    }

    private void selectCameraMode(int mode) {
        if (state != RecordingState.NONE || pendingAction != null
                || RecordingService.isCameraCleanupInProgress() || RecordingService.hasActiveSession()
                || DualCameraRecordingService.hasActiveSession()) return;
        if (cameraControls != null) cameraControls.cancelGesture();
        cameraMode = mode;
        prefs.sharedPreferences.edit().putInt(SAVED_CAMERA_MODE,mode).apply();
        com.fadcam.utils.PersonalProSettings.setEnabled(prefs,mode==MODE_PRO_VIDEO);
        if(cameraControlsReady()) requireContext().startService(com.fadcam.RecordingControlIntents.applyProSettings(requireContext()));
        render();
    }

    private boolean hasCapturePermissions() {
        return cameraMode == MODE_PHOTO
                ? getContext() != null && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED
                : hasRecordingPermissions();
    }

    private void performPhotoAction() {
        if (state != RecordingState.NONE || pendingAction != null || RecordingService.isCameraCleanupInProgress()) return;
        if (!hasCapturePermissions()) {
            permissions.launch(new String[]{Manifest.permission.CAMERA});
            return;
        }
        if (!cameraControlsReady()) {
            syncPreview();
            Toast.makeText(requireContext(), R.string.photo_capture_preview_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        // Reuse the existing preview-only photo path; never start an encoder for a photo.
        try {
            requireContext().startService(new Intent(requireContext(), RecordingService.class)
                    .setAction(Constants.INTENT_ACTION_CAPTURE_PHOTO));
        } catch (RuntimeException error) {
            Toast.makeText(requireContext(), R.string.photo_capture_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        String[] actions = {Constants.BROADCAST_ON_RECORDING_STATE_CALLBACK,
                Constants.BROADCAST_ON_RECORDING_STARTED, Constants.BROADCAST_ON_RECORDING_STOPPED,
                Constants.BROADCAST_ON_RECORDING_PAUSED, Constants.BROADCAST_ON_RECORDING_RESUMED,
                Constants.BROADCAST_ON_DUAL_RECORDING_STARTED, Constants.BROADCAST_ON_DUAL_RECORDING_STOPPED,
                Constants.BROADCAST_ON_DUAL_RECORDING_PAUSED, Constants.BROADCAST_ON_DUAL_RECORDING_RESUMED,
                Constants.ACTION_RECORDING_FAILED, Constants.BROADCAST_ON_DUAL_CAMERA_ERROR,
                Constants.BROADCAST_ON_PREVIEW_ONLY_STARTED, Constants.BROADCAST_ON_PREVIEW_ONLY_STOPPED};
        for (String action : actions) filter.addAction(action);
        ContextCompat.registerReceiver(requireContext(), recordingReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        registered = true;
        FLog.d(TAG, "Registered private recording status receiver");
        if (hasRecordingPermissions()) completeSetup();
        queryState();
        handler.post(tick);
    }

    @Override public void onResume() {
        super.onResume();
        previewForeground = true;
        previewFailed = false;
        // The stop acknowledgement may have arrived while this receiver was
        // unregistered in the background. Reconcile the real service teardown.
        if (!RecordingService.hasPreviewOnlySession() && !RecordingService.isCameraCleanupInProgress()) previewStopPending = false;
        if (timer != null) { queryState(); render(); if(recentMedia!=null)recentMedia.refresh(); }
    }

    @Override public void onPause() {
        previewForeground = false;
        restoreOrientationPolicy();
        if (cameraControls != null) cameraControls.cancelGesture();
        setPreviewScreenAwake(false);
        detachPreview();
        stopIdlePreview();
        if (preview != null) preview.setVisibility(View.INVISIBLE);
        super.onPause();
    }

    @Override public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            restoreOrientationPolicy();
            if (cameraControls != null) cameraControls.cancelGesture();
            setPreviewScreenAwake(false);
            detachPreview();
            stopIdlePreview();
            if (preview != null) preview.setVisibility(View.INVISIBLE);
        } else {
            previewFailed = false;
        }
        if (!hidden && timer != null) { queryState(); render(); if(recentMedia!=null)recentMedia.refresh(); }
    }

    @Override public void onStop() {
        previewForeground = false;
        restoreOrientationPolicy();
        if (cameraControls != null) cameraControls.cancelGesture();
        setPreviewScreenAwake(false);
        detachPreview();
        stopIdlePreview();
        if (registered) {
            requireContext().unregisterReceiver(recordingReceiver);
            registered = false;
        }
        handler.removeCallbacksAndMessages(null);
        super.onStop();
    }

    @Override public void onDestroyView() {
        if(recentMedia!=null)recentMedia.close();
        recentMedia=null;
        restoreOrientationPolicy();
        if (cameraControls != null) { cameraControls.release(); cameraControls = null; }
        setPreviewScreenAwake(false);
        stopIdlePreview();
        releasePreviewSurface();
        if (preview != null) preview.setSurfaceTextureListener(null);
        preview = null;
        previewClock = null;
        handler.removeCallbacksAndMessages(null);
        timer = status = summary = hint = null;
        recordButton = pauseButton = cameraButton = null;
        super.onDestroyView();
    }

    private boolean hasRecordingPermissions() {
        return com.fadcam.utils.RecordingPermissions.canRecord(getContext(), prefs.isRecordAudioEnabled());
    }

    private void completeSetup() {
        prefs.sharedPreferences.edit()
                .putBoolean(Constants.COMPLETED_ONBOARDING_KEY, true)
                .putBoolean(Constants.FIRST_INSTALL_CHECKED_KEY, true).apply();
    }

    private void performRecordAction() {
        if (cameraMode == MODE_PHOTO && state == RecordingState.NONE) { performPhotoAction(); return; }
        if (pendingAction != null || RecordingService.isCameraCleanupInProgress()) return;
        if (!hasRecordingPermissions()) {
            ArrayList<String> requested = new ArrayList<>();
            requested.add(Manifest.permission.CAMERA);
            if(prefs.isRecordAudioEnabled())requested.add(Manifest.permission.RECORD_AUDIO);
            permissions.launch(requested.toArray(new String[0]));
            return; // Granting permission never starts a recording automatically.
        }
        if (state == null) { queryState(); return; }
        if (state != RecordingState.NONE && state != RecordingState.IN_PROGRESS && state != RecordingState.PAUSED) return;
        completeSetup();
        Intent action;
        if (state == RecordingState.NONE) {
            boolean dual = prefs.getCameraSelection() != null && prefs.getCameraSelection().isDual();
            action = dual
                    ? new Intent(requireContext(), DualCameraRecordingService.class).setAction(Constants.INTENT_ACTION_START_DUAL_RECORDING)
                    : new Intent(requireContext(), RecordingService.class).setAction(Constants.INTENT_ACTION_START_RECORDING);
            if(!dual) {
                pendingStartDisplayOrientation=RecordingService.snapshotDisplayOrientation(requireContext());
                action.putExtra(RecordingService.EXTRA_SESSION_DISPLAY_ORIENTATION,pendingStartDisplayOrientation);
            }
            // Supply the foreground surface on the explicit start tap. This uses the
            // existing recording pipeline; it never starts a separate preview camera.
            if (!dual && previewForeground && !isHidden()
                    && previewSurface != null && previewSurface.isValid()) {
                action.putExtra("SURFACE", previewSurface);
                action.putExtra("SURFACE_WIDTH", preview.getWidth());
                action.putExtra("SURFACE_HEIGHT", preview.getHeight());
            }
            state = RecordingState.STARTING;
            pendingAction = "START";
            frozenStopElapsed = 0L;
            previewStartPending = false;
            handler.removeCallbacks(previewStartTimeout);
            startTime = pauseTime = pausedDuration = 0L;
        } else {
            pendingAction = "STOP";
            frozenStopElapsed = elapsedRecordingMillis();
            boolean dual = DualCameraRecordingService.hasActiveSession();
            action = dual
                    ? new Intent(requireContext(), DualCameraRecordingService.class).setAction(Constants.INTENT_ACTION_STOP_DUAL_RECORDING)
                    : new Intent(requireContext(), RecordingService.class).setAction(Constants.INTENT_ACTION_STOP_RECORDING);
        }
        pendingIssuedAt = SystemClock.elapsedRealtime();
        try {
            ServiceStartPolicy.startRecordingAction(requireContext(), action);
            if (Constants.INTENT_ACTION_START_RECORDING.equals(action.getAction()) && action.hasExtra("SURFACE")) {
                // Remember the start-intent surface too, so leaving immediately after
                // tapping Start still detaches it before the first state callback.
                previewOwner = RecordingService.class;
                attachedSurface = previewSurface;
                attachedWidth = attachedHeight = 0;
            }
        } catch (RuntimeException e) {
            pendingAction = null;
            state = null;
            Toast.makeText(requireContext(), R.string.personal_home_recording_failed, Toast.LENGTH_LONG).show();
            queryState();
        }
        render();
    }

    private void performPauseAction() {
        if (pendingAction != null || RecordingService.isCameraCleanupInProgress()
                || (state != RecordingState.IN_PROGRESS && state != RecordingState.PAUSED)) return;
        boolean resume = state == RecordingState.PAUSED;
        boolean dual = DualCameraRecordingService.hasActiveSession();
        String action = dual ? (resume ? Constants.INTENT_ACTION_RESUME_DUAL_RECORDING : Constants.INTENT_ACTION_PAUSE_DUAL_RECORDING)
                : (resume ? Constants.INTENT_ACTION_RESUME_RECORDING : Constants.INTENT_ACTION_PAUSE_RECORDING);
        pendingAction = resume ? "RESUME" : "PAUSE";
        pendingIssuedAt = SystemClock.elapsedRealtime();
        try {
            ServiceStartPolicy.startRecordingAction(requireContext(), new Intent(requireContext(),
                    dual ? DualCameraRecordingService.class : RecordingService.class).setAction(action));
        } catch (RuntimeException error) {
            pendingAction = null;
            state = null;
            queryState();
            Toast.makeText(requireContext(), R.string.personal_home_recording_failed, Toast.LENGTH_LONG).show();
        }
        render();
    }

    private void acknowledgePendingAction() {
        if (("START".equals(pendingAction) && (state == RecordingState.IN_PROGRESS || state == RecordingState.PAUSED))
                || ("STOP".equals(pendingAction) && state == RecordingState.NONE)
                || ("PAUSE".equals(pendingAction) && state == RecordingState.PAUSED)
                || ("RESUME".equals(pendingAction) && state == RecordingState.IN_PROGRESS)) pendingAction = null;
    }

    private void chooseCamera() {
        if (state != RecordingState.NONE) return;
        CameraType selected = prefs.getCameraSelection();
        String[] choices = {getString(R.string.personal_home_back), getString(R.string.personal_home_front), getString(R.string.usb_camera_title)};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.personal_home_camera_title)
                .setSingleChoiceItems(choices, selected == CameraType.FRONT ? 1 : 0, (dialog, which) -> {
                    if (which == 2) {
                        stopIdlePreview();
                        dialog.dismiss();
                        startActivity(new Intent(requireContext(), UsbCameraActivity.class));
                        return;
                    }
                    prefs.sharedPreferences.edit().putString(Constants.PREF_CAMERA_SELECTION,
                            (which == 1 ? CameraType.FRONT : CameraType.BACK).name()).apply();
                    previewFailed = false;
                    if (RecordingService.hasPreviewOnlySession()) {
                        requireContext().startService(new Intent(requireContext(), RecordingService.class)
                                .setAction(Constants.INTENT_ACTION_SWITCH_CAMERA)
                                .putExtra(Constants.INTENT_EXTRA_CAMERA_TYPE_SWITCH,
                                        (which == 1 ? CameraType.FRONT : CameraType.BACK).name()));
                    }
                    render();
                    dialog.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void openSettings() {
        if (getActivity() instanceof MainActivity) ((MainActivity) requireActivity()).switchFragment(4, true);
    }

    private void queryState() {
        if (!isAdded()) return;
        if (DualCameraRecordingService.hasActiveSession()) {
            readTimeline(null);
            state = DualCameraRecordingService.getSessionState();
            acknowledgePendingAction();
            render();
            return;
        }
        // Querying the actual service avoids treating stale preference flags as current status.
        try {
            FLog.d(TAG, "Requesting recording service state");
            requireContext().startService(new Intent(requireContext(), RecordingService.class)
                    .setAction(Constants.BROADCAST_ON_RECORDING_STATE_REQUEST));
        } catch (RuntimeException e) {
            FLog.w(TAG, "Could not request recording state", e);
            state = null;
            render();
        }
    }

    private void readTimeline(@Nullable Intent intent) {
        startTime = intent != null ? intent.getLongExtra(Constants.INTENT_EXTRA_RECORDING_START_TIME,
                prefs.sharedPreferences.getLong(Constants.PREF_RECORDING_START_TIME, 0L))
                : prefs.sharedPreferences.getLong(Constants.PREF_RECORDING_START_TIME, 0L);
        pauseTime = intent != null ? intent.getLongExtra(Constants.INTENT_EXTRA_RECORDING_PAUSE_STARTED_AT,
                prefs.sharedPreferences.getLong(Constants.PREF_RECORDING_PAUSE_STARTED_AT, 0L))
                : prefs.sharedPreferences.getLong(Constants.PREF_RECORDING_PAUSE_STARTED_AT, 0L);
        pausedDuration = intent != null ? intent.getLongExtra(Constants.INTENT_EXTRA_RECORDING_ACCUMULATED_PAUSED_DURATION,
                prefs.sharedPreferences.getLong(Constants.PREF_RECORDING_ACCUMULATED_PAUSED_DURATION, 0L))
                : prefs.sharedPreferences.getLong(Constants.PREF_RECORDING_ACCUMULATED_PAUSED_DURATION, 0L);
    }

    private void render() {
        if (timer == null || prefs == null) return;
        updateOrientationPolicy();
        syncIdleOrientation(getResources().getConfiguration());
        boolean active = state != null && state != RecordingState.NONE;
        boolean capturePermissions = hasCapturePermissions();
        boolean finishing = "STOP".equals(pendingAction) || RecordingService.isRecordingFinalizing();
        lastCleanup = RecordingService.isCameraCleanupInProgress();
        int statusLabel = finishing ? R.string.personal_home_saving : state == null ? R.string.personal_home_syncing
                : state == RecordingState.STARTING ? R.string.personal_home_starting
                : state == RecordingState.WAITING_FOR_CAMERA ? R.string.personal_home_waiting
                : state == RecordingState.PAUSED ? R.string.personal_home_paused
                : active ? R.string.personal_home_recording : cameraModeLabel();
        status.setText(statusLabel);
        boolean stableRecording=hasRecordingPermissions() && !finishing && (state==RecordingState.IN_PROGRESS || state==RecordingState.PAUSED);
        status.setVisibility(!stableRecording && (active || finishing || state==null || !capturePermissions)?View.VISIBLE:View.GONE);
        androidx.core.view.ViewCompat.setStateDescription(recordButton,getString(statusLabel));
        int buttonLabel = !hasCapturePermissions() ? R.string.personal_home_grant_permissions
                : active ? R.string.personal_home_stop : cameraMode == MODE_PHOTO ? R.string.shortcut_take_photo : R.string.personal_home_start;
        recordButton.setText("");
        recordButton.setContentDescription(getString(buttonLabel));
        recordButton.setIconResource(active || finishing ? R.drawable.personal_record_stop
                : cameraMode == MODE_PHOTO ? R.drawable.personal_camera_snapshot : R.drawable.personal_record_start);
        boolean settled = pendingAction == null && !lastCleanup;
        recordButton.setEnabled(!capturePermissions || settled && (state == RecordingState.NONE || state == RecordingState.IN_PROGRESS || state == RecordingState.PAUSED));
        boolean recordingControls = state == RecordingState.IN_PROGRESS || state == RecordingState.PAUSED || finishing;
        pauseButton.setVisibility(recordingControls ? View.VISIBLE : View.GONE);
        pauseButton.setEnabled(settled && (state == RecordingState.IN_PROGRESS || state == RecordingState.PAUSED));
        pauseButton.setContentDescription(getString(state == RecordingState.PAUSED ? R.string.personal_home_resume : R.string.personal_home_pause));
        pauseButton.setIconResource(state == RecordingState.PAUSED ? R.drawable.personal_resume : R.drawable.personal_pause);
        recordButton.setIconTint(recordingControls ? android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE) : null);
        recordButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(recordingControls ? android.graphics.Color.TRANSPARENT : android.graphics.Color.WHITE));
        recordButton.setStrokeWidth(0);
        PersonalCameraLayout geometry=new PersonalCameraLayout(requireContext());
        recordButton.setIconSize(geometry.px(recordingControls?72:104));
        ViewGroup.LayoutParams recordSize=recordButton.getLayoutParams();recordSize.width=recordSize.height=geometry.px(recordingControls?232:256);recordButton.setLayoutParams(recordSize);
        ViewGroup.LayoutParams pauseSize=pauseButton.getLayoutParams();pauseSize.width=pauseSize.height=geometry.px(232);pauseButton.setLayoutParams(pauseSize);pauseButton.setIconSize(geometry.px(72));
        boolean land=cameraLandscape;
        View actionGroup=getView().findViewById(R.id.personal_recording_actions);int activePad=recordingControls?geometry.px(31):0;
        actionGroup.setPadding(land?0:activePad,land?activePad:0,land?0:activePad,land?activePad:0);
        if(cameraControls!=null)cameraControls.setRecordingUi(recordingControls);
        if(recentMedia!=null)recentMedia.setActiveRecording(recordingControls);
        MaterialButton recentButton=getView().findViewById(R.id.personal_home_videos);
        recentButton.setCornerRadius(geometry.px(96));
        recentButton.setIconPadding(0);
        recentButton.setPadding(0,0,0,0);
        recentButton.setStrokeWidth(recordingControls?0:geometry.px(1));
        recentButton.setStrokeColor(android.content.res.ColorStateList.valueOf(0x40FFFFFF));
        recentButton.setContentDescription(getString(state==RecordingState.PAUSED ? R.string.photo_capture_paused
                : recordingControls ? R.string.shortcut_take_photo : R.string.personal_home_videos));
        recentButton.setEnabled(!recordingControls || settled && state==RecordingState.IN_PROGRESS);
        if(recordingControls) {
            recentButton.setIconResource(R.drawable.personal_camera_snapshot);recentButton.setIconSize(geometry.px(106));
            recentButton.setIconTint(android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE));
        }
        getView().findViewById(R.id.personal_recording_actions).setBackgroundResource(recordingControls ? R.drawable.personal_recording_actions : 0);
        cameraButton.setEnabled(state == RecordingState.NONE);
        cameraButton.setVisibility(recordingControls?View.GONE:View.VISIBLE);
        CameraType camera = prefs.getCameraSelection();
        String cameraLabel = getString(camera == CameraType.FRONT ? R.string.personal_home_front
                : camera != null && camera.isDual() ? R.string.personal_home_dual : R.string.personal_home_back);
        cameraButton.setText("");
        cameraButton.setContentDescription(cameraLabel);
        timer.setVisibility(active || finishing ? View.VISIBLE : View.GONE);
        timer.setBackgroundResource(active || finishing ? R.drawable.personal_recording_timer : 0);
        Size resolution = prefs.getCameraResolution();
        summary.setText(getString(R.string.personal_home_summary_format, resolution.getWidth(), resolution.getHeight(),
                prefs.getSpecificVideoFrameRate(camera), getString(prefs.isRecordAudioEnabled()
                        ? R.string.personal_home_audio_on : R.string.personal_home_audio_off)));
        hint.setText(capturePermissions ? R.string.personal_home_hint : R.string.personal_home_permission_hint);
        hint.setVisibility(capturePermissions ? View.GONE : View.VISIBLE);
        if (cameraControls != null) cameraControls.refresh();
        renderCameraModes();
        syncPreview();
        renderTimer();
    }

    private int cameraModeLabel() {
        return cameraMode == MODE_PHOTO ? R.string.personal_photo_mode
                : cameraMode == MODE_VIDEO ? R.string.personal_video_mode : R.string.personal_pro_video;
    }

    private void renderCameraModes() {
        View root = getView();
        if (root == null) return;
        boolean enabled = state == RecordingState.NONE && pendingAction == null && !lastCleanup
                && !RecordingService.hasActiveSession() && !DualCameraRecordingService.hasActiveSession();
        int[] ids = {R.id.personal_camera_mode_photo, R.id.personal_camera_mode_video, R.id.personal_camera_mode_pro_video};
        for (int mode = MODE_PHOTO; mode <= MODE_PRO_VIDEO; mode++) {
            TextView label = root.findViewById(ids[mode]);
            boolean selected = mode == cameraMode;
            label.setSelected(selected);
            label.setEnabled(enabled);
            label.setAlpha(selected ? 1f : .65f);
            label.setTypeface(android.graphics.Typeface.create("sec", selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
        }
        ((View) root.findViewById(R.id.personal_pro_items).getParent()).setVisibility(cameraMode == MODE_PRO_VIDEO ? View.VISIBLE : View.GONE);
    }

    @Override public void onConfigurationChanged(@NonNull Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (preview == null) return;
        if (cameraControls != null) cameraControls.cancelGesture();
        reflowCameraControls(configuration);
        syncIdleOrientation(configuration);
        // Keep this Fragment, TextureView and active recording alive. Only the
        // preview dimensions change; the encoder is never restarted for rotation.
        preview.post(this::syncPreview);
    }

    private void updateOrientationPolicy() {
        if (!previewForeground || isHidden() || getActivity() == null) return;
        if (getActivity() instanceof MainActivity) ((MainActivity) requireActivity()).setPersonalCameraFullscreen(true);
        if (previousRequestedOrientation == null) {
            previousRequestedOrientation = requireActivity().getRequestedOrientation();
        }
        boolean active = RecordingService.hasActiveSession() || DualCameraRecordingService.hasActiveSession()
                || (state != null && state != RecordingState.NONE);
        int requested=ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
        if(active) {
            requested=RecordingService.getSessionDisplayOrientation();
            if(requested==ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                // The start intent may still be queued. Its foreground snapshot
                // bridges this interval; the accepted service session owns it after that.
                requested="START".equals(pendingAction)?pendingStartDisplayOrientation:ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            }
            if(requested==ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)requested=ActivityInfo.SCREEN_ORIENTATION_LOCKED;
        }
        if (requireActivity().getRequestedOrientation() != requested) requireActivity().setRequestedOrientation(requested);
    }

    private void restoreOrientationPolicy() {
        if (getActivity() instanceof MainActivity) ((MainActivity) requireActivity()).setPersonalCameraFullscreen(false);
        if (previousRequestedOrientation == null) return;
        if (getActivity() != null) requireActivity().setRequestedOrientation(previousRequestedOrientation);
        previousRequestedOrientation = null;
    }

    private void syncIdleOrientation(Configuration configuration) {
        if (!previewForeground || isHidden() || state != RecordingState.NONE
                || RecordingService.hasActiveSession() || DualCameraRecordingService.hasActiveSession()) return;
        String orientation = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                ? SharedPreferencesManager.ORIENTATION_LANDSCAPE : SharedPreferencesManager.ORIENTATION_PORTRAIT;
        int rotation = com.fadcam.utils.CameraDisplayOrientation.currentRotation(requireContext());
        if (orientation.equals(prefs.getVideoOrientation())
                && (idlePreviewDisplayRotation == null || idlePreviewDisplayRotation == rotation)) return;
        prefs.setVideoOrientation(orientation);
        previewFailed = false;
        // Display-only rotation changes the existing preview renderer, not the
        // Camera2/GL session. The next surface-size callback carries this rotation.
        idlePreviewDisplayRotation = rotation;
    }

    private void reflowCameraControls(Configuration configuration) {
        View root=getView();if(root==null || preview==null)return;
        boolean land=configuration.orientation==Configuration.ORIENTATION_LANDSCAPE;cameraLandscape=land;
        PersonalCameraLayout g=new PersonalCameraLayout(requireContext());
        View leftRail=root.findViewById(R.id.personal_camera_left_rail),modeRail=root.findViewById(R.id.personal_camera_mode_rail);
        leftRail.getLayoutParams().width=g.px(240);modeRail.getLayoutParams().width=g.px(315);
        leftRail.setVisibility(land?View.VISIBLE:View.GONE);modeRail.setVisibility(land?View.VISIBLE:View.GONE);
        FrameLayout.LayoutParams full=(FrameLayout.LayoutParams)preview.getLayoutParams();
        full.width=full.height=ViewGroup.LayoutParams.MATCH_PARENT;full.setMargins(0,0,0,0);full.setMarginEnd(0);preview.setLayoutParams(full);
        View controls=root.findViewById(R.id.personal_home_controls);
        FrameLayout.LayoutParams strip=(FrameLayout.LayoutParams)controls.getLayoutParams();
        strip.width=ViewGroup.LayoutParams.MATCH_PARENT;strip.height=ViewGroup.LayoutParams.WRAP_CONTENT;strip.gravity=Gravity.BOTTOM|Gravity.START;
        strip.setMargins(0,0,0,g.px(land?64:1020));strip.setMarginStart(0);strip.setMarginEnd(0);controls.setLayoutParams(strip);
        controls.setTranslationX(land?-g.px(234):0);
        View proItems=root.findViewById(R.id.personal_pro_items);proItems.getLayoutParams().height=g.px(128);
        ((View)proItems.getParent()).getLayoutParams().height=g.px(128);
        View lens=root.findViewById(R.id.personal_zoom_presets_container);FrameLayout.LayoutParams lenses=(FrameLayout.LayoutParams)lens.getLayoutParams();
        lenses.width=land?g.px(136):ViewGroup.LayoutParams.WRAP_CONTENT;lenses.height=ViewGroup.LayoutParams.WRAP_CONTENT;lenses.gravity=land?Gravity.END|Gravity.CENTER_VERTICAL:Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL;
        lenses.bottomMargin=land?0:g.px(756);lenses.setMarginEnd(land?g.px(760):0);lens.setLayoutParams(lenses);
        View shutterRow=root.findViewById(R.id.personal_shutter_row);FrameLayout.LayoutParams shutter=(FrameLayout.LayoutParams)shutterRow.getLayoutParams();
        shutter.width=land?g.px(360):ViewGroup.LayoutParams.MATCH_PARENT;shutter.height=land?ViewGroup.LayoutParams.MATCH_PARENT:g.px(360);shutter.gravity=land?Gravity.END:Gravity.BOTTOM;
        shutter.setMargins(0,0,0,land?0:g.px(352));shutter.setMarginEnd(land?g.px(352):0);shutterRow.setLayoutParams(shutter);shutterRow.setPadding(0,0,0,0);
        android.widget.LinearLayout actions=root.findViewById(R.id.personal_recording_actions);
        actions.setOrientation(land?android.widget.LinearLayout.VERTICAL:android.widget.LinearLayout.HORIZONTAL);
        boolean recording=state==RecordingState.IN_PROGRESS || state==RecordingState.PAUSED || "STOP".equals(pendingAction) || RecordingService.isRecordingFinalizing();
        int pad=recording?g.px(31):0;actions.setPadding(land?0:pad,land?pad:0,land?0:pad,land?pad:0);
        View videos=root.findViewById(R.id.personal_home_videos);FrameLayout.LayoutParams gallery=(FrameLayout.LayoutParams)videos.getLayoutParams();
        gallery.width=gallery.height=g.px(192);gallery.gravity=land?Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL:Gravity.START|Gravity.CENTER_VERTICAL;
        gallery.setMargins(0,0,0,land?g.px(136):0);gallery.setMarginStart(land?0:g.px(136));videos.setLayoutParams(gallery);
        FrameLayout.LayoutParams camera=(FrameLayout.LayoutParams)cameraButton.getLayoutParams();camera.width=camera.height=g.px(192);
        camera.gravity=land?Gravity.TOP|Gravity.CENTER_HORIZONTAL:Gravity.END|Gravity.CENTER_VERTICAL;
        camera.setMargins(0,land?g.px(136):0,0,0);camera.setMarginEnd(land?0:g.px(136));cameraButton.setLayoutParams(camera);
        root.findViewById(R.id.personal_home_shortcuts).setVisibility(View.VISIBLE);
        View top=root.findViewById(R.id.personal_top_controls);FrameLayout.LayoutParams topPosition=(FrameLayout.LayoutParams)top.getLayoutParams();
        topPosition.topMargin=land?0:g.px(180);topPosition.setMarginEnd(0);top.setLayoutParams(topPosition);
        top.setPadding(0,0,0,0);
        View clockContent=root.findViewById(R.id.personal_home_clock);FrameLayout.LayoutParams clock=(FrameLayout.LayoutParams)clockContent.getLayoutParams();
        clock.gravity=Gravity.TOP|Gravity.START;clock.setMargins(0,g.px(61),0,0);clock.setMarginStart(0);clock.setMarginEnd(0);clockContent.setLayoutParams(clock);
        clockContent.setTranslationX(land?-g.px(234):0);
        timer.getLayoutParams().height=g.px(95);timer.setMinWidth(g.px(340));timer.setTextSize(g.textSp(requireContext(),52.5f));
        View mode=root.findViewById(R.id.personal_camera_mode);FrameLayout.LayoutParams modePosition=(FrameLayout.LayoutParams)mode.getLayoutParams();
        modePosition.width=g.px(1120);modePosition.height=g.px(136);modePosition.gravity=land?Gravity.END|Gravity.CENTER_VERTICAL:Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL;
        modePosition.bottomMargin=land?0:g.px(90);mode.setLayoutParams(modePosition);mode.setRotation(land?270:0);mode.setTranslationX(land?g.px(402.5f):0);
        for (int id : new int[]{R.id.personal_camera_mode_photo,R.id.personal_camera_mode_video,R.id.personal_camera_mode_pro_video})
            ((TextView)root.findViewById(id)).setTextSize(g.textSp(requireContext(),43.875f));
        if(cameraControls!=null)cameraControls.reflow(land);
        renderCameraModes();
    }

    private boolean cameraControlsReady() {
        CameraType selected = prefs.getCameraSelection();
        return previewForeground && !previewStopPending && !isHidden() && preview != null && previewSurface != null
                && previewSurface.isValid() && preview.getVisibility() == View.VISIBLE
                && (selected == null || !selected.isDual()) && !DualCameraRecordingService.hasActiveSession()
                && !RecordingService.isCameraCleanupInProgress()
                && (RecordingService.hasPreviewOnlySession() || RecordingService.hasActiveSession())
                && state != RecordingState.STARTING;
    }

    /** Idle preview uses the existing preview-only path; opening home never records audio/video. */
    private void syncPreview() {
        if (preview == null || previewClock == null || !isAdded()) return;
        boolean foreground = previewForeground && !isHidden();
        boolean cameraAllowed = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        CameraType selected = prefs.getCameraSelection();
        boolean idle = state == RecordingState.NONE;
        boolean show = foreground && cameraAllowed && state != null
                && (!idle || selected == null || !selected.isDual());
        setPreviewScreenAwake(show);
        preview.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        if (cameraControls != null) cameraControls.updateEnabled();
        if (!show || previewSurface == null || !previewSurface.isValid()) {
            detachPreview();
            return;
        }
        Context context = requireContext();
        if (idle && !RecordingService.hasActiveSession() && !DualCameraRecordingService.hasActiveSession()) {
            if (previewStopPending || RecordingService.isCameraCleanupInProgress() || previewFailed) return;
            if (!RecordingService.hasPreviewOnlySession() && !previewStartPending) {
                int width = preview.getWidth(), height = preview.getHeight();
                if (width <= 0 || height <= 0) return;
                previewOnlyOwned = true;
                idlePreviewDisplayRotation = com.fadcam.utils.CameraDisplayOrientation.currentRotation(context);
                previewStartPending = true;
                try {
                    context.startService(new Intent(context, RecordingService.class)
                            .setAction(Constants.INTENT_ACTION_START_PREVIEW_ONLY)
                            .putExtra("SURFACE", previewSurface)
                            .putExtra("SURFACE_WIDTH", width).putExtra("SURFACE_HEIGHT", height));
                    previewOwner = RecordingService.class;
                    attachedSurface = previewSurface;
                    attachedWidth = width;
                    attachedHeight = height;
                    attachedDisplayRotation = idlePreviewDisplayRotation;
                    handler.postDelayed(previewStartTimeout, 5000);
                } catch (RuntimeException e) {
                    previewStartPending = false;
                    previewFailed = true;
                    FLog.w(TAG, "Could not start idle preview", e);
                }
                return;
            }
        }
        boolean dual = DualCameraRecordingService.hasActiveSession();
        Class<?> service = dual ? DualCameraRecordingService.class : RecordingService.class;
        // The dual pipeline accepts a surface once its STARTED event arrives.
        // Single-camera STARTING accepts the surface to avoid its preview wait timeout.
        if (dual && state != RecordingState.IN_PROGRESS && state != RecordingState.PAUSED) return;
        if (!ServiceUtils.isServiceRunning(context, service)) return;
        int width = preview.getWidth(), height = preview.getHeight();
        if (width <= 0 || height <= 0) return;
        int displayRotation = com.fadcam.utils.CameraDisplayOrientation.currentRotation(context);
        boolean sameSurface = previewOwner == service && attachedSurface == previewSurface;
        if (sameSurface && attachedWidth == width && attachedHeight == height
                && (!idle || attachedDisplayRotation == displayRotation)) return;
        if (previewOwner != null && previewOwner != service) detachPreview();
        try {
            Intent change = new Intent(context, service).setAction(Constants.INTENT_ACTION_CHANGE_SURFACE)
                    .putExtra("SURFACE_WIDTH", width).putExtra("SURFACE_HEIGHT", height);
            // Re-parceling an unchanged Surface creates a new Java wrapper in the
            // service and used to recreate its EGL window on a simple resize.
            if (!sameSurface) change.putExtra("SURFACE", previewSurface);
            if (idle) change.putExtra(RecordingService.EXTRA_PREVIEW_DISPLAY_ROTATION, displayRotation);
            context.startService(change);
            previewOwner = service;
            attachedSurface = previewSurface;
            attachedWidth = width;
            attachedHeight = height;
            attachedDisplayRotation = displayRotation;
            FLog.d(TAG, "Attached live preview to " + service.getSimpleName() + " " + width + "x" + height);
        } catch (RuntimeException e) {
            FLog.w(TAG, "Could not attach recording preview", e);
        }
    }

    private void setPreviewScreenAwake(boolean awake) {
        View view = getView();
        if (view != null) view.setKeepScreenOn(awake);
    }

    /** This action stops only preview-only work; it never stops a recording started elsewhere. */
    private void stopIdlePreview() {
        handler.removeCallbacks(previewStartTimeout);
        previewStartPending = false;
        if (!previewOnlyOwned) return;
        previewOnlyOwned = false;
        previewStopPending = true;
        idlePreviewDisplayRotation = null;
        Context context = getContext();
        if (context == null) return;
        try {
            context.startService(new Intent(context, RecordingService.class)
                    .setAction(Constants.INTENT_ACTION_STOP_PREVIEW_ONLY));
        } catch (RuntimeException e) {
            previewStopPending = false;
            FLog.w(TAG, "Could not stop idle preview", e);
        }
    }

    private void detachPreview() {
        Class<?> owner = previewOwner;
        previewOwner = null;
        attachedSurface = null;
        attachedWidth = attachedHeight = 0;
        Context context = getContext();
        if (owner == null || context == null || !ServiceUtils.isServiceRunning(context, owner)) return;
        try {
            context.startService(new Intent(context, owner).setAction(Constants.INTENT_ACTION_CHANGE_SURFACE)
                    .putExtra("SURFACE", (Surface) null));
            FLog.d(TAG, "Detached foreground recording preview");
        } catch (RuntimeException e) {
            FLog.w(TAG, "Could not detach recording preview", e);
        }
    }

    private void releasePreviewSurface() {
        detachPreview();
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
    }

    private void renderTimer() {
        if (timer == null) return;
        long elapsed = 0L;
        if ("STOP".equals(pendingAction) || RecordingService.isRecordingFinalizing()) elapsed = Math.max(frozenStopElapsed, RecordingService.getFinalizingTimelineMs()) / 1000;
        else elapsed = elapsedRecordingMillis() / 1000;
        timer.setText((state != null && state != RecordingState.NONE) || RecordingService.isRecordingFinalizing() || elapsed >= 3600
                ? String.format(Locale.getDefault(), "%02d:%02d:%02d", elapsed / 3600, elapsed / 60 % 60, elapsed % 60)
                : String.format(Locale.getDefault(), "%02d:%02d", elapsed / 60, elapsed % 60));
    }

    private long elapsedRecordingMillis() {
        if (state == null || state == RecordingState.NONE || startTime <= 0L) return 0L;
        long end = state == RecordingState.PAUSED && pauseTime > 0L ? pauseTime : SystemClock.elapsedRealtime();
        return Math.max(0L, end - startTime - pausedDuration);
    }
}
