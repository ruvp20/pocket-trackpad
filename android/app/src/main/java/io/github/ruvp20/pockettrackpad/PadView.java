package io.github.ruvp20.pockettrackpad;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.animation.ValueAnimator;
import android.os.Build;
import android.provider.Settings;
import java.util.Arrays;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

@SuppressLint("ViewConstructor") // The trackpad is created in code with an input sink.
final class PadView extends View {
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path();
    private boolean keyboardCorner;
    private float[] grid = new float[0];
    private final float density;
    private final GestureTracker tracker;
    private final GestureTracker.Output output;
    private final Runnable pendingTap;
    private final float[] touchX = new float[5], touchY = new float[5];
    private int visibleTouches;
    private float[] light = new float[0];
    private long lastFrame;
    private boolean animateLight = true;

    PadView(Context context, GestureTracker.Output output) {
        super(context);
        this.output = output;
        density = getResources().getDisplayMetrics().density;
        tracker = new GestureTracker((command, x, y) -> {
            if (command == Wire.CLICK) performClick();
            else output.send(command, x, y);
        });
        updateOrientation(getResources().getConfiguration());
        pendingTap = () -> tracker.advance(SystemClock.uptimeMillis());
        setFocusable(true);
        setClickable(true);
        setContentDescription("Trackpad surface");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    private void updateOrientation(Configuration configuration) {
        tracker.setLandscape(configuration.orientation == Configuration.ORIENTATION_LANDSCAPE);
    }

    @Override protected void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        updateOrientation(configuration);
        reset();
    }

    void configure(boolean natural, boolean threeDrag, boolean pageSwipes, float speed) {
        tracker.configure(natural, threeDrag, pageSwipes, speed);
    }

    void reserveKeyboardCorner() {
        keyboardCorner = true;
        updateOutline(getWidth(), getHeight());
    }

    private void updateOutline(int width, int height) {
        outline.rewind();
        if (width <= 2 * density || height <= 2 * density) return;
        float left = density, top = density, right = width - density, bottom = height - density;
        float radius = Math.min(32 * density, Math.min(right - left, bottom - top) / 3);
        if (!keyboardCorner || Math.min(width, height) < 100 * density) {
            outline.addRoundRect(left, top, right, bottom, radius, radius, Path.Direction.CW);
            return;
        }
        float notch = 60 * density, lip = 12 * density, scoop = 32 * density;
        outline.moveTo(left + radius, top);
        outline.lineTo(right - radius, top);
        outline.quadTo(right, top, right, top + radius);
        outline.lineTo(right, bottom - notch - lip);
        outline.quadTo(right, bottom - notch, right - lip, bottom - notch);
        outline.lineTo(right - notch + scoop, bottom - notch);
        outline.cubicTo(right - notch + scoop * 0.45f, bottom - notch,
                right - notch, bottom - notch + scoop * 0.45f, right - notch, bottom - notch + scoop);
        outline.lineTo(right - notch, bottom - lip);
        outline.quadTo(right - notch, bottom, right - notch - lip, bottom);
        outline.lineTo(left + radius, bottom);
        outline.quadTo(left, bottom, left, bottom - radius);
        outline.lineTo(left, top + radius);
        outline.quadTo(left, top, left + radius, top);
        outline.close();
    }

    void reset() {
        removeCallbacks(pendingTap);
        tracker.cancel();
        visibleTouches = 0;
        Arrays.fill(light, 0);
        lastFrame = 0;
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        reset();
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int w, int h, int oldWidth, int oldHeight) {
        super.onSizeChanged(w, h, oldWidth, oldHeight);
        updateOutline(w, h);
        float step = 19 * density;
        int columns = Math.max(0, (int) ((w - 13 * density) / step));
        int rows = Math.max(0, (int) ((h - 13 * density) / step));
        light = new float[columns * rows];
        grid = new float[light.length * 2];
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
        ink.setStyle(Paint.Style.FILL);
        ink.setColor(MainActivity.PAD);
        canvas.drawPath(outline, ink);
        canvas.save();
        canvas.clipPath(outline);
        ink.setColor(MainActivity.GRID);
        ink.setStrokeWidth(1.7f * density);
        ink.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawPoints(grid, ink);
        drawContactLight(canvas);
        canvas.restore();
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(density);
        ink.setColor(isFocused() ? MainActivity.ACCENT : MainActivity.EDGE);
        canvas.drawPath(outline, ink);
    }

    private void drawContactLight(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        long elapsed = lastFrame == 0 ? 16 : Math.min(64, now - lastFrame);
        lastFrame = now;
        float rise = animateLight ? (float) -Math.expm1(-elapsed / 80.0) : 1;
        float decay = animateLight ? (float) -Math.expm1(-elapsed / 145.0) : 1;
        float radius = 74 * density;
        float radiusSquared = radius * radius;
        boolean unsettled = false;
        ink.setStyle(Paint.Style.FILL);
        ink.setColor(MainActivity.ACCENT);
        for (int dot = 0; dot < light.length; dot++) {
            float x = grid[2 * dot], y = grid[2 * dot + 1];
            float target = 0;
            for (int i = 0; i < visibleTouches; i++) {
                float dx = x - touchX[i], dy = y - touchY[i];
                float proximity = Math.max(0, 1 - (dx * dx + dy * dy) / radiusSquared);
                target = Math.max(target, proximity * proximity);
            }
            float difference = target - light[dot];
            light[dot] += difference * (difference > 0 ? rise : decay);
            if (Math.abs(target - light[dot]) < 0.004f) light[dot] = target;
            else unsettled = true;
            if (light[dot] <= 0.004f) continue;
            ink.setAlpha(Math.round(225 * light[dot]));
            canvas.drawCircle(x, y, density * (0.9f + 2.6f * light[dot]), ink);
        }
        if (unsettled) postInvalidateOnAnimation();
        else lastFrame = 0;
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
                animateLight = Build.VERSION.SDK_INT >= 26 ? ValueAnimator.areAnimatorsEnabled()
                        : Settings.Global.getFloat(getContext().getContentResolver(),
                                Settings.Global.ANIMATOR_DURATION_SCALE, 1) > 0;
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
