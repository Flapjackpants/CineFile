package com.cinefile.core;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Minimal Minecraft FriendlyByteBuf reader (port of cinefile/bufio.py). */
public final class ByteReader {
    private final byte[] data;
    private int i;

    public ByteReader(byte[] data) {
        this.data = data;
    }

    public int remaining() {
        return data.length - i;
    }

    public void require(int n) throws EOFException {
        if (n < 0 || i + n > data.length) {
            throw new EOFException("need " + n + " bytes at " + i + ", have " + remaining());
        }
    }

    public byte[] readBytes(int n) throws EOFException {
        require(n);
        byte[] out = java.util.Arrays.copyOfRange(data, i, i + n);
        i += n;
        return out;
    }

    public void skip(int n) throws EOFException {
        require(n);
        i += n;
    }

    /** Absolute position, so a caller can jump to a known end offset. */
    public int position() {
        return i;
    }

    public void position(int p) {
        i = p;
    }

    public int readU8() throws EOFException {
        require(1);
        return data[i++] & 0xFF;
    }

    public boolean readBool() throws EOFException {
        return readU8() != 0;
    }

    public int readInt() throws EOFException {
        return ByteBuffer.wrap(readBytes(4)).getInt();
    }

    public float readFloat() throws EOFException {
        return ByteBuffer.wrap(readBytes(4)).getFloat();
    }

    public double readDouble() throws EOFException {
        return ByteBuffer.wrap(readBytes(8)).getDouble();
    }

    public UUID readUuid() throws EOFException {
        ByteBuffer b = ByteBuffer.wrap(readBytes(16));
        return new UUID(b.getLong(), b.getLong());
    }

    public int readVarint() throws IOException {
        long num = 0;
        int shift = 0;
        while (true) {
            int b = readU8();
            num |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return (int) num;
            shift += 7;
            if (shift > 35) throw new IOException("VarInt too long");
        }
    }

    public String readUtf() throws IOException {
        return readUtf(32767);
    }

    public String readUtf(int maxLen) throws IOException {
        int len = readVarint();
        if (len < 0 || len > maxLen * 3) throw new IOException("UTF length " + len + " too large");
        return new String(readBytes(len), StandardCharsets.UTF_8);
    }

    public String readIdentifier() throws IOException {
        return readUtf(32767);
    }

    /** Namespace and path of a dimension key; the namespace defaults to minecraft. */
    public String[] readResourceKey() throws IOException {
        String loc = readIdentifier();
        int c = loc.indexOf(':');
        return c >= 0 ? new String[] {loc.substring(0, c), loc.substring(c + 1)} : new String[] {"minecraft", loc};
    }
}
