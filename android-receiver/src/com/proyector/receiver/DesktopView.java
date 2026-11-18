package com.proyector.receiver;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.proyector.shared.Wire;

final class DesktopView extends View {
    interface Input {
        void send(int command, int... values);
        void keyboard();
    }

    private final Input input;
    private final RectF destination = new RectF();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final int slop;
    private Bitmap bitmap;
    private boolean panelMode;
    private float pointerSensitivity = 1.5f;
    private boolean moved, longPressed, pressed, twoFingers, secondTap, skipGesture;
    private float startX, startY, lastX, lastY, scrollY, relativeX, relativeY;
    private long lastMotion, lastTap;
    private final Runnable longPress = () -> { if (!moved && !twoFingers) longPressed = true; };

    DesktopView(Context context, Input input) {
        super(context);
        this.input = input;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setBackgroundColor(Color.BLACK);
        setContentDescription("Escritorio de Windows a pantalla completa");
    }

    void panelMode(boolean value) { endGesture(); panelMode = value; }
    void pointerSensitivity(int percent) { pointerSensitivity = Math.max(.5f, Math.min(3f, percent / 100f)); }

    void image(Bitmap next) {
        bitmap = next;
        measureImage();
        postInvalidateOnAnimation();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) { measureImage(); }

    private void measureImage() {
        if (bitmap == null) { destination.setEmpty(); return; }
        float scale = Math.min(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight());
        float width = bitmap.getWidth() * scale;
        float height = bitmap.getHeight() * scale;
        float left = (getWidth() - width) / 2f;
        float top = (getHeight() - height) / 2f;
        destination.set(left, top, left + width, top + height);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap != null) canvas.drawBitmap(bitmap, null, destination, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) return true;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            startX = lastX = event.getX();
            startY = lastY = event.getY();
            removeCallbacks(longPress);
            moved = longPressed = pressed = twoFingers = skipGesture = false;
            relativeX = relativeY = 0;
            secondTap = panelMode && SystemClock.uptimeMillis() - lastTap < ViewConfiguration.getDoubleTapTimeout();
            postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            removeCallbacks(longPress);
            release();
            moved = true;
            if (event.getPointerCount() >= 3) {
                if (!skipGesture) input.keyboard();
                skipGesture = true;
                twoFingers = false;
            } else {
                twoFingers = true;
                scrollY = (event.getY(0) + event.getY(1)) / 2f;
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (!skipGesture) move(event);
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            skipGesture = true;
            twoFingers = false;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (panelMode && !skipGesture) flushRelative();
            if (pressed && !panelMode) absolute(Wire.UP, event.getX(), event.getY(), true);
            else if (pressed) input.send(Wire.UP, -1, -1);
            else if (!moved && !skipGesture && action == MotionEvent.ACTION_UP) click(event.getX(), event.getY());
            pressed = false;
            endGesture();
        }
        return true;
    }

    private void move(MotionEvent event) {
        if (twoFingers && event.getPointerCount() >= 2) {
            float next = (event.getY(0) + event.getY(1)) / 2f;
            float step = 26 * getResources().getDisplayMetrics().density;
            int ticks = (int) ((scrollY - next) / step);
            if (ticks != 0) {
                int amount = Math.max(-12, Math.min(12, ticks));
                input.send(Wire.SCROLL, amount);
                scrollY -= amount * step;
            }
            return;
        }

        float x = event.getX();
        float y = event.getY();
        if (Math.hypot(x - startX, y - startY) > slop) { moved = true; removeCallbacks(longPress); }
        if (moved && panelMode) {
            if (!pressed && (secondTap || longPressed)) { input.send(Wire.DOWN, -1, -1); pressed = true; }
            relativeX += (x - lastX) * pointerSensitivity;
            relativeY += (y - lastY) * pointerSensitivity;
            if (SystemClock.uptimeMillis() - lastMotion >= 16) flushRelative();
        } else if (moved && !panelMode) {
            if (!pressed) pressed = absolute(Wire.DOWN, startX, startY, false);
            if (pressed && SystemClock.uptimeMillis() - lastMotion >= 16) {
                absolute(Wire.MOVE, x, y, true);
                lastMotion = SystemClock.uptimeMillis();
            }
        }
        lastX = x;
        lastY = y;
    }

    private void flushRelative() {
        int dx = Math.max(-1000, Math.min(1000, Math.round(relativeX)));
        int dy = Math.max(-1000, Math.min(1000, Math.round(relativeY)));
        relativeX = relativeY = 0;
        if (dx == 0 && dy == 0) return;
        input.send(Wire.RELATIVE, dx, dy);
        lastMotion = SystemClock.uptimeMillis();
    }

    private void click(float x, float y) {
        super.performClick();
        if (longPressed) {
            if (panelMode || absolute(Wire.MOVE, x, y, false)) input.send(Wire.RIGHT_CLICK);
        } else if (panelMode) {
            input.send(Wire.DOWN, -1, -1);
            input.send(Wire.UP, -1, -1);
            lastTap = SystemClock.uptimeMillis();
        } else if (absolute(Wire.DOWN, x, y, false)) {
            absolute(Wire.UP, x, y, false);
        }
    }

    private boolean absolute(int command, float x, float y, boolean clamp) {
        if (destination.isEmpty() || (!clamp && !destination.contains(x, y))) return false;
        int a = Math.round(Math.max(0f, Math.min(1f, (x - destination.left) / destination.width())) * Wire.COORDINATE_MAX);
        int b = Math.round(Math.max(0f, Math.min(1f, (y - destination.top) / destination.height())) * Wire.COORDINATE_MAX);
        input.send(command, a, b);
        return true;
    }

    private void release() {
        if (pressed) input.send(Wire.UP, -1, -1);
        pressed = false;
    }

    void endGesture() {
        removeCallbacks(longPress);
        release();
        skipGesture = true;
        relativeX = relativeY = 0;
    }
}
