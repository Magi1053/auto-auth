package com.skcraft.autoauth.runtimetest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal loopback login peer: actual game networking, encryption challenge, and key response. */
final class RuntimeLoginFixture implements AutoCloseable {
    private final ServerSocket listener;
    private final byte[] publicKey;
    private volatile Socket current;
    private volatile boolean closed;
    final AtomicInteger connections = new AtomicInteger();
    final CompletableFuture<Void> authenticated = new CompletableFuture<>();

    RuntimeLoginFixture() throws Exception {
        listener = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
        listener.setSoTimeout(25_000);
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        publicKey = generator.generateKeyPair().getPublic().getEncoded();
        CompletableFuture.runAsync(this::serve);
    }

    String address() {
        return "127.0.0.1:" + listener.getLocalPort();
    }

    private void serve() {
        try {
            while (!closed) {
                try (Socket socket = listener.accept()) {
                    current = socket;
                    socket.setSoTimeout(20_000);
                    InputStream input = socket.getInputStream();
                    readFrame(input); // Handshake switches to login.
                    readFrame(input); // Login start.
                    connections.incrementAndGet();
                    var payload = new ByteArrayOutputStream();
                    var packet = new DataOutputStream(payload);
                    writeVarInt(packet, 1); // Clientbound login hello.
                    writeBytes(packet, "".getBytes(StandardCharsets.UTF_8));
                    writeBytes(packet, publicKey);
                    writeBytes(packet, new byte[] { 1, 2, 3, 4 });
                    packet.writeBoolean(true);
                    OutputStream output = socket.getOutputStream();
                    writeVarInt(output, payload.size());
                    payload.writeTo(output);
                    output.flush();
                    try {
                        byte[] response = readFrame(input);
                        if (readVarInt(new ByteArrayInputStream(response)) != 1) {
                            throw new AssertionError("Expected a serverbound encryption-key response");
                        }
                        authenticated.complete(null);
                        return;
                    } catch (EOFException | SocketException rejected) {
                        // A rejected client closes this socket before refreshing and reconnecting.
                    }
                } finally {
                    current = null;
                }
            }
        } catch (Throwable failure) {
            if (!closed) authenticated.completeExceptionally(failure);
        }
    }

    private static byte[] readFrame(InputStream input) throws IOException {
        int length = readVarInt(input);
        if (length < 1 || length > 65_536) throw new IOException("Invalid fixture frame length");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Incomplete fixture frame");
        return bytes;
    }

    private static int readVarInt(InputStream input) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int next = input.read();
            if (next < 0) throw new EOFException("Fixture connection closed");
            value |= (next & 127) << shift;
            if ((next & 128) == 0) return value;
        }
        throw new IOException("Oversized fixture VarInt");
    }

    private static void writeBytes(OutputStream output, byte[] bytes) throws IOException {
        writeVarInt(output, bytes.length);
        output.write(bytes);
    }

    private static void writeVarInt(OutputStream output, int value) throws IOException {
        while ((value & ~127) != 0) {
            output.write((value & 127) | 128);
            value >>>= 7;
        }
        output.write(value);
    }

    @Override
    public void close() {
        closed = true;
        try { listener.close(); } catch (IOException ignored) { }
        Socket socket = current;
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }
}
