package com.fadcam.ui;

import static org.junit.Assert.*;
import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mikimn.droiduvc.UvcCamera;
import java.io.*;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Native library linkage + actual integrated no-device screen only; not USB hardware verification. */
@RunWith(AndroidJUnit4.class)
public class UsbCameraSmokeTest {
 @Test public void integratedUsbScreenAndNativeEngineLoadWithoutSeparateApp()throws Exception{
  assertTrue(Build.HARDWARE.equals("ranchu")||Build.HARDWARE.equals("goldfish"));
  boolean rejected=false;try{UvcCamera.Companion.open(-1);}catch(Exception e){assertTrue(e instanceof java.io.IOException || e instanceof IllegalArgumentException);rejected=true;}assertTrue("Invalid descriptor rejected by real JNI",rejected);
  Instrumentation i=InstrumentationRegistry.getInstrumentation();UsbCameraActivity a=(UsbCameraActivity)i.startActivitySync(new Intent(i.getTargetContext(),UsbCameraActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  try{i.waitForIdleSync();assertTrue(a.devices().isEmpty());Bitmap bitmap=i.getUiAutomation().takeScreenshot();assertNotNull(bitmap);File folder=new File(a.getExternalFilesDir(null),"qa-usb-20261102");assertTrue(folder.isDirectory()||folder.mkdirs());try(FileOutputStream output=new FileOutputStream(new File(folder,"usb-no-device.png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}finally{bitmap.recycle();}}
  finally{i.runOnMainSync(a::finish);}
 }
}
