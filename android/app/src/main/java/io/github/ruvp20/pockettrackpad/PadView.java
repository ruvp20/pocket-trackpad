package io.github.ruvp20.pockettrackpad;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

@SuppressLint("ViewConstructor") // The trackpad is created in code with an input sink.
final class PadView extends View {
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private float[] grid = new float[0];
    private final float density;
    private final GestureTracker tracker;
    private final GestureTracker.Output output;
    private final Runnable pendingTap;
    private final float[] touchX = new float[4], touchY = new float[4];
    private int visibleTouches;

    PadView(Context context, GestureTracker.Output output) {
        super(context);
        this.output = output;
        density = getResources().getDisplayMetrics().density;
        tracker = new GestureTracker((command, x, y) -> {
            if (command == Wire.CLICK) performClick();
            else output.send(command, x, y);
        });
        pendingTap = () -> tracker.advance(SystemClock.uptimeMillis());
        setFocusable(true);
        setClickable(true);
        setContentDescription("Trackpad surface");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void configure(boolean natural, boolean threeDrag, boolean pageSwipes, float speed) {
        tracker.configure(natural, threeDrag, pageSwipes, speed);
    }

    void reset() {
        removeCallbacks(pendingTap);
        tracker.cancel();
        visibleTouches = 0;
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        reset();
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int w, int h, int oldWidth, int oldHeight) {
        super.onSizeChanged(w, h, oldWidth, oldHeight);
        bounds.set(density, density, w - density, h - density);
        float step = 19 * density;
        int columns = Math.max(0, (int) ((w - 13 * density) / step));
        int rows = Math.max(0, (int) ((h - 13 * density) / step));
        grid = new float[columns * rows * 2];
        int index = 0;
        for (int row = 1; row <= rows; row++) {
            for (int column = 1; column <= columns; column++) {
                grid[index++] = column * step;
                grid[index++] = row * step;
            }
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight(), radius = 20 * density;
        ink.setStyle(Paint.Style.FILL);
        ink.setColor(MainActivity.PAD);
        canvas.drawRoundRect(bounds, radius, radius, ink);
        ink.setColor(MainActivity.GRID);
        ink.setStrokeWidth(1.7f * density);
        ink.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawPoints(grid, ink);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(density);
        ink.setColor(isFocused() ? MainActivity.ACCENT : MainActivity.EDGE);
        canvas.drawRoundRect(bounds, radius, radius, ink);
        for (int i = 0; i < visibleTouches; i++) {
            // Light the grid around each contact, keeping the response under the fingertip.
            float reach = 62 * density;
            ink.setStyle(Paint.Style.FILL);
            for (int point = 0; point < grid.length; point += 2) {
                float dx = grid[point] - touchX[i], dy = grid[point + 1] - touchY[i];
                float squared = dx * dx + dy * dy;
                if (squared >= reach * reach) continue;
                float glow = 1 - squared / (reach * reach);
                ink.setColor(MainActivity.ACCENT);
                ink.setAlpha((int) (190 * glow));
                canvas.drawCircle(grid[point], grid[point + 1], (1 + 2 * glow) * density, ink);
            }
            ink.setColor(MainActivity.ACCENT);
            canvas.drawCircle(touchX[i], touchY[i], 3 * density, ink);
        }
        if (visibleTouches == 0) {
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeWidth(1.25f * density);
            ink.setColor(MainActivity.SUBTLE);
            for (int offset = -1; offset <= 1; offset++) {
                canvas.drawCircle(w * 0.5f + offset * 16 * density, h * 0.5f, 3.3f * density, ink);
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility") // Recognized taps call performClick via the tracker.
    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL) {
            reset();
            return true;
        }
        int count = event.getPointerCount();
        float x = 0, y = 0, span = 0;
        for (int i = 0; i < count; i++) {
            x += event.getX(i);
            y += event.getY(i);
        }
        x /= count;
        y /= count;
        for (int i = 0; i < count; i++) {
            span += (float) Math.hypot(event.getX(i) - x, event.getY(i) - y);
        }
        span /= count;
        float angle = count == 2 ? (float) Math.toDegrees(Math.atan2(
                event.getY(1) - event.getY(0), event.getX(1) - event.getX(0))) : 0;
        long time = event.getEventTime();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                tracker.begin(time, x / density, y / density, getWidth() / density);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                tracker.moreFingers(time, count, x / density, y / density, span / density, angle);
                break;
            case MotionEvent.ACTION_MOVE:
                tracker.move(time, x / density, y / density, span / density, angle);
                break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                tracker.lift(time, x / density, y / density, span / density, angle,
                        action == MotionEvent.ACTION_UP);
                if (action == MotionEvent.ACTION_UP && tracker.hasPendingTap()) {
                    removeCallbacks(pendingTap);
                    postDelayed(pendingTap, GestureTracker.DOUBLE_TAP_MS);
                }
                break;
            default: return true;
        }
        int skip = action == MotionEvent.ACTION_POINTER_UP ? event.getActionIndex() : -1;
        visibleTouches = 0;
        if (action != MotionEvent.ACTION_UP) {
            for (int i = 0; i < count && visibleTouches < touchX.length; i++) {
                if (i == skip) continue;
                touchX[visibleTouches] = event.getX(i);
                touchY[visibleTouches++] = event.getY(i);
            }
        }
        invalidate();
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        output.send(Wire.CLICK, 0, 0);
        return true;
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                AccessibilityNodeInfo.ACTION_CLICK, "Click on Mac"));
    }
}
