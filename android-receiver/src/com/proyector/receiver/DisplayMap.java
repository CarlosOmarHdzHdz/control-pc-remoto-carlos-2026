package com.proyector.receiver;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Indicates which end of the extended desktop is available. */
final class DisplayMap extends View {
    private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ValueAnimator reveal = ValueAnimator.ofFloat(0, 1);
    private boolean connected, extended;
    private float progress = 1f;

    DisplayMap(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        pen.setStrokeCap(Paint.Cap.ROUND);
        pen.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        reveal.setDuration(320);
        reveal.setInterpolator(new DecelerateInterpolator());
        reveal.addUpdateListener(a -> { progress = (float) a.getAnimatedValue(); invalidate(); });
    }

    void status(boolean hasPc, boolean ready) {
        if (connected == hasPc && extended == ready) return;
        connected = hasPc;
        extended = ready;
        enter();
    }

    void enter() {
        reveal.cancel();
        if (isShown() && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())) reveal.start();
        else { progress = 1f; invalidate(); }
    }

    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (visibility != VISIBLE && reveal != null) { reveal.cancel(); progress = 1f; }
    }

    @Override protected void onDetachedFromWindow() { reveal.cancel(); super.onDetachedFromWindow(); }

    @Override protected void onDraw(Canvas c) {
        float scale = Math.min(getWidth() / 284f, getHeight() / 104f);
        c.save();
        c.translate((getWidth() - 284 * scale) / 2f, (getHeight() - 104 * scale) / 2f);
        c.scale(scale, scale);
        pen.setStyle(Paint.Style.FILL);
        pen.setColor(0x444F7195);
        for (int x = 6; x < 284; x += 12) {
            for (int y = 5; y < 82; y += 12) c.drawCircle(x, y, .8f, pen);
        }
        int primary = connected ? 0xFFB1DAFF : 0xFFA2B6CA;
        int target = extended ? 0xFF9DF5D1 : 0xFF899FB4;
        screen(c, 14, 12, 118, 69, primary);
        pen.setColor(primary);
        c.drawLine(66, 70, 66, 77, pen);
        c.drawLine(49, 78, 83, 78, pen);
        pen.setColor(0xFF6F8AA7);
        c.drawLine(125, 43, 180, 43, pen);
        if (connected) {
            pen.setColor(extended ? target : primary);
            c.drawLine(125, 43, 125 + 55 * progress, 43, pen);
        }
        c.drawLine(174, 38, 180, 43, pen);
        c.drawLine(174, 48, 180, 43, pen);
        screen(c, 188, 20, 272, 68, target);
        pen.setColor(target);
        c.drawLine(223, 63, 237, 63, pen);
        pen.setStyle(Paint.Style.FILL);
        pen.setTextAlign(Paint.Align.CENTER);
        pen.setColor(0xFFE5F2FF);
        pen.setTextSize(12);
        c.drawText("Windows", 66, 98, pen);
        c.drawText("Android", 230, 98, pen);
        c.restore();
    }

    private void screen(Canvas c, float l, float t, float r, float b, int edge) {
        pen.setStyle(Paint.Style.FILL);
        pen.setColor(0xFF1F3858);
        c.drawRoundRect(l, t, r, b, 5, 5, pen);
        pen.setStyle(Paint.Style.STROKE);
        pen.setStrokeWidth(2);
        pen.setColor(edge);
        c.drawRoundRect(l, t, r, b, 5, 5, pen);
    }
}
