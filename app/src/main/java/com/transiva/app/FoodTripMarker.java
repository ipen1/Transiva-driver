package com.transiva.app;

import android.content.Context;
import android.graphics.*;

/** Orange restaurant with cutlery and green destination; vehicle assets remain untouched. */
final class FoodTripMarker {
    static Bitmap bitmap(Context context,boolean restaurant) {
        return bitmap(context,restaurant,"food");
    }
    static Bitmap bitmap(Context context,boolean restaurant,String kind) {
        float density=context.getResources().getDisplayMetrics().density;
        Bitmap bitmap=Bitmap.createBitmap(Math.round(40*density),Math.round(52*density),Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);canvas.scale(density,density);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setStrokeJoin(Paint.Join.ROUND);
        Path path=new Path();path.moveTo(20,49);path.cubicTo(15,42,3,30,3,20);path.cubicTo(3,-2,37,-2,37,20);path.cubicTo(37,30,25,42,20,49);path.close();
        paint.setColor(Color.WHITE);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(5);canvas.drawPath(path,paint);
        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.parseColor(restaurant?"#FF850B":"#00A748"));canvas.drawPath(path,paint);
        paint.setColor(Color.WHITE);
        if(!restaurant){canvas.drawCircle(20,18,7,paint);return bitmap;}
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f);paint.setStrokeCap(Paint.Cap.ROUND);
        if(!"food".equals(kind)){
            if("shop".equals(kind)){
                canvas.drawRect(10,19,30,31,paint);canvas.drawLine(10,19,12,10,paint);canvas.drawLine(12,10,28,10,paint);canvas.drawLine(28,10,30,19,paint);
                for(int x=10;x<=26;x+=4)canvas.drawArc(x,16,x+4,22,0,180,false,paint);
                canvas.drawRect(17,24,23,31,paint);
            }else{
                Path cube=new Path();cube.moveTo(10,14);cube.lineTo(20,9);cube.lineTo(30,14);cube.lineTo(30,26);cube.lineTo(20,32);cube.lineTo(10,26);cube.close();canvas.drawPath(cube,paint);
                canvas.drawLine(10,14,20,20,paint);canvas.drawLine(30,14,20,20,paint);canvas.drawLine(20,20,20,32,paint);canvas.drawLine(15,11,25,17,paint);
            }
            return bitmap;
        }
        canvas.drawLine(14,11,14,32,paint);canvas.drawLine(10,11,10,17,paint);canvas.drawLine(18,11,18,17,paint);
        Path fork=new Path();fork.moveTo(10,17);fork.quadTo(14,23,18,17);canvas.drawPath(fork,paint);
        paint.setStyle(Paint.Style.FILL);canvas.drawOval(23,10,29,21,paint);
        paint.setStyle(Paint.Style.STROKE);canvas.drawLine(26,20,26,32,paint);
        return bitmap;
    }
}
