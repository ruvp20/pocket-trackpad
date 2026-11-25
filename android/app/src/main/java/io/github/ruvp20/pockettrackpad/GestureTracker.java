package io.github.ruvp20.pockettrackpad;

/** Classifies a touch cluster in density-independent pixels. */
final class GestureTracker {
    interface Output { void send(int command, int x, int y); }
    enum Mode { UNDECIDED, POINTER, DRAG, SCROLL, HORIZONTAL, ZOOM, ROTATE, SWIPE, FINISHED }

    static final int DOUBLE_TAP_MS = 285;
    private static final int TAP_MS = 215;
    private static final float TAP_DISTANCE = 10;
    private static final float SWIPE_DISTANCE = 47;

    private final Output output;
    private Mode mode = Mode.FINISHED;
    private int fingers;
    private int tapFingers;
    private long startedAt, clusterAt, lastSingleTap = -1000, pendingRightTap = -1;
    private float startX, startY, lastX, lastY, startSpan, startAngle, maximumTravel;
    private float lastTapX, lastTapY, scaleSent, padWidth;
    private boolean tapping, mayDrag, natural = true, threeFingerDrag, pageSwipes = true;
    private boolean landscape;
    private float speed = 1.35f;

    GestureTracker(Output output) { this.output = output; }

    void setLandscape(boolean enabled) {
        if (enabled == landscape) return;
        cancel(); // Release any held button before the window changes orientation.
        landscape = enabled;
    }

    void configure(boolean natural, boolean threeFingerDrag, boolean pageSwipes, float speed) {
        this.natural = natural;
        this.threeFingerDrag = threeFingerDrag;
        this.pageSwipes = pageSwipes;
        this.speed = speed;
    }

    void begin(long now, float x, float y, float width) {
        advance(now);
        fingers = tapFingers = 1;
        startedAt = now;
        tapping = true;
        padWidth = width;
        mayDrag = now - lastSingleTap <= DOUBLE_TAP_MS && distance(x - lastTapX, y - lastTapY) < 40;
        resetCluster(now, x, y, 0, 0);
    }

    void moreFingers(long now, int count, float x, float y, float span, float angle) {
        if (count > 5 || (count >= 3 && !landscape)) {
            cancel();
            return;
        }
        if (mode == Mode.FINISHED) {
            finishCluster();
            tapping = false;
            return;
        }
        if (mode == Mode.DRAG || mode == Mode.SWIPE) {
            // Keep an already recognized action from turning into a second action.
            finishCluster();
            tapping = false;
            return;
        }
        if (mode != Mode.UNDECIDED) tapping = false;
        fingers = tapFingers = count;
        if (count > 1) mayDrag = false;
        resetCluster(now, x, y, span, angle);
    }

    private void resetCluster(long now, float x, float y, float span, float angle) {
        mode = Mode.UNDECIDED;
        clusterAt = now;
        startX = lastX = x;
        startY = lastY = y;
        startSpan = span;
        startAngle = angle;
        maximumTravel = 0;
        scaleSent = 0;
    }

    void move(long now, float x, float y, float span, float angle) {
        advance(now);
        if (mode == Mode.FINISHED || mode == Mode.SWIPE) return;
        float dx = x - startX;
        float dy = y - startY;
        float travel = distance(dx, dy);
        maximumTravel = Math.max(travel, maximumTravel);
        float scale = startSpan >= 8 && span >= 8 ? (float) Math.log(span / startSpan) : 0;
        float rotation = (float) Math.IEEEremainder(angle - startAngle, 360);
        if (maximumTravel > TAP_DISTANCE || Math.abs(scale) > 0.09f || Math.abs(rotation) > 12) tapping = false;
        if (mode == Mode.UNDECIDED) classify(dx, dy, scale, rotation);
        switch (mode) {
            case POINTER:
            case DRAG: sendDelta(Wire.MOVE, (x - lastX) * speed, (y - lastY) * speed); break;
            case SCROLL: sendScroll(x - lastX, y - lastY); break;
            case HORIZONTAL: decideHorizontal(now, dx, dy); break;
            case ZOOM: sendZoom(scale); break;
            case ROTATE:
                if (Math.abs(rotation) >= 28) {
                    action(rotation < 0 ? Wire.ROTATE_LEFT : Wire.ROTATE_RIGHT);
                    mode = Mode.SWIPE;
                }
                break;
            default: break;
        }
        if (mode != Mode.UNDECIDED && mode != Mode.HORIZONTAL) {
            lastX = x;
            lastY = y;
        }
    }

    private void classify(float dx, float dy, float scale, float rotation) {
        float travel = distance(dx, dy);
        if (fingers == 1 && travel >= (mayDrag ? 7 : 2)) {
            mode = mayDrag ? Mode.DRAG : Mode.POINTER;
            if (mode == Mode.DRAG) send(Wire.DRAG_START);
        } else if (fingers == 2) {
            float pinchTravel = Math.abs(scale) * startSpan * 0.5f;
            float twistTravel = (float) Math.toRadians(Math.abs(rotation)) * startSpan * 0.5f;
            if (Math.abs(rotation) > 12 && twistTravel > Math.max(pinchTravel, travel * 0.8f)) {
                mode = Mode.ROTATE;
            } else if (Math.abs(scale) > 0.085f && pinchTravel > travel * 0.65f) {
                mode = Mode.ZOOM;
            } else if (travel >= 8) {
                boolean edgeSwipe = startX > padWidth - 43 && dx < 0;
                boolean pageSwipe = pageSwipes && Math.abs(dx) > Math.abs(dy) * 2;
                mode = edgeSwipe || pageSwipe ? Mode.HORIZONTAL : Mode.SCROLL;
            }
        } else if (fingers == 5) {
            // A scoop changes the radius of the cluster. Translating the whole hand should do nothing.
            if (Math.abs(scale) >= 0.20f && (Math.abs(scale) * startSpan) > Math.max(8, travel * 0.8f)) {
                action(scale < 0 ? Wire.APPS : Wire.DESKTOP);
                mode = Mode.SWIPE;
            }
        } else if (fingers == 3 || fingers == 4) {
            if (fingers == 3 && threeFingerDrag && travel >= 8) {
                mode = Mode.DRAG;
                send(Wire.DRAG_START);
            } else if (fingers == 4 && Math.abs(scale) >= 0.20 && Math.abs(scale) * startSpan > travel) {
                action(scale < 0 ? Wire.APPS : Wire.DESKTOP);
                mode = Mode.SWIPE;
            } else if (Math.max(Math.abs(dx), Math.abs(dy)) >= SWIPE_DISTANCE) {
                if (Math.abs(dx) > Math.abs(dy)) action(dx < 0 ? Wire.SPACE_RIGHT : Wire.SPACE_LEFT);
                else action(dy < 0 ? Wire.MISSION_CONTROL : Wire.APP_WINDOWS);
                mode = Mode.SWIPE;
            }
        }
    }

    private void decideHorizontal(long now, float dx, float dy) {
        boolean edge = startX > padWidth - 43 && dx < 0;
        if (edge && dx <= -50 && Math.abs(dx) > Math.abs(dy) * 1.6f) {
            action(Wire.NOTIFICATIONS);
            mode = Mode.SWIPE;
        } else if (!edge && Math.abs(dx) >= SWIPE_DISTANCE && now - clusterAt <= 180
                && Math.abs(dx) > Math.abs(dy) * 1.8f) {
            action(dx < 0 ? Wire.FORWARD : Wire.BACK);
            mode = Mode.SWIPE;
        } else if (now - clusterAt > (edge ? 260 : 180) || Math.abs(dy) * 1.5f > Math.abs(dx)) {
            mode = Mode.SCROLL;
            sendScroll(dx, dy);
        }
    }

    void lift(long now, float x, float y, float span, float angle, boolean allUp) {
        move(now, x, y, span, angle);
        if (mode == Mode.HORIZONTAL) {
            sendScroll(x - startX, y - startY);
            tapping = false;
        }
        finishCluster();
        if (!allUp) return;
        if (tapping && now - startedAt <= TAP_MS) {
            switch (tapFingers) {
                case 1:
                    flushRightTap();
                    send(Wire.CLICK);
                    lastSingleTap = now;
                    lastTapX = x;
                    lastTapY = y;
                    break;
                case 2:
                    if (pendingRightTap >= 0) {
                        pendingRightTap = -1;
                        action(Wire.SMART_ZOOM);
                    } else {
                        pendingRightTap = now;
                    }
                    break;
                case 3: action(Wire.LOOKUP); break;
                default: break;
            }
        } else {
            lastSingleTap = -1000;
        }
        fingers = 0;
        tapping = false;
    }

    void advance(long now) {
        if (pendingRightTap >= 0 && now - pendingRightTap >= DOUBLE_TAP_MS) flushRightTap();
    }

    void cancel() {
        finishCluster();
        fingers = 0;
        tapping = false;
        pendingRightTap = -1;
        lastSingleTap = -1000;
    }

    boolean hasPendingTap() { return pendingRightTap >= 0; }

    private void finishCluster() {
        if (mode == Mode.DRAG) send(Wire.DRAG_END);
        mode = Mode.FINISHED;
    }

    private void flushRightTap() {
        if (pendingRightTap < 0) return;
        pendingRightTap = -1;
        send(Wire.RIGHT_CLICK);
    }

    private void sendZoom(float scale) {
        float difference = scale - scaleSent;
        if (Math.abs(difference) >= 0.13f) {
            action(difference > 0 ? Wire.ZOOM_IN : Wire.ZOOM_OUT);
            scaleSent += Math.copySign(0.13f, difference);
        }
    }

    private void sendScroll(float dx, float dy) {
        int direction = natural ? 1 : -1;
        sendDelta(Wire.SCROLL, dx * direction, dy * direction);
    }

    private void sendDelta(int command, float dx, float dy) {
        int x = Math.round(dx * 1000);
        int y = Math.round(dy * 1000);
        if (x != 0 || y != 0) output.send(command, x, y);
    }

    private void send(int command) { output.send(command, 0, 0); }
    private void action(int action) { output.send(Wire.ACTION, action, 0); }
    private static float distance(float x, float y) { return (float) Math.hypot(x, y); }
}
