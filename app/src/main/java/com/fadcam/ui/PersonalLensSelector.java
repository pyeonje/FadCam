package com.fadcam.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;

/** One shared lens highlight, moving inside the common rail rather than four chips. */
public final class PersonalLensSelector extends LinearLayout {
    private final Paint highlight=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF displayed=new RectF(),target=new RectF();
    private ValueAnimator motion;
    private int selectedIndex=-1;
    private boolean animateNext;
    private float[] glyphScales=new float[0];

    public PersonalLensSelector(Context context,AttributeSet attrs) {
        super(context,attrs);highlight.setColor(0x66303030);
    }

    void selectIndex(int index) {
        if(index==selectedIndex)return;
        selectedIndex=index;animateNext=!displayed.isEmpty();requestLayout();invalidate();
    }

    @Override public void setOrientation(int orientation) {
        if(displayed!=null && orientation!=getOrientation()) { cancelMotion();displayed.setEmpty();target.setEmpty();animateNext=false; }
        super.setOrientation(orientation);
    }

    @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom) {
        super.onLayout(changed,left,top,right,bottom);
        View selected=selectedIndex>=0 && selectedIndex<getChildCount()?getChildAt(selectedIndex):null;
        if(selected==null || selected.getWidth()==0 || selected.getHeight()==0) {
            cancelMotion();displayed.setEmpty();target.setEmpty();animateNext=false;settleGlyphScales();return;
        }
        // Stock S24 reference: 136px shared rail, 120px selected disc.
        float diameter=Math.min(selected.getWidth(),selected.getHeight());
        float cx=(selected.getLeft()+selected.getRight())/2f,cy=(selected.getTop()+selected.getBottom())/2f;
        RectF next=new RectF(cx-diameter/2,cy-diameter/2,cx+diameter/2,cy+diameter/2);
        if(next.equals(target)) {applyGlyphScales();return;}
        cancelMotion();target.set(next);
        if(!animateNext || displayed.isEmpty() || !isAttachedToWindow() || (android.os.Build.VERSION.SDK_INT>=26 && !ValueAnimator.areAnimatorsEnabled())) {
            displayed.set(target);animateNext=false;settleGlyphScales();invalidate();return;
        }
        final RectF from=new RectF(displayed),to=new RectF(target);animateNext=false;
        if(glyphScales.length!=getChildCount())settleGlyphScales();
        final float[] fromScales=glyphScales.clone();
        motion=ValueAnimator.ofFloat(0f,1f);motion.setDuration(200);
        motion.setInterpolator(new PathInterpolator(.2f,0f,0f,1f));
        motion.addUpdateListener(animation->{float f=(float)animation.getAnimatedValue();
            displayed.set(from.left+(to.left-from.left)*f,from.top+(to.top-from.top)*f,
                    from.right+(to.right-from.right)*f,from.bottom+(to.bottom-from.bottom)*f);
            for(int n=0;n<glyphScales.length;n++)glyphScales[n]=fromScales[n]+((n==selectedIndex?1.25f:1f)-fromScales[n])*f;
            applyGlyphScales();invalidate();});
        motion.start();
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        if(!displayed.isEmpty())canvas.drawRoundRect(displayed,displayed.width()/2,displayed.height()/2,highlight);
        super.dispatchDraw(canvas);
    }

    private void cancelMotion() {if(motion!=null){motion.cancel();motion.removeAllUpdateListeners();motion=null;}}
    private void settleGlyphScales() {
        glyphScales=new float[getChildCount()];for(int n=0;n<glyphScales.length;n++)glyphScales[n]=n==selectedIndex?1.25f:1f;applyGlyphScales();
    }
    private void applyGlyphScales() {
        for(int n=0;n<Math.min(glyphScales.length,getChildCount());n++){getChildAt(n).setScaleX(glyphScales[n]);getChildAt(n).setScaleY(glyphScales[n]);}
    }
    @Override protected void onDetachedFromWindow() {cancelMotion();displayed.set(target);settleGlyphScales();animateNext=false;super.onDetachedFromWindow();}
}
