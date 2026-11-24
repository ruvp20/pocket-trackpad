package io.github.ruvp20.pockettrackpad;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class Wire {
    static final byte[] MAGIC = {'P', 'T', 'P', '1'};
    static final int CHALLENGE_SIZE = 16;
    static final int SEALED_SIZE = 28;
    static final int PORT = 58432;

    static final int AUTHENTICATE = 1, ACCEPTED = 2, BUSY = 3, HEARTBEAT = 4;
    static final int MOVE = 10, SCROLL = 11, CLICK = 12, RIGHT_CLICK = 13;
    static final int DRAG_START = 14, DRAG_END = 15, ACTION = 16;
    static final int ZOOM_IN = 1, ZOOM_OUT = 2, SMART_ZOOM = 3, ROTATE_LEFT = 4, ROTATE_RIGHT = 5;
    static final int BACK = 6, FORWARD = 7, MISSION_CONTROL = 8, APP_WINDOWS = 9;
    static final int SPACE_LEFT = 10, SPACE_RIGHT = 11, LOOKUP = 12;
    static final int APPS = 13, DESKTOP = 14, NOTIFICATIONS = 15;

    private Wire() {}

    static byte[] challenge() {
        byte[] bytes = new byte[CHALLENGE_SIZE];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    static byte[] key(byte[] secret, byte[] client, byte[] server, boolean fromPhone)
            throws GeneralSecurityException {
        byte[] salt = ByteBuffer.allocate(2 * CHALLENGE_SIZE).put(client).put(server).array();
        // HKDF-SHA256 extract, then the single 32-byte expand block.
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] extracted = mac.doFinal(secret);
        mac.init(new SecretKeySpec(extracted, "HmacSHA256"));
        Arrays.fill(extracted, (byte) 0);
        mac.update((fromPhone ? "PTP1 phone-to-mac" : "PTP1 mac-to-phone").getBytes(StandardCharsets.US_ASCII));
        return mac.doFinal(new byte[]{1});
    }

    static final class Record {
        final int command;
        final int x;
        final int y;

        Record(int command) { this(command, 0, 0); }

        Record(int command, int x, int y) {
            this.command = command;
            this.x = x;
            this.y = y;
        }

        boolean is(int expected) { return command == expected && x == 0 && y == 0; }

        byte[] bytes() { return ByteBuffer.allocate(12).putInt(command << 24).putInt(x).putInt(y).array(); }
    }

    static final class Channel {
        private final SecretKeySpec key;
        private final Cipher cipher;
        private long counter;

        Channel(byte[] key) throws GeneralSecurityException {
            this.key = new SecretKeySpec(key, "AES");
            cipher = Cipher.getInstance("AES/GCM/NoPadding");
        }

        private void init(int mode) throws GeneralSecurityException {
            if (counter >= 0xffff_ffffL) throw new GeneralSecurityException("Session expired");
            byte[] nonce = ByteBuffer.allocate(12).putInt(0).putLong(counter).array();
            cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        }

        byte[] seal(Record record) throws GeneralSecurityException {
            init(Cipher.ENCRYPT_MODE);
            byte[] sealed = cipher.doFinal(record.bytes());
            counter++;
            return sealed;
        }

        Record open(byte[] sealed) throws GeneralSecurityException {
            if (sealed.length != SEALED_SIZE) throw new GeneralSecurityException("Invalid frame length");
            init(Cipher.DECRYPT_MODE);
            ByteBuffer bytes = ByteBuffer.wrap(cipher.doFinal(sealed));
            int header = bytes.getInt();
            if ((header & 0x00ff_ffff) != 0) throw new GeneralSecurityException("Invalid frame header");
            Record record = new Record(header >>> 24, bytes.getInt(), bytes.getInt());
            counter++;
            return record;
        }
    }
}
