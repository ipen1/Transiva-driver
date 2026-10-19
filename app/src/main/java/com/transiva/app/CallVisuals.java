package com.transiva.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

/** Lightweight native artwork. No overlay windows, remote assets or animation loop. */
final class CallVisuals {
    static final int TOP=0xff0a1728, BOTTOM=0xff060e1b, MUTED=0xffa6b9cd;
    static GradientDrawable surface(int top,int bottom,int radius,int border) {
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{top,bottom});
        d.setCornerRadius(radius);if(border!=0)d.setStroke(1,border);return d;
    }
    static void style(Button b,String icon,boolean selected,int kind,int densitySize) {
        int a=kind==1?0xff25ce95:kind==2?0xfff15b70:selected?0xff31566c:0xff1c2d43;
        int z=kind==1?0xff087b63:kind==2?0xffac294c:selected?0xff183b50:0xff102033;
        b.setBackgroundTintList(null);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x38ffffff),
                surface(a,z,densitySize,kind==0?0xff38516a:0x55ffffff),null));
        b.setTextColor(Color.WHITE);b.setAllCaps(false);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setGravity(Gravity.CENTER);b.setMinHeight(0);b.setMinimumHeight(0);b.setMinWidth(0);b.setMinimumWidth(0);
        b.setPadding(4,4,4,4);b.setElevation(kind==0?0:4*b.getResources().getDisplayMetrics().density);
        Icon glyph=new Icon(icon,selected);int edge=Math.round(24*b.getResources().getDisplayMetrics().density);
        glyph.setBounds(0,0,edge,edge);b.setCompoundDrawables(null,glyph,null,null);
        b.setCompoundDrawablePadding(Math.round(4*b.getResources().getDisplayMetrics().density));
        b.setSelected(selected);
    }
    static final class Avatar extends ImageView {
        private final Paint paint=new Paint(3);private String initials="T";
        Avatar(Context c){super(c);setScaleType(ScaleType.CENTER_CROP);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        void name(String value){initials=CallPeerIdentity.initials(value);invalidate();}
        @Override protected void onDraw(Canvas c){
            float x=getWidth()/2f,y=getHeight()/2f,r=Math.min(x,y);
            paint.setShader(new LinearGradient(0,0,getWidth(),getHeight(),0xff346481,0xff14293f,Shader.TileMode.CLAMP));
            c.drawCircle(x,y,r,paint);paint.setShader(null);
            int save=c.save();Path clip=new Path();clip.addCircle(x,y,Math.max(0,r-3),Path.Direction.CW);c.clipPath(clip);
            if(getDrawable()!=null)super.onDraw(c);else{
                paint.setColor(Color.WHITE);paint.setTypeface(Typeface.create("sans-serif-medium",0));paint.setTextSize(r*.66f);paint.setTextAlign(Paint.Align.CENTER);
                c.drawText(initials,x,y-(paint.ascent()+paint.descent())/2,paint);
            }c.restoreToCount(save);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setColor(0x70b9e4ef);c.drawCircle(x,y,Math.max(0,r-1),paint);paint.setStyle(Paint.Style.FILL);
        }
    }
    static final class Halo extends View {
        final Paint paint=new Paint(3);
        Halo(Context c){super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas c){float x=getWidth()/2f,y=getHeight()/2f,r=Math.min(getWidth(),getHeight())*.48f;
            if(r<=0)return;paint.setShader(new RadialGradient(x,y,r,new int[]{0x303faeaf,0x0c3faeaf,0x003faeaf},null,Shader.TileMode.CLAMP));c.drawCircle(x,y,r,paint);paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(getResources().getDisplayMetrics().density);
            for(float scale:new float[]{.68f,.88f}){paint.setColor(0x183fc9b6);c.drawCircle(x,y,r*scale,paint);}paint.setStyle(Paint.Style.FILL);
        }
    }
    static final class Icon extends Drawable {
        final String kind;final boolean off;final Paint p=new Paint(3);
        Icon(String kind,boolean off){this.kind=kind;this.off=off;}
        @Override public void draw(Canvas c){int save=c.save();c.translate(getBounds().left,getBounds().top);c.scale(getBounds().width()/24f,getBounds().height()/24f);
            p.setColor(Color.WHITE);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.8f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);Path path=new Path();
            switch(kind){
                case "mic":c.drawRoundRect(9,3,15,15,3,3,p);path.moveTo(6,11);path.cubicTo(6,21,18,21,18,11);c.drawPath(path,p);c.drawLine(12,19,12,22,p);c.drawLine(9,22,15,22,p);break;
                case "speaker":path.moveTo(3,9);path.lineTo(7,9);path.lineTo(12,5);path.lineTo(12,19);path.lineTo(7,15);path.lineTo(3,15);path.close();c.drawPath(path,p);c.drawArc(10,6,22,18,-55,110,false,p);break;
                case "video":c.drawRoundRect(3,6,15,18,3,3,p);path.moveTo(15,10);path.lineTo(21,7);path.lineTo(21,17);path.lineTo(15,14);c.drawPath(path,p);break;
                case "swap":c.drawArc(4,4,20,20,210,150,false,p);c.drawArc(4,4,20,20,30,150,false,p);path.moveTo(16,8);path.lineTo(20,12);path.lineTo(22,7);path.moveTo(8,16);path.lineTo(4,12);path.lineTo(2,17);c.drawPath(path,p);break;
                default:
                    if("end".equals(kind)){c.rotate(135,12,12);}
                    path.moveTo(5,3);path.lineTo(9,3);path.lineTo(11,8);path.lineTo(8,10);path.cubicTo(9,13,11,15,14,16);path.lineTo(16,13);path.lineTo(21,15);path.lineTo(21,19);path.quadTo(21,22,17,21);path.cubicTo(9,19,5,15,3,7);path.quadTo(2,3,5,3);c.drawPath(path,p);
            }
            if(off&&("mic".equals(kind)||"video".equals(kind)))c.drawLine(3,3,21,21,p);c.restoreToCount(save);
        }
        @Override public void setAlpha(int a){p.setAlpha(a);}
        @Override public void setColorFilter(ColorFilter f){p.setColorFilter(f);}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
