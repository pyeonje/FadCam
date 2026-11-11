package com.fadcam.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** Viewfinder-only composition guide; never burned into recorded video. */
public final class PersonalCameraGuides extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    public PersonalCameraGuides(Context c,AttributeSet a){super(c,a);setClickable(false);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override protected void onDraw(Canvas canvas){
        paint.setColor(0x70FFFFFF);paint.setStrokeWidth(getResources().getDisplayMetrics().density*.7f);
        com.fadcam.SharedPreferencesManager prefs=com.fadcam.SharedPreferencesManager.getInstance(getContext());
        android.util.Size size=prefs.getCameraResolution();
        float ratio=(float)Math.max(size.getWidth(),size.getHeight())/Math.min(size.getWidth(),size.getHeight());
        if(com.fadcam.SharedPreferencesManager.ORIENTATION_PORTRAIT.equals(prefs.getVideoOrientation()))ratio=1/ratio;
        float width=getWidth(),height=getHeight();if(width/Math.max(1,height)>ratio)width=height*ratio;else height=width/ratio;
        float left=(getWidth()-width)/2,top=(getHeight()-height)/2;
        for(int i=1;i<3;i++){float x=left+width*i/3f,y=top+height*i/3f;canvas.drawLine(x,top,x,top+height,paint);canvas.drawLine(left,y,left+width,y,paint);}
    }
}
