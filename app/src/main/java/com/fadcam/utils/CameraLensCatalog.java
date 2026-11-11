package com.fadcam.utils;

import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;
import com.fadcam.Constants;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Physical lenses, not digital zoom shortcuts. IDs always come from Camera2. */
public final class CameraLensCatalog {
    private CameraLensCatalog() { }

    public static final class Route {
        public final String deviceId, physicalId;
        Route(String deviceId, String physicalId) {
            this.deviceId = deviceId; this.physicalId = physicalId;
        }
        public OutputConfiguration output(Surface surface) {
            OutputConfiguration output = new OutputConfiguration(surface);
            if (Build.VERSION.SDK_INT >= 28 && physicalId != null) output.setPhysicalCameraId(physicalId);
            return output;
        }
    }

    public static final class Lens {
        public final String id, label;
        public final float baseRatio;
        public final CameraCharacteristics capabilities;
        Lens(String id, float ratio, CameraCharacteristics capabilities) {
            this.id = id; this.baseRatio = ratio; this.capabilities = capabilities;
            label = ratio < .85f ? "UW" : ratio < 1.3f ? "W" : ratio < 4f ? "T" : "ST";
        }
        public boolean supports(Size size, int fps) {
            StreamConfigurationMap map = capabilities.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null || size == null) return false;
            if (fps >= 120) return false; // Physical-output sessions use SESSION_REGULAR.
            Size landscape = new Size(Math.max(size.getWidth(), size.getHeight()), Math.min(size.getWidth(), size.getHeight()));
            Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
            if (sizes == null || !Arrays.asList(sizes).contains(landscape)) return false;
            long duration = map.getOutputMinFrameDuration(SurfaceTexture.class, landscape);
            return duration == 0 || duration <= 1_000_000_000L / Math.max(1, fps) + 1000;
        }
    }

    /** Non-public physical IDs cannot be passed directly to openCamera(). */
    public static Route resolve(CameraManager manager, String selected) throws CameraAccessException {
        List<String> publicIds = Arrays.asList(manager.getCameraIdList());
        if (publicIds.contains(selected)) return new Route(selected, null);
        if (Build.VERSION.SDK_INT >= 28) {
            for (String id : publicIds) {
                if (manager.getCameraCharacteristics(id).getPhysicalCameraIds().contains(selected)) {
                    return new Route(id, selected);
                }
            }
        }
        throw new IllegalArgumentException("Camera is not advertised: " + selected);
    }

    /** A saved ID is only a preference; every consumer uses the same advertised route. */
    public static String select(CameraManager manager, String preferred, int facing) throws CameraAccessException {
        if(manager==null)return null;
        String first=null, conventional=null;
        String[] publicIds=manager.getCameraIdList();
        for(String id:publicIds) {
            CameraCharacteristics c=manager.getCameraCharacteristics(id);
            if(!Integer.valueOf(facing).equals(c.get(CameraCharacteristics.LENS_FACING)))continue;
            if(first==null)first=id;
            if(id.equals(Constants.DEFAULT_BACK_CAMERA_ID) && facing==CameraCharacteristics.LENS_FACING_BACK)conventional=id;
            if(id.equals(preferred))return id;
            if(Build.VERSION.SDK_INT>=28 && preferred!=null && c.getPhysicalCameraIds().contains(preferred)) {
                // Physical children may omit LENS_FACING; their advertised parent
                // establishes facing and supplies the public device route.
                CameraCharacteristics child=manager.getCameraCharacteristics(preferred);
                Integer childFacing=child.get(CameraCharacteristics.LENS_FACING);
                if(childFacing==null || childFacing==facing)return preferred;
            }
        }
        return conventional!=null?conventional:first;
    }

    public static List<Lens> rear(CameraManager manager) throws CameraAccessException {
        Map<String, CameraCharacteristics> candidates = new LinkedHashMap<>();
        String mainId=select(manager,null,CameraCharacteristics.LENS_FACING_BACK);
        CameraCharacteristics main = mainId!=null?manager.getCameraCharacteristics(mainId):null;
        // Prefer actual physical children over their duplicate logical/default view.
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            if (!Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(c.get(CameraCharacteristics.LENS_FACING))) continue;
            if (Build.VERSION.SDK_INT >= 28) for (String physical : c.getPhysicalCameraIds()) {
                candidates.putIfAbsent(physical, manager.getCameraCharacteristics(physical));
            }
        }
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            if (Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(c.get(CameraCharacteristics.LENS_FACING))) candidates.putIfAbsent(id, c);
        }
        if (main == null && !candidates.isEmpty()) main = candidates.values().iterator().next();
        float mainFov = focalPerSensorWidth(main);
        List<Lens> result = new ArrayList<>();
        if (mainFov <= 0) return result; // Unknown field of view is not a made-up lens.
        for (Map.Entry<String, CameraCharacteristics> entry : candidates.entrySet()) {
            float fov = focalPerSensorWidth(entry.getValue());
            if (fov <= 0) continue;
            float ratio = namedRatio(fov / mainFov);
            boolean duplicate = false;
            for (Lens lens : result) if (Math.abs(lens.baseRatio / ratio - 1) < .12f) duplicate = true;
            if (!duplicate) result.add(new Lens(entry.getKey(), ratio, entry.getValue()));
        }
        result.sort(Comparator.comparingDouble(lens -> lens.baseRatio));
        return result;
    }

    public static float baseRatio(CameraManager manager, String selected) throws CameraAccessException {
        String mainId=select(manager,null,CameraCharacteristics.LENS_FACING_BACK);
        String selectedId=select(manager,selected,CameraCharacteristics.LENS_FACING_BACK);
        if(mainId==null || selectedId==null)return 1f;
        CameraCharacteristics main = manager.getCameraCharacteristics(mainId);
        float baseline = focalPerSensorWidth(main);
        float focal = focalPerSensorWidth(manager.getCameraCharacteristics(selectedId));
        return baseline > 0 && focal > 0 ? namedRatio(focal / baseline) : 1f;
    }

    private static float focalPerSensorWidth(CameraCharacteristics c) {
        if (c == null) return 0;
        float[] focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        SizeF sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        return focal != null && focal.length > 0 && sensor != null && sensor.getWidth() > 0 ? focal[0] / sensor.getWidth() : 0;
    }

    private static float namedRatio(float measured) {
        // Nominal ratios describe lens field of view, not the raw focal length in mm.
        for (float standard : new float[]{.6f, 1f, 2f, 3f, 5f, 10f}) {
            if (Math.abs(measured / standard - 1) < .12f) return standard;
        }
        return Math.round(measured * 10) / 10f;
    }
}
