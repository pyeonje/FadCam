package com.fadcam.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.app.Application;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.camera2.*;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Handler;
import android.os.Looper;
import android.util.Range;
import android.util.SizeF;
import android.view.Surface;
import com.fadcam.*;
import com.fadcam.services.RecordingService;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class CameraLensCatalogTest {
    private CameraManager manager;
    private CameraCharacteristics main, st;
    @Before public void setup() throws Exception {
        manager = mock(CameraManager.class);
        when(manager.getCameraIdList()).thenReturn(new String[]{"0", "1", "2", "3"});
        main = camera(6.3f, 9.792f);
        when(main.getPhysicalCameraIds()).thenReturn(new LinkedHashSet<>(Arrays.asList("2","5","6","7")));
        when(manager.getCameraCharacteristics("0")).thenReturn(main);
        CameraCharacteristics c1=camera(3.3f, 4.48f);
        when(manager.getCameraCharacteristics("1")).thenReturn(c1);
        when(manager.getCameraCharacteristics("1").get(CameraCharacteristics.LENS_FACING)).thenReturn(CameraCharacteristics.LENS_FACING_FRONT);
        CameraCharacteristics c2=camera(2.2f, 5.6f);
        when(manager.getCameraCharacteristics("2")).thenReturn(c2);
        CameraCharacteristics c3=camera(7.9f, 4.48f);
        when(manager.getCameraCharacteristics("3")).thenReturn(c3);
        CameraCharacteristics c5=camera(6.3f, 9.792f);
        when(manager.getCameraCharacteristics("5")).thenReturn(c5);
        CameraCharacteristics c6=camera(7.9f, 4.48f);
        when(manager.getCameraCharacteristics("6")).thenReturn(c6);
        st = camera(18.6f, 5.712f);
        when(st.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)).thenReturn(new Range<>(1f,8f));
        when(st.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)).thenReturn(new Rect(0,0,4000,3000));
        when(manager.getCameraCharacteristics("7")).thenReturn(st);
    }
    private CameraCharacteristics camera(float focal, float width) {
        CameraCharacteristics c = mock(CameraCharacteristics.class);
        when(c.get(CameraCharacteristics.LENS_FACING)).thenReturn(CameraCharacteristics.LENS_FACING_BACK);
        when(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)).thenReturn(new float[]{focal});
        when(c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)).thenReturn(new SizeF(width,width*.75f));
        return c;
    }
    @After public void cleanup() {
        ReflectionHelpers.setStaticField(SharedPreferencesManager.class,"instance",null);
        ReflectionHelpers.setStaticField(RecordingService.class,"liveInstance",null);
    }
    @Test public void s24SensorGeometryProducesFourLensesNotDigitalCropStops() throws Exception {
        List<CameraLensCatalog.Lens> lenses = CameraLensCatalog.rear(manager);
        assertEquals(4,lenses.size());
        String[] ids={"2","5","6","7"}, names={"UW","W","T","ST"};
        float[] bases={.6f,1f,3f,5f};
        for(int i=0;i<4;i++) {
            assertEquals(ids[i],lenses.get(i).id); assertEquals(names[i],lenses.get(i).label);
            assertEquals(bases[i],lenses.get(i).baseRatio,0f);
        }
        assertEquals(3f,CameraLensCatalog.baseRatio(manager,"6"),0f); // 7.9mm is telephoto, not an ultrawide <=8mm.
        CameraLensCatalog.Route route=CameraLensCatalog.resolve(manager,"7");
        assertEquals("0",route.deviceId); assertEquals("7",route.physicalId);
        assertEquals("7",ReflectionHelpers.getField(route.output(mock(Surface.class)),"mPhysicalCameraId"));
        assertEquals("2",CameraLensCatalog.resolve(manager,"2").deviceId);
        assertNull(CameraLensCatalog.resolve(manager,"2").physicalId);
        try { CameraLensCatalog.resolve(manager,"999"); fail(); } catch(IllegalArgumentException expected) { }
    }
    @Test public void actualStandardSessionTargetsPhysicalLensAndSavedLocalCrop() throws Exception {
        Context app=RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(SharedPreferencesManager.class,"instance",null);
        app.getSharedPreferences(Constants.PREFS_NAME,Context.MODE_PRIVATE).edit().clear().commit();
        SharedPreferencesManager prefs=SharedPreferencesManager.getInstance(app);
        prefs.setSelectedBackCameraId("7"); prefs.setSpecificZoomRatio(CameraType.BACK,2f);
        RecordingService service=Robolectric.buildService(RecordingService.class).get();
        CameraDevice device=mock(CameraDevice.class);
        Class metadataClass=Class.forName("android.hardware.camera2.impl.CameraMetadataNative");
        Object metadata=ReflectionHelpers.callConstructor(metadataClass);
        CaptureRequest.Builder builder=ReflectionHelpers.callConstructor(CaptureRequest.Builder.class,
                ReflectionHelpers.ClassParameter.from(metadataClass,metadata),
                ReflectionHelpers.ClassParameter.from(boolean.class,false),
                ReflectionHelpers.ClassParameter.from(int.class,-1),
                ReflectionHelpers.ClassParameter.from(String.class,"0"),
                ReflectionHelpers.ClassParameter.from(Set.class,new HashSet<>(Arrays.asList("7"))));
        when(device.createCaptureRequest(anyInt())).thenReturn(builder);
        ReflectionHelpers.setField(service,"cameraManager",manager);
        ReflectionHelpers.setField(service,"cameraDevice",device);
        ReflectionHelpers.setField(service,"sharedPreferencesManager",prefs);
        ReflectionHelpers.setField(service,"currentCameraCharacteristics",st);
        ReflectionHelpers.setField(service,"previewOnlyActive",true);
        ReflectionHelpers.setField(service,"backgroundHandler",new Handler(Looper.getMainLooper()));
        Surface surface=mock(Surface.class);
        ReflectionHelpers.callInstanceMethod(service,"createStandardSession",
            ReflectionHelpers.ClassParameter.from(List.class,Arrays.asList(surface)),
            ReflectionHelpers.ClassParameter.from(int.class,30),
            ReflectionHelpers.ClassParameter.from(CameraCharacteristics.class,st),
            ReflectionHelpers.ClassParameter.from(CameraType.class,CameraType.BACK));
        ArgumentCaptor<SessionConfiguration> config=ArgumentCaptor.forClass(SessionConfiguration.class);
        verify(device).createCaptureSession(config.capture());
        assertEquals("7",ReflectionHelpers.getField(config.getValue().getOutputConfigurations().get(0),"mPhysicalCameraId"));
        assertEquals(new Rect(1000,750,3000,2250),builder.get(CaptureRequest.SCALER_CROP_REGION));
        assertEquals(Float.valueOf(1f),builder.get(CaptureRequest.CONTROL_ZOOM_RATIO)); // ST 5x base, local 2x crop = displayed 10x, not another HAL lens switch.
        assertEquals(2f,prefs.getSpecificZoomRatio(CameraType.BACK),0f);
    }
    @Test public void nonZeroPublicRearIsUsedBySelectionPreviewAndOpenWithoutInventingZero() throws Exception {
        when(manager.getCameraIdList()).thenReturn(new String[]{"1"});
        CameraCharacteristics rear=camera(4f,6f);
        when(manager.getCameraCharacteristics("1")).thenReturn(rear);
        when(manager.getCameraCharacteristics("0")).thenThrow(new IllegalArgumentException("unknown device 0"));
        Context app=RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(SharedPreferencesManager.class,"instance",null);
        app.getSharedPreferences(Constants.PREFS_NAME,Context.MODE_PRIVATE).edit().clear().commit();
        SharedPreferencesManager prefs=SharedPreferencesManager.getInstance(app);
        assertEquals("0",prefs.getSelectedBackCameraId());
        assertEquals("1",prefs.resolveSelectedCameraId(manager,CameraType.BACK));
        assertEquals("1",prefs.getSelectedBackCameraId());
        assertEquals(1f,CameraLensCatalog.baseRatio(manager,"1"),0f);
        assertEquals("1",CameraLensCatalog.rear(manager).get(0).id);
        RecordingService service=Robolectric.buildService(RecordingService.class).get();
        ReflectionHelpers.setField(service,"cameraManager",manager);
        ReflectionHelpers.setField(service,"sharedPreferencesManager",prefs);
        assertEquals("1",ReflectionHelpers.callInstanceMethod(service,"getCameraId",
                ReflectionHelpers.ClassParameter.from(CameraManager.class,manager),
                ReflectionHelpers.ClassParameter.from(CameraType.class,CameraType.BACK)));
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.CAMERA);
        ReflectionHelpers.callInstanceMethod(service,"openCamera");
        verify(manager).openCamera(eq("1"),any(CameraDevice.StateCallback.class),nullable(Handler.class));
        verify(manager,never()).getCameraCharacteristics("0");
    }
    @Test public void cameraZeroFacingFrontDoesNotBecomeRearBaseline() throws Exception {
        when(manager.getCameraIdList()).thenReturn(new String[]{"0","rear4"});
        CameraCharacteristics front=camera(3f,4f);
        when(front.get(CameraCharacteristics.LENS_FACING)).thenReturn(CameraCharacteristics.LENS_FACING_FRONT);
        when(manager.getCameraCharacteristics("0")).thenReturn(front);
        CameraCharacteristics rear=camera(4f,6f);
        when(manager.getCameraCharacteristics("rear4")).thenReturn(rear);
        assertEquals("rear4",CameraLensCatalog.select(manager,"0",CameraCharacteristics.LENS_FACING_BACK));
        assertEquals("0",CameraLensCatalog.select(manager,null,CameraCharacteristics.LENS_FACING_FRONT));
        assertEquals(1f,CameraLensCatalog.baseRatio(manager,"rear4"),0f);
    }
    @Test public void advertisedSavedPhysicalRearWithoutOwnFacingIsPreserved() throws Exception {
        when(st.get(CameraCharacteristics.LENS_FACING)).thenReturn(null);
        Context app=RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(SharedPreferencesManager.class,"instance",null);
        app.getSharedPreferences(Constants.PREFS_NAME,Context.MODE_PRIVATE).edit().clear().commit();
        SharedPreferencesManager prefs=SharedPreferencesManager.getInstance(app);prefs.setSelectedBackCameraId("7");
        assertEquals("7",prefs.resolveSelectedCameraId(manager,CameraType.BACK));
        assertEquals("7",prefs.getSelectedBackCameraId());
        assertEquals("0",CameraLensCatalog.resolve(manager,"7").deviceId);
    }
    @Test public void missingRearReturnsNullWithoutOverwritingSavedSelection() throws Exception {
        when(manager.getCameraIdList()).thenReturn(new String[]{"1"});
        Context app=RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(SharedPreferencesManager.class,"instance",null);
        app.getSharedPreferences(Constants.PREFS_NAME,Context.MODE_PRIVATE).edit().clear().commit();
        SharedPreferencesManager prefs=SharedPreferencesManager.getInstance(app);prefs.setSelectedBackCameraId("7");
        assertNull(prefs.resolveSelectedCameraId(manager,CameraType.BACK));
        assertEquals("7",prefs.getSelectedBackCameraId());
        assertTrue(CameraLensCatalog.rear(manager).isEmpty());
    }
}
