package com.proyector.receiver;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.widget.SeekBar;

/** Native seek interaction and accessibility, with a segmented volume scale. */
final class VolumeFader extends SeekBar {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final ValueAnimator levelMotion = new ValueAnimator();
    private int target = -1;
    private float displayed;

    VolumeFader(Context context) {
        super(context);
        int inset = Math.round(10 * getResources().getDisplayMetrics().density);
        setPadding(inset, 0, inset, 0);
        levelMotion.setDuration(165);
        levelMotion.addUpdateListener(a -> { displayed = (float) a.getAnimatedValue(); invalidate(); });
    }

    void followFinger(int value) {
        target = value;
        levelMotion.cancel();
        displayed = value;
        invalidate();
    }

    void reading(int value) {
        setProgress(value);
        if (value == target) return;
        if (target < 0 || !isShown() || !isEnabled() || (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled())) {
            followFinger(value);
            return;
        }
        target = value;
        levelMotion.cancel();
        levelMotion.setFloatValues(displayed, value);
        levelMotion.start();
    }

    @Override protected void onDetachedFromWindow() {
        levelMotion.cancel();
        displayed = getProgress();
        super.onDetachedFromWindow();
    }

    @Override protected synchronized void onDraw(Canvas c) {
        float density = getResources().getDisplayMetrics().density;
        float left = getPaddingLeft(), right = getWidth() - getPaddingRight();
        float span = right - left, middle = getHeight() * .5f;
        if (span <= 0) return;
        float fraction = displayed / getMax();
        c.save();
        if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) c.scale(-1, 1, getWidth() / 2f, middle);
        int segments = 28;
        for (int i = 0; i < segments; i++) {
            float half = (4 + 8f * i / (segments - 1)) * density;
            float x = left + span * i / segments;
            float end = left + span * (i + 1) / segments - density * 2;
            paint.setColor(isEnabled() && (i + .5f) / segments <= fraction ? 0xFF9DF5D1 : 0xFF49637D);
            c.drawRoundRect(x, middle - half, end, middle + half, density, density, paint);
        }
        if (isEnabled()) {
            float x = left + fraction * span;
            paint.setColor(isFocused() || isPressed() ? 0xFF9DF5D1 : 0xFFFFFFFF);
            c.drawRoundRect(x - 2.5f * density, middle - 15 * density,
                    x + 2.5f * density, middle + 15 * density, 2 * density, 2 * density, paint);
        }
        c.restore();
    }
}
