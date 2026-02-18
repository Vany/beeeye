package com.beeeye;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Minimal OSC 1.0 UDP listener. Zero external dependencies.
 * Dispatches face tracking quaternion to HeadTracker.
 *
 * Hot path is zero-alloc: known face-tracking addresses are matched and
 * dispatched without ArrayList or Number boxing. Unknown messages fall
 * through to the generic parser (blend shapes, etc.) which are ignored.
 */
public class OscListener {

    // OSC address byte prefixes — avoids String.equals() allocation on hot path
    // Full addresses: /data/faceTracking/face/rotation/{x,y,z,w}
    private static final byte[] PREFIX =
        "/data/faceTracking/face/rotation/".getBytes(StandardCharsets.US_ASCII);

    private volatile DatagramSocket socket;
    private volatile boolean running;

    // Quaternion component buffer — single OSC thread, no sync needed.
    // x/y/z buffered, complete Quat pushed to HeadTracker on /w arrival.
    private float bufQx, bufQy, bufQz;

    public void start(int port) {
        Beeeye.LOGGER.info("[Beeeye] OSC starting on port {}...", port);
        if (running) return;
        running = true;
        Thread thread = new Thread(() -> listen(port), "beeeye-osc");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    /** Stop current socket and restart on a new port. Safe to call from any thread. */
    public void restart(int port) {
        stop();
        running = true;
        Thread thread = new Thread(() -> listen(port), "beeeye-osc");
        thread.setDaemon(true);
        thread.start();
    }

    private void listen(int port) {
        try {
            socket = new DatagramSocket(port);
            Beeeye.LOGGER.info("[Beeeye] OSC listening on UDP port {}", port);
            byte[] buf = new byte[8192];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                try {
                    parse(
                        ByteBuffer.wrap(buf, 0, packet.getLength()).order(
                            ByteOrder.BIG_ENDIAN
                        )
                    );
                } catch (Exception e) {
                    Beeeye.LOGGER.warn("[Beeeye] OSC parse error", e);
                }
            }
        } catch (SocketException e) {
            if (running) Beeeye.LOGGER.error("[Beeeye] OSC socket error", e);
        } catch (Exception e) {
            Beeeye.LOGGER.error("[Beeeye] OSC listener error", e);
        } finally {
            if (socket != null && !socket.isClosed()) socket.close();
            Beeeye.LOGGER.info("[Beeeye] OSC listener stopped");
        }
    }

    private void parse(ByteBuffer buf) {
        if (buf.remaining() < 4) return;
        byte first = buf.get(buf.position());
        if (first == '#') {
            parseBundle(buf);
        } else if (first == '/') {
            parseMessage(buf);
        }
    }

    private void parseBundle(ByteBuffer buf) {
        readString(buf); // "#bundle"
        if (buf.remaining() < 8) return;
        buf.getLong(); // timetag
        while (buf.remaining() >= 4) {
            int size = buf.getInt();
            if (size <= 0 || size > buf.remaining()) break;
            int end = buf.position() + size;
            parse(buf);
            buf.position(end);
        }
    }

    private void parseMessage(ByteBuffer buf) {
        int addrStart = buf.position();

        // Fast path: check if address matches /data/faceTracking/face/rotation/{x,y,z,w}
        // Avoids String allocation for the 60+ Hz face tracking messages.
        if (matchesPrefix(buf, addrStart)) {
            // Address is PREFIX + one char component + null padding
            int componentOffset = addrStart + PREFIX.length;
            if (componentOffset < buf.limit()) {
                byte component = buf.get(componentOffset);
                // Skip past address (4-byte aligned), then type tag ",f\0\0", then read float
                int addrLen = addressLength(buf, addrStart);
                int addrEnd = (addrStart + addrLen + 4) & ~3;
                buf.position(addrEnd);
                if (buf.remaining() < 4) return; // type tag
                // Expect ",f" type tag
                int tagStart = buf.position();
                int tagLen = addressLength(buf, tagStart);
                if (tagLen >= 2) {
                    byte comma = buf.get(tagStart);
                    byte type = buf.get(tagStart + 1);
                    if (comma == ',' && type == 'f') {
                        buf.position((tagStart + tagLen + 4) & ~3);
                        if (buf.remaining() < 4) return;
                        float v = buf.getFloat();
                        dispatchRotation(component, v);
                        return;
                    }
                }
                // Type mismatch — fall through to generic (shouldn't happen)
                buf.position(addrStart);
            }
        }

        // Generic path for unknown / non-face-tracking messages
        String address = readString(buf);
        if (address == null || buf.remaining() < 4) return;
        String typeTags = readString(buf);
        if (
            typeTags == null || typeTags.isEmpty() || typeTags.charAt(0) != ','
        ) return;

        // Skip args — we don't act on unknown addresses
        skipArgs(buf, typeTags);
    }

    /** Dispatch a known face-tracking rotation component by its last path byte. */
    private void dispatchRotation(byte component, float v) {
        switch (component) {
            case 'x' -> bufQx = v;
            case 'y' -> bufQy = v;
            case 'z' -> bufQz = v;
            case 'w' -> HeadTracker.update(
                new HeadTracker.Quat(bufQx, bufQy, bufQz, v)
            );
        }
    }

    /** Check if buf at addrStart starts with PREFIX bytes. */
    private static boolean matchesPrefix(ByteBuffer buf, int addrStart) {
        if (buf.limit() - addrStart < PREFIX.length + 1) return false;
        for (int i = 0; i < PREFIX.length; i++) {
            if (buf.get(addrStart + i) != PREFIX[i]) return false;
        }
        return true;
    }

    /** Count bytes from position until null terminator. Does not advance buf. */
    private static int addressLength(ByteBuffer buf, int start) {
        int pos = start;
        while (pos < buf.limit() && buf.get(pos) != 0) pos++;
        return pos - start;
    }

    /** Skip over OSC arguments without allocating. */
    private static void skipArgs(ByteBuffer buf, String typeTags) {
        for (int i = 1; i < typeTags.length(); i++) {
            if (!buf.hasRemaining()) break;
            switch (typeTags.charAt(i)) {
                case 'i', 'f' -> buf.position(buf.position() + 4);
                case 'h', 'd' -> buf.position(buf.position() + 8);
                case 's' -> readString(buf);
                case 'b' -> {
                    int sz = buf.getInt();
                    buf.position((buf.position() + sz + 3) & ~3);
                }
                case 'T', 'F', 'N' -> {
                } // zero bytes
                default -> {
                    return;
                }
            }
        }
    }

    private static String readString(ByteBuffer buf) {
        int start = buf.position();
        while (buf.hasRemaining() && buf.get() != 0) {}
        int len = buf.position() - 1 - start;
        byte[] bytes = new byte[len];
        buf.position(start);
        buf.get(bytes);
        buf.position((start + len + 4) & ~3);
        return new String(bytes, StandardCharsets.US_ASCII);
    }
}
