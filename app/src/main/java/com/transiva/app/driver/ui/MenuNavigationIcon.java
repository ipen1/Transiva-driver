package com.transiva.app.driver.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

final class MenuNavigationIcon extends View {
    private final DriverBottomNavigation.ActiveItem item;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    MenuNavigationIcon(Context context, DriverBottomNavigation.ActiveItem item, int color) {
        super(context);
        this.item = item;
        paint.setColor(color);
        paint.setStrokeWidth(1.65f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.scale(getWidth() / 24f, getHeight() / 24f);
        path.reset();
        switch (item) {
            case HOME:
                path.moveTo(2, 11);
                path.lineTo(12, 2.5f);
                path.lineTo(22, 11);
                path.lineTo(19, 11);
                path.lineTo(19, 21);
                path.lineTo(14, 21);
                path.lineTo(14, 15);
                path.lineTo(10, 15);
                path.lineTo(10, 21);
                path.lineTo(5, 21);
                path.lineTo(5, 11);
                path.close();
                paint.setStyle(Paint.Style.FILL);
                canvas.drawPath(path, paint);
                paint.setStyle(Paint.Style.STROKE);
                break;
            case ACTIVITY:
                canvas.drawCircle(12, 12, 9, paint);
                canvas.drawLine(12, 6, 12, 12, paint);
                canvas.drawLine(12, 12, 16, 14.5f, paint);
                break;
            case CHAT:
                path.moveTo(8, 19);
                path.lineTo(3, 21);
                path.lineTo(4.5f, 16);
                path.cubicTo(0, 7, 7, 3, 12, 3);
                path.cubicTo(18, 3, 22, 6, 22, 11);
                path.cubicTo(22, 17, 17, 20, 8, 19);
                canvas.drawPath(path, paint);
                for (int x = 8; x <= 16; x += 4) canvas.drawPoint(x, 11, paint);
                break;
            case EARNINGS:
                canvas.drawRoundRect(3, 6, 21, 20, 3, 3, paint);
                canvas.drawLine(5, 3.5f, 17, 3.5f, paint);
                canvas.drawRoundRect(14, 10, 22, 16, 2, 2, paint);
                canvas.drawPoint(17.5f, 13, paint);
                break;
            case PROFILE:
                canvas.drawCircle(12, 7, 4, paint);
                path.moveTo(4, 21);
                path.lineTo(4, 18);
                path.cubicTo(4, 11, 20, 11, 20, 18);
                path.lineTo(20, 21);
                path.close();
                canvas.drawPath(path, paint);
                break;
        }
        canvas.restore();
    }
}
