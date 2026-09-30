package com.fadcam.ui;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.os.Build;
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
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferencesManager prefs;
    private RecordingState state;
    private long startTime, pauseTime, pausedDuration;
    private boolean registered;
    private TextView timer, status, summary, hint;
    private MaterialButton recordButton, cameraButton;
    private TextureView preview;
    private View previewClock;
    private Surface previewSurface;
    private Class<?> previewOwner;
    private Surface attachedSurface;
    private int attachedWidth, attachedHeight;
    private boolean previewForeground;

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
                if (hasRecordingPermissions()) {
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
            handler.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver recordingReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (timer == null) return;
            String action = intent.getAction();
            FLog.d(TAG, "Received recording event: " + action);
            if (Constants.BROADCAST_ON_RECORDING_STATE_CALLBACK.equals(action)) {
                // An idle single-camera service must not overwrite an active dual session.
                if (ServiceUtils.isServiceRunning(context, DualCameraRecordingService.class)
                        && prefs.isRecordingInProgress()) return;
                RecordingState reported = Utils.getSerializableExtraCompat(intent,
                        Constants.INTENT_EXTRA_RECORDING_STATE, RecordingState.class);
                if (reported == null) {
                    FLog.w(TAG, "Recording state callback is missing its enum state");
                    return;
                }
                state = reported;
                FLog.d(TAG, "Recording state synchronized: " + state);
            } else if (Constants.BROADCAST_ON_RECORDING_STOPPED.equals(action)
                    || Constants.BROADCAST_ON_DUAL_RECORDING_STOPPED.equals(action)) {
                state = RecordingState.NONE;
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
            } else {
                Toast.makeText(context, R.string.personal_home_recording_failed, Toast.LENGTH_LONG).show();
                queryState();
                return;
            }
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
        timer = view.findViewById(R.id.personal_home_timer);
        status = view.findViewById(R.id.personal_home_status);
        summary = view.findViewById(R.id.personal_home_summary);
        hint = view.findViewById(R.id.personal_home_hint);
        recordButton = view.findViewById(R.id.personal_home_record);
        cameraButton = view.findViewById(R.id.personal_home_camera);
        preview = view.findViewById(R.id.personal_home_preview);
        previewClock = view.findViewById(R.id.personal_home_clock);
        preview.setSurfaceTextureListener(previewListener);
        recordButton.setOnClickListener(v -> performRecordAction());
        cameraButton.setOnClickListener(v -> chooseCamera());
        view.findViewById(R.id.personal_home_settings).setOnClickListener(v -> openSettings());
        view.findViewById(R.id.personal_home_shortcuts).setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) requireActivity()).switchFragment(4, true);
                OverlayNavUtil.show(requireActivity(), new ShortcutsSettingsFragment(), "ShortcutsSettingsFragment");
            }
        });
        render();
    }

    @Override public void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        String[] actions = {Constants.BROADCAST_ON_RECORDING_STATE_CALLBACK,
                Constants.BROADCAST_ON_RECORDING_STARTED, Constants.BROADCAST_ON_RECORDING_STOPPED,
                Constants.BROADCAST_ON_RECORDING_PAUSED, Constants.BROADCAST_ON_RECORDING_RESUMED,
                Constants.BROADCAST_ON_DUAL_RECORDING_STARTED, Constants.BROADCAST_ON_DUAL_RECORDING_STOPPED,
                Constants.BROADCAST_ON_DUAL_RECORDING_PAUSED, Constants.BROADCAST_ON_DUAL_RECORDING_RESUMED,
                Constants.ACTION_RECORDING_FAILED, Constants.BROADCAST_ON_DUAL_CAMERA_ERROR};
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
        if (timer != null) { queryState(); render(); }
    }

    @Override public void onPause() {
        previewForeground = false;
        detachPreview();
        if (preview != null) preview.setVisibility(View.INVISIBLE);
        super.onPause();
    }

    @Override public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            detachPreview();
            if (preview != null) preview.setVisibility(View.INVISIBLE);
        }
        if (!hidden && timer != null) { queryState(); render(); }
    }

    @Override public void onStop() {
        previewForeground = false;
        detachPreview();
        if (registered) {
            requireContext().unregisterReceiver(recordingReceiver);
            registered = false;
        }
        handler.removeCallbacksAndMessages(null);
        super.onStop();
    }

    @Override public void onDestroyView() {
        releasePreviewSurface();
        if (preview != null) preview.setSurfaceTextureListener(null);
        preview = null;
        previewClock = null;
        handler.removeCallbacksAndMessages(null);
        timer = status = summary = hint = null;
        recordButton = cameraButton = null;
        super.onDestroyView();
    }

    private boolean hasRecordingPermissions() {
        Context context = getContext();
        return context != null
                && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void completeSetup() {
        prefs.sharedPreferences.edit()
                .putBoolean(Constants.COMPLETED_ONBOARDING_KEY, true)
                .putBoolean(Constants.FIRST_INSTALL_CHECKED_KEY, true).apply();
    }

    private void performRecordAction() {
        if (!hasRecordingPermissions()) {
            ArrayList<String> requested = new ArrayList<>();
            requested.add(Manifest.permission.CAMERA);
            requested.add(Manifest.permission.RECORD_AUDIO);
            if (Build.VERSION.SDK_INT >= 33) requested.add(Manifest.permission.POST_NOTIFICATIONS);
            permissions.launch(requested.toArray(new String[0]));
            return; // Granting permission never starts a recording automatically.
        }
        if (state == null) { queryState(); return; }
        completeSetup();
        Intent action;
        if (state == RecordingState.NONE) {
            boolean dual = prefs.getCameraSelection() != null && prefs.getCameraSelection().isDual();
            action = dual
                    ? new Intent(requireContext(), DualCameraRecordingService.class).setAction(Constants.INTENT_ACTION_START_DUAL_RECORDING)
                    : new Intent(requireContext(), RecordingService.class).setAction(Constants.INTENT_ACTION_START_RECORDING);
            // Supply the foreground surface on the explicit start tap. This uses the
            // existing recording pipeline; it never starts a separate preview camera.
            if (!dual && previewForeground && !isHidden()
                    && previewSurface != null && previewSurface.isValid()) {
                action.putExtra("SURFACE", previewSurface);
                action.putExtra("SURFACE_WIDTH", preview.getWidth());
                action.putExtra("SURFACE_HEIGHT", preview.getHeight());
            }
            state = RecordingState.STARTING;
            startTime = pauseTime = pausedDuration = 0L;
        } else {
            boolean dual = ServiceUtils.isServiceRunning(requireContext(), DualCameraRecordingService.class);
            action = dual
                    ? new Intent(requireContext(), DualCameraRecordingService.class).setAction(Constants.INTENT_ACTION_STOP_DUAL_RECORDING)
                    : new Intent(requireContext(), RecordingService.class).setAction(Constants.INTENT_ACTION_STOP_RECORDING);
        }
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
            Toast.makeText(requireContext(), R.string.personal_home_recording_failed, Toast.LENGTH_LONG).show();
            queryState();
        }
        render();
    }

    private void chooseCamera() {
        if (state != RecordingState.NONE) return;
        CameraType selected = prefs.getCameraSelection();
        String[] choices = {getString(R.string.personal_home_back), getString(R.string.personal_home_front)};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.personal_home_camera_title)
                .setSingleChoiceItems(choices, selected == CameraType.FRONT ? 1 : 0, (dialog, which) -> {
                    prefs.sharedPreferences.edit().putString(Constants.PREF_CAMERA_SELECTION,
                            (which == 1 ? CameraType.FRONT : CameraType.BACK).name()).apply();
                    render();
                    dialog.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void openSettings() {
        if (getActivity() instanceof MainActivity) ((MainActivity) requireActivity()).switchFragment(4, true);
    }

    private void queryState() {
        if (!isAdded()) return;
        if (ServiceUtils.isServiceRunning(requireContext(), DualCameraRecordingService.class)) {
            readTimeline(null);
            state = prefs.isRecordingInProgress()
                    ? (pauseTime > 0 ? RecordingState.PAUSED : RecordingState.IN_PROGRESS)
                    : RecordingState.STARTING;
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
        boolean active = state != null && state != RecordingState.NONE;
        int statusLabel = state == null ? R.string.personal_home_syncing
                : state == RecordingState.STARTING ? R.string.personal_home_starting
                : state == RecordingState.WAITING_FOR_CAMERA ? R.string.personal_home_waiting
                : state == RecordingState.PAUSED ? R.string.personal_home_paused
                : active ? R.string.personal_home_recording : R.string.personal_home_ready;
        status.setText(statusLabel);
        int buttonLabel = !hasRecordingPermissions() ? R.string.personal_home_grant_permissions
                : active ? R.string.personal_home_stop : R.string.personal_home_start;
        recordButton.setText(buttonLabel);
        recordButton.setIconResource(active ? R.drawable.ic_stop : R.drawable.ic_camera);
        recordButton.setEnabled(!hasRecordingPermissions() || state != null);
        cameraButton.setEnabled(state == RecordingState.NONE);
        CameraType camera = prefs.getCameraSelection();
        String cameraLabel = getString(camera == CameraType.FRONT ? R.string.personal_home_front
                : camera != null && camera.isDual() ? R.string.personal_home_dual : R.string.personal_home_back);
        cameraButton.setText(cameraLabel);
        Size resolution = prefs.getCameraResolution();
        summary.setText(getString(R.string.personal_home_summary_format, resolution.getWidth(), resolution.getHeight(),
                prefs.getSpecificVideoFrameRate(camera), getString(prefs.isRecordAudioEnabled()
                        ? R.string.personal_home_audio_on : R.string.personal_home_audio_off)));
        hint.setText(hasRecordingPermissions() ? R.string.personal_home_hint : R.string.personal_home_permission_hint);
        syncPreview();
        renderTimer();
    }

    /** Attach only to an existing recording session while the home screen is foreground. */
    private void syncPreview() {
        if (preview == null || previewClock == null || !isAdded()) return;
        boolean show = previewForeground && !isHidden() && state != null && state != RecordingState.NONE;
        preview.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        FrameLayout.LayoutParams clockLayout = (FrameLayout.LayoutParams) previewClock.getLayoutParams();
        int clockSize = Math.round(236 * getResources().getDisplayMetrics().density);
        clockLayout.width = show ? ViewGroup.LayoutParams.MATCH_PARENT : clockSize;
        clockLayout.height = show ? ViewGroup.LayoutParams.WRAP_CONTENT : clockSize;
        clockLayout.gravity = show ? Gravity.BOTTOM : Gravity.CENTER;
        previewClock.setLayoutParams(clockLayout);
        if (show) previewClock.setBackgroundColor(Color.parseColor("#B3101916"));
        else previewClock.setBackgroundResource(R.drawable.personal_timer_background);
        timer.setTextSize(show ? 28 : 46);
        if (!show || previewSurface == null || !previewSurface.isValid()) {
            detachPreview();
            return;
        }
        Context context = requireContext();
        boolean dual = ServiceUtils.isServiceRunning(context, DualCameraRecordingService.class);
        Class<?> service = dual ? DualCameraRecordingService.class : RecordingService.class;
        // The dual pipeline accepts a surface once its STARTED event arrives.
        // Single-camera STARTING accepts the surface to avoid its preview wait timeout.
        if (dual && state != RecordingState.IN_PROGRESS && state != RecordingState.PAUSED) return;
        if (!ServiceUtils.isServiceRunning(context, service)) return;
        int width = preview.getWidth(), height = preview.getHeight();
        if (width <= 0 || height <= 0) return;
        if (previewOwner == service && attachedSurface == previewSurface
                && attachedWidth == width && attachedHeight == height) return;
        if (previewOwner != null && previewOwner != service) detachPreview();
        try {
            context.startService(new Intent(context, service).setAction(Constants.INTENT_ACTION_CHANGE_SURFACE)
                    .putExtra("SURFACE", previewSurface).putExtra("SURFACE_WIDTH", width).putExtra("SURFACE_HEIGHT", height));
            previewOwner = service;
            attachedSurface = previewSurface;
            attachedWidth = width;
            attachedHeight = height;
            FLog.d(TAG, "Attached live preview to " + service.getSimpleName() + " " + width + "x" + height);
        } catch (RuntimeException e) {
            FLog.w(TAG, "Could not attach recording preview", e);
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
        if (state != null && state != RecordingState.NONE && startTime > 0L) {
            long end = state == RecordingState.PAUSED && pauseTime > 0L ? pauseTime : SystemClock.elapsedRealtime();
            elapsed = Math.max(0L, end - startTime - pausedDuration) / 1000;
        }
        timer.setText(elapsed >= 3600
                ? String.format(Locale.getDefault(), "%02d:%02d:%02d", elapsed / 3600, elapsed / 60 % 60, elapsed % 60)
                : String.format(Locale.getDefault(), "%02d:%02d", elapsed / 60, elapsed % 60));
    }
}
