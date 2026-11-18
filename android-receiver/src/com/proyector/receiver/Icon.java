package com.proyector.receiver;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

final class Icon extends View {
    private final String name;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();

    Icon(Context context, String name, int tint) {
        super(context);
        this.name = name;
        paint.setColor(tint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.65f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void tint(int value) { paint.setColor(value); invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth() - getPaddingLeft() - getPaddingRight(),
                getHeight() - getPaddingTop() - getPaddingBottom());
        if (size <= 0) return;
        canvas.save();
        canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
        canvas.scale(size / 24f, size / 24f);
        shape.reset();
        switch (name) {
            case "home":
                shape.moveTo(3, 11); shape.lineTo(12, 3); shape.lineTo(21, 11);
                shape.moveTo(5, 10); shape.lineTo(5, 21); shape.lineTo(19, 21); shape.lineTo(19, 10);
                shape.moveTo(10, 21); shape.lineTo(10, 15); shape.lineTo(14, 15); shape.lineTo(14, 21);
                canvas.drawPath(shape, paint);
                break;
            case "screen":
                canvas.drawRoundRect(2, 4, 22, 17, 2, 2, paint);
                canvas.drawLine(12, 17, 12, 21, paint);
                canvas.drawLine(8, 21, 16, 21, paint);
                break;
            case "dual":
                shape.moveTo(11, 16); shape.lineTo(4, 16); shape.quadTo(2, 16, 2, 14);
                shape.lineTo(2, 6); shape.quadTo(2, 4, 4, 4); shape.lineTo(17, 4);
                shape.quadTo(19, 4, 19, 6); shape.lineTo(19, 7);
                canvas.drawPath(shape, paint);
                canvas.drawRoundRect(13, 9, 22, 22, 2, 2, paint);
                canvas.drawLine(16, 19, 19, 19, paint);
                break;
            case "touch":
                canvas.drawRoundRect(3, 3, 21, 21, 4, 4, paint);
                canvas.drawCircle(12, 10, 2.3f, paint);
                canvas.drawLine(12, 13, 12, 17, paint);
                break;
            case "sliders":
                canvas.drawLine(5, 4, 5, 8, paint);
                canvas.drawLine(5, 12, 5, 20, paint);
                canvas.drawCircle(5, 10, 2, paint);
                canvas.drawLine(12, 4, 12, 13, paint);
                canvas.drawLine(12, 17, 12, 20, paint);
                canvas.drawCircle(12, 15, 2, paint);
                canvas.drawLine(19, 4, 19, 6, paint);
                canvas.drawLine(19, 10, 19, 20, paint);
                canvas.drawCircle(19, 8, 2, paint);
                break;
            case "battery":
                canvas.drawRoundRect(3, 6, 20, 18, 2, 2, paint);
                canvas.drawLine(21, 10, 21, 14, paint);
                canvas.drawRoundRect(6, 9, 9, 15, 1, 1, paint);
                canvas.drawRoundRect(10, 9, 13, 15, 1, 1, paint);
                break;
            case "volume":
                shape.moveTo(3, 9); shape.lineTo(7, 9); shape.lineTo(12, 5);
                shape.lineTo(12, 19); shape.lineTo(7, 15); shape.lineTo(3, 15); shape.close();
                canvas.drawPath(shape, paint);
                canvas.drawArc(10, 7, 20, 17, -55, 110, false, paint);
                canvas.drawArc(9, 3, 24, 21, -55, 110, false, paint);
                break;
            case "latency":
                canvas.drawCircle(5, 18, 1.4f, paint);
                canvas.drawLine(9, 18, 12, 13, paint);
                canvas.drawLine(12, 13, 15, 15, paint);
                canvas.drawLine(15, 15, 20, 7, paint);
                canvas.drawCircle(20, 7, 1.4f, paint);
                break;
            case "link":
                canvas.save();
                canvas.rotate(-42, 12, 12);
                canvas.drawRoundRect(2, 8, 10, 16, 4, 4, paint);
                canvas.drawRoundRect(14, 8, 22, 16, 4, 4, paint);
                canvas.drawLine(8, 12, 16, 12, paint);
                canvas.restore();
                break;
            default: break;
        }
        canvas.restore();
    }
}
