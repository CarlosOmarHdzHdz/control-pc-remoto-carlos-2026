package com.proyector.receiver;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.animation.ValueAnimator;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

final class Icon extends View {
    private String name;
    private int level = -1;
    private float drawnLevel = -1;
    private boolean charging;
    private ValueAnimator motion;
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

    void battery(int percent, boolean onPower, boolean animate) {
        if (level == percent && charging == onPower) return;
        level = percent;
        charging = onPower;
        if (motion != null) motion.cancel();
        if (!animate || drawnLevel < 0 || percent < 0 || !isShown()) {
            drawnLevel = percent;
            invalidate();
            return;
        }
        motion = ValueAnimator.ofFloat(drawnLevel, percent);
        motion.setDuration(220);
        motion.addUpdateListener(value -> { drawnLevel = (Float) value.getAnimatedValue(); invalidate(); });
        motion.start();
    }

    void symbol(String next) {
        if (!name.equals(next)) { name = next; invalidate(); }
    }

    @Override protected void onDetachedFromWindow() {
        if (motion != null) motion.cancel();
        drawnLevel = level;
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth() - getPaddingLeft() - getPaddingRight(),
                getHeight() - getPaddingTop() - getPaddingBottom());
        if (size <= 0) return;
        canvas.save();
        canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
        canvas.scale(size / 24f, size / 24f);
        drawGlyph(canvas, paint, shape, name, drawnLevel, charging);
        canvas.restore();
    }

    static Drawable drawable(String symbol, int tint, int size) {
        Drawable glyph = new Drawable() {
            final Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG);
            final Path path = new Path();
            { brush.setColor(tint); brush.setStyle(Paint.Style.STROKE); brush.setStrokeWidth(1.75f);
              brush.setStrokeCap(Paint.Cap.ROUND); brush.setStrokeJoin(Paint.Join.ROUND); }
            @Override public void draw(Canvas canvas) {
                canvas.save();
                canvas.translate(getBounds().left, getBounds().top);
                canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
                drawGlyph(canvas, brush, path, symbol, -1, false);
                canvas.restore();
            }
            @Override public void setAlpha(int alpha) { brush.setAlpha(alpha); invalidateSelf(); }
            @Override public void setColorFilter(ColorFilter filter) { brush.setColorFilter(filter); invalidateSelf(); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
        glyph.setBounds(0, 0, size, size);
        return glyph;
    }

    private static void drawGlyph(Canvas canvas, Paint paint, Path shape, String name, float level, boolean charging) {
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
                paint.setStyle(Paint.Style.FILL);
                if (level > 0) canvas.drawRoundRect(5, 8, 5 + 13 * level / 100f, 16, 1, 1, paint);
                paint.setStyle(Paint.Style.STROKE);
                if (charging) {
                    shape.moveTo(10, 2); shape.lineTo(8, 5); shape.lineTo(12, 5); shape.lineTo(10, 8);
                    canvas.drawPath(shape, paint);
                }
                break;
            case "mute":
            case "volume-low":
            case "volume":
                shape.moveTo(3, 9); shape.lineTo(7, 9); shape.lineTo(12, 5);
                shape.lineTo(12, 19); shape.lineTo(7, 15); shape.lineTo(3, 15); shape.close();
                canvas.drawPath(shape, paint);
                if (name.equals("mute")) {
                    canvas.drawLine(16, 10, 21, 15, paint);
                    canvas.drawLine(21, 10, 16, 15, paint);
                } else {
                    canvas.drawArc(10, 7, 20, 17, -55, 110, false, paint);
                    if (name.equals("volume")) canvas.drawArc(9, 3, 24, 21, -55, 110, false, paint);
                }
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
            case "theme":
                canvas.drawCircle(12, 12, 8.5f, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawArc(3.5f, 3.5f, 20.5f, 20.5f, -90, 180, true, paint);
                paint.setStyle(Paint.Style.STROKE);
                break;
            case "wifi":
                canvas.drawArc(-2, 5, 26, 33, 225, 90, false, paint);
                canvas.drawArc(3, 10, 21, 28, 225, 90, false, paint);
                canvas.drawCircle(12, 18, 1.2f, paint);
                break;
            case "usb":
                canvas.drawRoundRect(6, 7, 18, 14, 2, 2, paint);
                canvas.drawRoundRect(8, 3, 16, 7, 1, 1, paint);
                shape.moveTo(12, 14); shape.lineTo(12, 17); shape.quadTo(12, 22, 6, 21);
                canvas.drawPath(shape, paint);
                break;
            case "globe":
                canvas.drawCircle(12, 12, 9, paint);
                canvas.drawOval(8, 3, 16, 21, paint);
                canvas.drawLine(4, 12, 20, 12, paint);
                break;
            case "lock":
                canvas.drawRoundRect(5, 10, 19, 21, 2, 2, paint);
                canvas.drawArc(8, 2, 16, 16, 180, 180, false, paint);
                canvas.drawLine(12, 14, 12, 17, paint);
                break;
            case "sleep":
                shape.moveTo(19, 15); shape.cubicTo(13, 18, 6, 12, 9, 5);
                shape.cubicTo(-2, 9, 5, 26, 17, 18); shape.close();
                canvas.drawPath(shape, paint);
                break;
            case "power":
                canvas.drawLine(12, 2, 12, 12, paint);
                canvas.drawArc(4, 4, 20, 21, -52, 284, false, paint);
                break;
            case "restart":
                canvas.drawArc(4, 4, 20, 20, -78, 300, false, paint);
                shape.moveTo(3, 7); shape.lineTo(4, 13); shape.lineTo(9, 10);
                canvas.drawPath(shape, paint);
                break;
            case "play":
            case "prev":
            case "next":
                canvas.save();
                if (name.equals("prev")) canvas.scale(-1, 1, 12, 12);
                shape.moveTo(6, 5); shape.lineTo(16, 12); shape.lineTo(6, 19); shape.close();
                canvas.drawPath(shape, paint);
                if (!name.equals("play")) canvas.drawLine(19, 5, 19, 19, paint);
                canvas.restore();
                break;
            case "eye":
                shape.moveTo(2, 12); shape.quadTo(12,-2,22,12); shape.quadTo(12,26,2,12);
                canvas.drawPath(shape, paint);
                canvas.drawCircle(12, 12, 3, paint);
                break;
            case "keyboard":
                canvas.drawRoundRect(2, 5, 22, 19, 3, 3, paint);
                for (int x = 6; x <= 18; x += 4) {
                    canvas.drawPoint(x, 9, paint);
                    canvas.drawPoint(x, 12, paint);
                }
                canvas.drawLine(8, 15.5f, 16, 15.5f, paint);
                break;
            case "camera":
                shape.moveTo(2, 7); shape.lineTo(7, 7); shape.lineTo(9, 4); shape.lineTo(15, 4);
                shape.lineTo(17, 7); shape.lineTo(22, 7); shape.lineTo(22, 20); shape.lineTo(2, 20); shape.close();
                canvas.drawPath(shape, paint);
                canvas.drawCircle(12, 13, 3.5f, paint);
                break;
            case "rotate":
                canvas.drawRoundRect(7, 6, 17, 18, 2, 2, paint);
                canvas.drawArc(3, 2, 21, 22, -90, 83, false, paint);
                shape.moveTo(17, 8); shape.lineTo(21, 11); shape.lineTo(23, 7);
                canvas.drawPath(shape, paint);
                break;
            case "exit":
                canvas.drawRoundRect(4, 4, 15, 20, 2, 2, paint);
                canvas.drawLine(11, 12, 22, 12, paint);
                shape.moveTo(18, 8); shape.lineTo(22, 12); shape.lineTo(18, 16);
                canvas.drawPath(shape, paint);
                break;
            default: break;
        }
    }
}
