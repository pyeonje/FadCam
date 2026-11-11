package com.fadcam.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.SeekBar;
import androidx.appcompat.widget.AppCompatSeekBar;

/** Compact camera ruler: scroll the ticks past a fixed yellow selection mark. */
public final class PersonalCameraRuler extends AppCompatSeekBar {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean vertical, dragged, focusScale, zoomScale, tracking;
    private float last, down, dragProgress;
    private OnSeekBarChangeListener listener;
    public PersonalCameraRuler(Context c, AttributeSet a) {
        super(c,a); setThumb(null); setBackground(null); setPadding(0,0,0,0);
    }
    public void setVertical(boolean value) { vertical=value; invalidate(); }
    public void setZoomScale(boolean value) { zoomScale=value;invalidate(); }
    public void setFocusScale(boolean value) { focusScale=value;invalidate(); }
    @Override public void setOnSeekBarChangeListener(OnSeekBarChangeListener value) {
        listener=value; super.setOnSeekBarChangeListener(value);
    }
    @Override protected synchronized void onDraw(Canvas canvas) {
        float d=getResources().getDisplayMetrics().density;
        float length=vertical?getHeight():getWidth(), cross=vertical?getWidth():getHeight();
        PersonalCameraLayout g=new PersonalCameraLayout(getContext());
        float interval=g.px(zoomScale?24:32);
        float tickCenter=vertical && focusScale?cross*160f/222f:cross/2;
        float step=focusScale?1:getMax()<60?.25f:Math.max(1,getMax()/60f);
        int radius=Math.round(length/interval/2);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for(int i=-radius;i<=radius;i++) {
            float value=getProgress()+(vertical?-i:i)*step;
            if(value<0 || value>getMax())continue;
            float position=length/2+i*interval;
            float fade=Math.max(0,1-Math.abs(i)/(float)Math.max(1,radius));
            paint.setColor(i==0?0xFFFFD400:0xFFFFFFFF);paint.setAlpha(i==0?255:Math.round(220*fade));
            paint.setStrokeWidth(g.px(i==0?8:4));
            boolean major=focusScale?Math.round(value)%5==0:i%5==0;
            float size=g.px(zoomScale?(i==0?76:48):(i==0?64:major?40:36));
            if(vertical)canvas.drawLine(tickCenter-size/2,position,tickCenter+size/2,position,paint);
            else canvas.drawLine(position,cross/2-size/2,position,cross/2+size/2,paint);
            if(vertical && focusScale && major && i!=0) {
                float labelX=cross*65f/222f;
                paint.setTypeface(android.graphics.Typeface.create("sec-num",android.graphics.Typeface.NORMAL));paint.setTextSize(g.px(48));paint.setTextAlign(Paint.Align.CENTER);
                canvas.save();canvas.rotate(270,labelX,position);
                canvas.drawText(String.format(java.util.Locale.US,"%.2f",value/100f),labelX,position-(paint.ascent()+paint.descent())/2,paint);canvas.restore();
            }
        }
    }
    public void cancelGesture() {tracking=false;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);}
    private void userProgress(int progress) {
        super.setProgress(progress);
        if(listener!=null)listener.onProgressChanged(this,getProgress(),true);
        invalidate();
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if(!isEnabled() || getVisibility()!=VISIBLE){cancelGesture();return false;}
        if(event.getActionMasked()!=MotionEvent.ACTION_DOWN && !tracking)return false;
        float point=vertical?event.getY():event.getX(), length=vertical?getHeight():getWidth();
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tracking=true;
                down=last=point;dragProgress=getProgress();dragged=false;getParent().requestDisallowInterceptTouchEvent(true);
                if(listener!=null)listener.onStartTrackingTouch(this);return true;
            case MotionEvent.ACTION_MOVE:
                float delta=vertical?last-point:point-last;
                if(Math.abs(point-down)>4)getParent().requestDisallowInterceptTouchEvent(true);
                dragged|=Math.abs(point-down)>4;
                // Preserve sub-step movement: a slow finger produces many small
                // MotionEvents that must accumulate before rounding an ISO stop.
                dragProgress=Math.max(0,Math.min(getMax(),dragProgress+delta*Math.max(1,getMax())/Math.max(1,length*.65f)));
                userProgress(Math.round(dragProgress));
                last=point;return true;
            case MotionEvent.ACTION_UP:
                tracking=false;
                if(!dragged) {
                    float deltaTap=vertical?length/2-point:point-length/2;
                    userProgress(getProgress()+Math.round(deltaTap*Math.max(1,getMax())/Math.max(1,length*.65f)));
                    performClick();
                }
                if(listener!=null)listener.onStopTrackingTouch(this);
                getParent().requestDisallowInterceptTouchEvent(false);return true;
            case MotionEvent.ACTION_CANCEL:
                tracking=false;
                if(listener!=null)listener.onStopTrackingTouch(this);
                getParent().requestDisallowInterceptTouchEvent(false);return true;
            default:return true;
        }
    }
    @Override public boolean performClick() { super.performClick();return true; }
}
