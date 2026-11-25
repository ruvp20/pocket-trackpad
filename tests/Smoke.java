package io.github.ruvp20.pockettrackpad;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class Smoke {
    private Smoke() {}

    public static void main(String[] arguments) throws Exception {
        List<String> packets = new ArrayList<>();
        HidReports reports = new HidReports((id, payload) -> packets.add(id + Arrays.toString(payload)));
        reports.send(Wire.DRAG_START, 0, 0);
        reports.send(Wire.MOVE, -1250, 2250);
        reports.send(Wire.DRAG_END, 0, 0);
        reports.send(Wire.ACTION, Wire.MISSION_CONTROL, 0);
        reports.media(4);
        check(packets.equals(Arrays.asList("1[1, 0, 0, 0, 0, 0, 0]", "1[1, -1, -1, 2, 0, 0, 0]",
                "1[0, 0, 0, 0, 0, 0, 0]", "2[1, 82]", "2[0, 0]", "3[4]", "3[0]")), "Bluetooth input reports");
        check(HidReports.stroke('A') == 0x204 && HidReports.stroke('#') == 0x220, "US keyboard mapping");
        testGestures();
        testConnection(Integer.parseInt(arguments[0]));
    }

    private static void testGestures() {
        List<Integer> events = new ArrayList<>();
        GestureTracker gestures = new GestureTracker((command, x, y) ->
                events.add(command == Wire.ACTION ? 100 + x : command));
        gestures.setLandscape(true);
        gestures.begin(1000, 60, 30, 300);
        gestures.lift(1050, 60, 30, 0, 0, true);
        gestures.begin(1150, 60, 30, 300);
        gestures.move(1180, 80, 35, 0, 0);
        gestures.cancel();
        check(events.equals(Arrays.asList(Wire.CLICK, Wire.DRAG_START, Wire.MOVE, Wire.DRAG_END)),
                "Tap-drag and cancel");
        events.clear();

        twoFingerTap(gestures, 2000);
        gestures.advance(2400);
        check(events.equals(Arrays.asList(Wire.RIGHT_CLICK)), "Delayed secondary click");
        events.clear();
        twoFingerTap(gestures, 2500);
        twoFingerTap(gestures, 2660);
        check(events.equals(Arrays.asList(100 + Wire.SMART_ZOOM)), "Double two-finger tap");
        events.clear();

        gestures.begin(3500, 70, 50, 300);
        gestures.moreFingers(3520, 2, 100, 50, 30, 0);
        gestures.move(3550, 101, 70, 30, 0);
        gestures.lift(3570, 101, 70, 30, 0, false);
        gestures.lift(3580, 100, 70, 0, 0, true);
        check(events.equals(Arrays.asList(Wire.SCROLL)), "Two-finger scroll through staggered lift");
        events.clear();

        gestures.begin(4000, 70, 90, 300);
        gestures.moreFingers(4020, 3, 100, 90, 35, 0);
        gestures.move(4100, 101, 40, 35, 0);
        gestures.lift(4120, 101, 40, 35, 0, false);
        gestures.lift(4150, 101, 40, 0, 0, true);
        check(events.equals(Arrays.asList(100 + Wire.MISSION_CONTROL)), "Single Mission Control action");
        events.clear();

        gestures.begin(5000, 70, 50, 300);
        gestures.moreFingers(5020, 2, 100, 50, 30, 0);
        gestures.move(5050, 100, 50, 42, 0);
        gestures.cancel();
        check(events.equals(Arrays.asList(100 + Wire.ZOOM_IN)), "Pinch zoom");
        events.clear();

        gestures.begin(6000, 70, 50, 300);
        gestures.moreFingers(6020, 2, 100, 50, 30, 0);
        gestures.move(6050, 100, 50, 30, -32);
        gestures.cancel();
        check(events.equals(Arrays.asList(100 + Wire.ROTATE_LEFT)), "Rotate counterclockwise");
        events.clear();

        gestures.begin(7000, 70, 50, 300);
        gestures.moreFingers(7020, 4, 100, 50, 40, 0);
        gestures.move(7050, 100, 50, 28, 0);
        gestures.cancel();
        check(events.equals(Arrays.asList(100 + Wire.APPS)), "Four-finger pinch");
        events.clear();
        gestures.configure(true, true, true, 1);
        gestures.begin(8000, 80, 100, 500);
        gestures.moreFingers(8010, 3, 110, 100, 40, 0);
        gestures.move(8030, 130, 105, 40, 0);
        gestures.setLandscape(false);
        gestures.lift(8090, 120, 100, 0, 0, true);
        check(events.equals(Arrays.asList(Wire.DRAG_START, Wire.MOVE, Wire.DRAG_END)),
                "Rotating releases a landscape drag without clicking");
        events.clear();

        for (int count = 3; count <= 5; count++) {
            gestures.begin(9000, 40, 50, 300);
            for (int n = 2; n <= count; n++) gestures.moreFingers(9010 + n, n, 80, 50, 40, 0);
            gestures.move(9040, 160, 90, 19, 0);
            gestures.lift(9050, 160, 90, 19, 0, false);
            gestures.move(9060, 250, 90, 0, 0);
            gestures.lift(9070, 250, 90, 0, 0, true);
            gestures.advance(9400);
        }
        check(events.isEmpty(), "Portrait blocks multi-finger actions through the final lift");
        gestures.setLandscape(true);
        for (int direction : new int[]{-1, 1}) {
            gestures.begin(10000, 40, 50, 500);
            for (int n = 2; n <= 5; n++) gestures.moreFingers(10000 + n * 10, n, 150, 100, 60, 0);
            gestures.move(10100, 150, 100, 60 + direction * 24, 0);
            gestures.move(10150, 150, 100, 60 + direction * 30, 0);
            gestures.lift(10200, 150, 100, 60 + direction * 30, 0, true);
        }
        check(events.equals(Arrays.asList(100 + Wire.APPS, 100 + Wire.DESKTOP)), "One action per five-finger scoop or spread");
        events.clear();
        gestures.begin(11000, 40, 50, 500);
        gestures.moreFingers(11030, 4, 150, 100, 60, 0);
        gestures.move(11100, 90, 100, 60, 0);
        gestures.lift(11150, 90, 100, 60, 0, true);
        check(events.equals(Arrays.asList(100 + Wire.SPACE_RIGHT)), "Four-finger Space swipe with drag enabled");
        System.out.println("Android recognizer: tap, drag, scroll, swipe, zoom and rotate passed");
    }

    private static void twoFingerTap(GestureTracker gestures, long time) {
        gestures.begin(time, 70, 50, 300);
        gestures.moreFingers(time + 20, 2, 100, 50, 30, 0);
        gestures.lift(time + 50, 100, 50, 30, 0, false);
        gestures.lift(time + 60, 70, 50, 0, 0, true);
    }

    private static void testConnection(int port) throws Exception {
        byte[] secret = new byte[16];
        for (int i = 0; i < secret.length; i++) secret[i] = (byte) i;

        Events bad = new Events();
        PadConnection rejected = new PadConnection("127.0.0.1", port, new byte[16], bad);
        rejected.start();
        check(bad.next().startsWith("Pairing was rejected"), "Wrong secret rejected");

        Events first = new Events();
        PadConnection client = new PadConnection("127.0.0.1", port, secret, first);
        client.start();
        try {
            check(first.next().equals("ready"), "First accepted");
            client.send(Wire.MOVE, 18_000, -7_500);
            client.send(Wire.DRAG_START, 0, 0);
            client.send(Wire.MOVE, 2_000, 3_000);

            Events busy = new Events();
            PadConnection competitor = new PadConnection("127.0.0.1", port, secret, busy);
            competitor.start();
            check(busy.next().startsWith("Another phone"), "Second controller refused");
            check(first.events.poll(7, TimeUnit.SECONDS) == null, "Encrypted heartbeat acknowledged");
        } finally {
            client.close();
        }

        // Allow the event loop to observe the closing TCP stream before reconnecting.
        Thread.sleep(200);
        Events second = new Events();
        PadConnection reconnect = new PadConnection("127.0.0.1", port, secret, second);
        reconnect.start();
        try {
            check(second.next().equals("ready"), "Reconnect accepted");
            reconnect.send(Wire.ACTION, Wire.MISSION_CONTROL, 0);
            check(second.next().startsWith("The Mac disconnected"), "Receiver shutdown detected");
        } finally {
            reconnect.close();
        }

        System.out.println("Android client: wrong-code rejection, pairing, heartbeat and reconnect passed");
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static final class Events implements PadConnection.Listener {
        final LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        @Override public void ready(PadConnection connection) { events.add("ready"); }
        @Override public void ended(PadConnection connection, String message) { events.add(message); }
        String next() throws InterruptedException {
            String value = events.poll(7, TimeUnit.SECONDS);
            if (value == null) throw new AssertionError("Missing connection state");
            return value;
        }
    }
}
