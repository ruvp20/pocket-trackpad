package io.github.ruvp20.pockettrackpad;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

final class PadConnection {
    interface Listener {
        void ready(PadConnection connection);
        void ended(PadConnection connection, String message);
    }

    private final Socket socket = new Socket();
    private final ArrayBlockingQueue<Wire.Record> queue = new ArrayBlockingQueue<>(96);
    private final String host;
    private final int port;
    private final byte[] secret;
    private final Listener listener;
    private final Thread worker;
    private volatile boolean canceled;
    private volatile boolean connected;

    PadConnection(String host, int port, byte[] secret, Listener listener) {
        this.host = host;
        this.port = port;
        this.secret = secret.clone();
        this.listener = listener;
        worker = new Thread(this::run, "Pocket Trackpad connection");
    }

    void start() { worker.start(); }

    void send(int command, int x, int y) {
        if (connected && !canceled && !queue.offer(new Wire.Record(command, x, y))) {
            // An input backlog makes dragging and clicks unpredictable. End that session.
            closeSocket();
        }
    }

    void close() {
        canceled = true;
        worker.interrupt();
        closeSocket();
    }

    private void closeSocket() {
        try { socket.close(); } catch (IOException ignored) {}
    }

    private void run() {
        String message = "The Mac disconnected. Check Wi-Fi and try again.";
        try {
            socket.connect(new InetSocketAddress(host, port), 4000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(4000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            byte[] client = Wire.challenge();
            out.write(Wire.MAGIC);
            out.write(client);
            if (!Arrays.equals(Wire.MAGIC, read(in, Wire.MAGIC.length))) {
                throw new GeneralSecurityException("Version mismatch");
            }
            byte[] server = read(in, Wire.CHALLENGE_SIZE);
            Wire.Channel toMac = new Wire.Channel(Wire.key(secret, client, server, true));
            Wire.Channel fromMac = new Wire.Channel(Wire.key(secret, client, server, false));
            out.write(toMac.seal(new Wire.Record(Wire.AUTHENTICATE)));
            Wire.Record reply = fromMac.open(read(in, Wire.SEALED_SIZE));
            if (reply.is(Wire.BUSY)) {
                message = "Another phone is connected. Disconnect it on the Mac.";
                return;
            }
            if (!reply.is(Wire.ACCEPTED)) throw new GeneralSecurityException("Invalid handshake");
            connected = true;
            socket.setSoTimeout(2500);
            listener.ready(this);
            long nextHeartbeat = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (!canceled) {
                long timeLeft = nextHeartbeat - System.nanoTime();
                if (timeLeft <= 0) {
                    out.write(toMac.seal(new Wire.Record(Wire.HEARTBEAT)));
                    if (!fromMac.open(read(in, Wire.SEALED_SIZE)).is(Wire.HEARTBEAT)) {
                        throw new GeneralSecurityException("Invalid heartbeat");
                    }
                    nextHeartbeat = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                    continue;
                }
                Wire.Record record = queue.poll(timeLeft, TimeUnit.NANOSECONDS);
                if (record != null) out.write(toMac.seal(record));
            }
        } catch (GeneralSecurityException | EOFException error) {
            if (!connected) message = "Pairing was rejected. Scan the current code on the Mac.";
        } catch (SocketTimeoutException error) {
            if (!connected) message = "The Mac did not respond. Check the address and Wi-Fi.";
        } catch (IOException error) {
            if (!connected) message = "Could not reach the Mac. Check its receiver and Wi-Fi.";
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            connected = false;
            Arrays.fill(secret, (byte) 0);
            closeSocket();
            if (!canceled) listener.ended(this, message);
        }
    }

    private static byte[] read(DataInputStream in, int size) throws IOException {
        byte[] bytes = new byte[size];
        in.readFully(bytes);
        return bytes;
    }
}
