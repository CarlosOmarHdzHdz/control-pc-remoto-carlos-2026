package com.proyector.receiver;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Recent round trips for the current connection. */
final class LatencyHistory extends View {
    private final long[] history = new long[24];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path trace = new Path();
    private int size, next;

    LatencyHistory(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private int gridColor, traceColor;

    void colors(int grid, int line) {
        gridColor = grid;
        traceColor = line;
        invalidate();
    }

    void record(long millis) {
        if (millis < 0) return;
        history[next] = millis;
        next = (next + 1) % history.length;
        size = Math.min(size + 1, history.length);
        invalidate();
    }

    void clear() {
        size = next = 0;
        invalidate();
    }

    int samples() { return size; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float edge = 4 * density;
        float bottom = getHeight() - edge;
        float span = Math.max(1f, getWidth() - edge * 2);
        float height = Math.max(1f, bottom - edge);
        paint.setColor(gridColor);
        paint.setStrokeWidth(density);
        canvas.drawLine(edge, bottom, edge + span, bottom, paint);
        canvas.drawLine(edge, bottom - height / 2f, edge + span, bottom - height / 2f, paint);
        if (size == 0) return;
        int first = (next + history.length - size) % history.length;
        float max = 30f;
        for (int i = 0; i < size; i++) max = Math.max(max, history[(first + i) % history.length]);
        float x = edge, y = bottom;
        trace.reset();
        for (int i = 0; i < size; i++) {
            x = edge + span * i / Math.max(1, size - 1);
            y = bottom - height * history[(first + i) % history.length] / max;
            if (i == 0) trace.moveTo(x, y);
            else trace.lineTo(x, y);
        }
        paint.setColor(traceColor);
        paint.setStrokeWidth(2.4f * density);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawPath(trace, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(x, y, 3 * density, paint);
    }
}
