package com.proyector.receiver;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

final class OrbitArtwork extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ValueAnimator sweep = ValueAnimator.ofFloat(0f, 1f);
    private float phase = 1f;
    private boolean connected;

    OrbitArtwork(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        sweep.setDuration(980);
        sweep.setInterpolator(new DecelerateInterpolator());
        sweep.addUpdateListener(animation -> {
            phase = (float) animation.getAnimatedValue();
            invalidate();
        });
    }

    void connectionChanged(boolean value) {
        if (value == connected) return;
        connected = value;
        enter();
    }

    void enter() {
        sweep.cancel();
        if (isShown() && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())) {
            sweep.start();
        } else {
            phase = 1f;
            invalidate();
        }
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE && sweep != null) {
            sweep.cancel();
            phase = 1f;
        }
    }

    @Override protected void onDetachedFromWindow() {
        sweep.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = getWidth() / 180f;
        canvas.save();
        canvas.scale(scale, scale);
        canvas.translate(108, 66);
        canvas.rotate(-32);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        for (int i = 0; i < 4; i++) {
            int radius = 31 + 14 * i;
            paint.setColor(0x587F69A5);
            canvas.drawOval(-radius, -radius * .64f, radius, radius * .64f, paint);
        }
        paint.setColor(connected ? 0xFFFFD8B4 : 0xFFFF896D);
        paint.setStrokeWidth(2);
        canvas.drawArc(-59, -38, 59, 38, -139, 32 + phase * 102, false, paint);
        double angle = Math.toRadians(-107 + phase * 102);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(59 * (float) Math.cos(angle), 38 * (float) Math.sin(angle), 5, paint);
        paint.setColor(0x33FFAE93);
        canvas.drawCircle(0, 0, 22, paint);
        paint.setColor(0xFFFFD8B4);
        canvas.drawCircle(0, 0, 10, paint);
        canvas.restore();
    }
}
