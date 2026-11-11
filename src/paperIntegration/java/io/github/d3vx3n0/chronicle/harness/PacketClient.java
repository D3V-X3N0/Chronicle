package io.github.d3vx3n0.chronicle.harness;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Minimal loopback-only protocol 777 client. IDs/codecs checked against pinned Paper 26.3.
 * This is an actual network player: all tested actions go through vanilla packet handling.
 * No encryption/compression, rendering, inventory simulation or production-server support.
 */
final class PacketClient implements AutoCloseable {
    static final String NAME = "ChronicleTest";
    static final UUID ACTOR = UUID.nameUUIDFromBytes(("OfflinePlayer:" + NAME).getBytes(StandardCharsets.UTF_8));
    private final Socket socket;
    private final Thread reader;
    private volatile IOException failure;
    private volatile boolean joined;
    private volatile int teleports;
    private int state;
    private int sequence;
    private int lastTeleport = -1;
    private ByteArrayOutputStream coalesced;

    PacketClient(int port) throws IOException {
        socket = new Socket(InetAddress.getLoopbackAddress(), port);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(30_000);
        send(0, out -> { varInt(out, 777); string(out, "localhost"); out.writeShort(port); varInt(out, 2); });
        send(0, out -> { string(out, NAME); out.writeLong(ACTOR.getMostSignificantBits()); out.writeLong(ACTOR.getLeastSignificantBits()); });
        reader = Thread.ofPlatform().daemon().name("chronicle-test-client").start(this::read);
    }

    boolean joined() throws IOException { check(); return joined && teleports > 0; }
    void check() throws IOException { if (failure != null) throw failure; }

    private void read() {
        try {
            var input = new DataInputStream(socket.getInputStream());
            while (!socket.isClosed()) {
                int size = varInt(input);
                if (size < 1 || size > 8 * 1024 * 1024) throw new IOException("Unbounded packet: " + size);
                byte[] bytes = input.readNBytes(size);
                if (bytes.length != size) throw new IOException("Truncated packet");
                var data = new DataInputStream(new ByteArrayInputStream(bytes));
                int id = varInt(data);
                if (state == 0) {
                    if (id != 2) throw new IOException("Unexpected login packet " + id + ": " + new String(bytes, StandardCharsets.UTF_8));
                    send(3, out -> {});
                    state = 1;
                    send(0, out -> {
                        string(out, "en_us"); out.writeByte(2); varInt(out, 0); out.writeBoolean(true);
                        out.writeByte(127); varInt(out, 1); out.writeBoolean(false); out.writeBoolean(false); varInt(out, 0);
                    });
                } else if (state == 1) {
                    switch (id) {
                        case 2 -> throw new IOException("Configuration disconnected: " + new String(bytes, StandardCharsets.UTF_8));
                        case 3 -> { send(3, out -> {}); state = 2; }
                        case 4 -> { long keep = data.readLong(); send(4, out -> out.writeLong(keep)); }
                        case 5 -> { int ping = data.readInt(); send(5, out -> out.writeInt(ping)); }
                        case 15 -> send(7, out -> varInt(out, 0));
                        case 20 -> send(9, out -> {});
                        default -> { /* Registry/tag contents are irrelevant to the fixed test actions. */ }
                    }
                } else {
                    switch (id) {
                        case 11 -> send(11, out -> out.writeFloat(10));
                        case 32 -> throw new IOException("Play disconnected: " + new String(bytes, StandardCharsets.UTF_8));
                        case 45 -> { long keep = data.readLong(); send(28, out -> out.writeLong(keep)); }
                        case 50 -> { joined = true; send(44, out -> {}); }
                        case 62 -> { int ping = data.readInt(); send(45, out -> out.writeInt(ping)); }
                        case 73 -> {
                            int teleport = varInt(data);
                            double x = data.readDouble(), y = data.readDouble(), z = data.readDouble();
                            data.readDouble(); data.readDouble(); data.readDouble();
                            float yaw = data.readFloat(), pitch = data.readFloat();
                            int flags = data.readInt();
                            if (flags != 0) throw new IOException("Fixture requires absolute teleport, flags=" + flags);
                            if (teleport == lastTeleport) break;
                            lastTeleport = teleport;
                            send(0, out -> { varInt(out, teleport); out.writeDouble(x); out.writeDouble(y); out.writeDouble(z);
                                out.writeFloat(yaw); out.writeFloat(pitch); });
                            teleports++;
                        }
                        default -> { }
                    }
                }
            }
        } catch (IOException exception) {
            if (!socket.isClosed()) failure = exception;
        }
    }

    void place() throws IOException {
        send(66, out -> {
            varInt(out, 0); out.writeLong(position(0, 79, 0)); varInt(out, 1);
            out.writeFloat(.5f); out.writeFloat(1); out.writeFloat(.5f);
            out.writeBoolean(false); out.writeBoolean(false); varInt(out, ++sequence);
        });
    }

    void dig(int action) throws IOException {
        send(41, out -> { varInt(out, action); out.writeLong(position(0, 80, 0));
            out.writeByte(1); varInt(out, ++sequence); });
    }
    synchronized void repeat() throws IOException {
        coalesced = new ByteArrayOutputStream();
        try { dig(0); place(); socket.getOutputStream().write(coalesced.toByteArray()); socket.getOutputStream().flush(); }
        finally { coalesced = null; }
    }

    private static long position(int x, int y, int z) {
        return ((long) x & 0x3ffffff) << 38 | ((long) z & 0x3ffffff) << 12 | y & 0xfff;
    }

    private synchronized void send(int id, Encoder encoder) throws IOException {
        check();
        var bytes = new ByteArrayOutputStream();
        var packet = new DataOutputStream(bytes);
        varInt(packet, id); encoder.write(packet);
        var frame = new ByteArrayOutputStream(); var output = new DataOutputStream(frame);
        varInt(output, bytes.size()); bytes.writeTo(output);
        if (coalesced != null) frame.writeTo(coalesced);
        else { socket.getOutputStream().write(frame.toByteArray()); socket.getOutputStream().flush(); }
    }

    @FunctionalInterface private interface Encoder { void write(DataOutputStream output) throws IOException; }
    private static void string(DataOutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8); varInt(output, bytes.length); output.write(bytes);
    }
    private static void varInt(DataOutputStream output, int value) throws IOException {
        do { int part = value & 127; value >>>= 7; output.writeByte(value == 0 ? part : part | 128); } while (value != 0);
    }
    private static int varInt(DataInputStream input) throws IOException {
        int result = 0;
        for (int i = 0; i < 5; i++) { int part = input.readUnsignedByte(); result |= (part & 127) << (7 * i);
            if ((part & 128) == 0) return result; }
        throw new IOException("Invalid VarInt");
    }
    @Override public void close() throws IOException { socket.close(); }
}
