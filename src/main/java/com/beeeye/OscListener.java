package com.beeeye;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal OSC 1.0 UDP listener. Zero external dependencies.
 * Dispatches face tracking quaternion to HeadTracker.
 */
public class OscListener {

    private volatile DatagramSocket socket;
    private volatile boolean running;

    // Quaternion component buffer (single-threaded listener, no sync needed).
    // Data OSC sends x, y, z, w as separate messages in a bundle.
    // We buffer x/y/z and push complete Quat to HeadTracker on /w arrival.
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
        } catch (Exception e) {
            if (running) {
                Beeeye.LOGGER.error("[Beeeye] OSC listener error", e);
            }
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
        String address = readString(buf);
        if (address == null || buf.remaining() < 4) return;
        String typeTags = readString(buf);
        if (
            typeTags == null || typeTags.isEmpty() || typeTags.charAt(0) != ','
        ) return;

        List<Object> args = new ArrayList<>();
        for (int i = 1; i < typeTags.length(); i++) {
            if (buf.remaining() < 1) break;
            switch (typeTags.charAt(i)) {
                case 'i' -> args.add(buf.getInt());
                case 'f' -> args.add(buf.getFloat());
                case 's' -> args.add(readString(buf));
                case 'b' -> args.add(readBlob(buf));
                case 'h' -> args.add(buf.getLong());
                case 'd' -> args.add(buf.getDouble());
                case 'T' -> args.add(true);
                case 'F' -> args.add(false);
                case 'N' -> args.add(null);
                default -> {
                    return;
                }
            }
        }

        // Data OSC face tracking — buffer x/y/z, push complete Quat on /w
        if (args.size() == 1 && args.get(0) instanceof Number n) {
            float v = n.floatValue();
            switch (address) {
                case "/data/faceTracking/face/rotation/x" -> bufQx = v;
                case "/data/faceTracking/face/rotation/y" -> bufQy = v;
                case "/data/faceTracking/face/rotation/z" -> bufQz = v;
                case "/data/faceTracking/face/rotation/w" -> HeadTracker.update(
                    new HeadTracker.Quat(bufQx, bufQy, bufQz, v)
                );
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

    private static byte[] readBlob(ByteBuffer buf) {
        int size = buf.getInt();
        byte[] data = new byte[size];
        buf.get(data);
        buf.position((buf.position() + 3) & ~3);
        return data;
    }
}
