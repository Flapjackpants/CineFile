package com.cinefile.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.EOFException;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class ByteReaderTest {
    private static byte[] b(int... v) {
        byte[] o = new byte[v.length];
        for (int i = 0; i < v.length; i++) o[i] = (byte) v[i];
        return o;
    }

    @Test
    void readsVarints() throws Exception {
        ByteReader r = new ByteReader(b(0x05, 0xAC, 0x02, 0xFF, 0x7F));
        assertEquals(5, r.readVarint());
        assertEquals(300, r.readVarint());
        assertEquals(16383, r.readVarint());
        assertEquals(0, r.remaining());
    }

    @Test
    void readsFixedWidthValues() throws Exception {
        ByteReader r = new ByteReader(b(0x80, 1, 0, 0, 0, 1, 0x3F, 0xC0, 0, 0, 0x3F, 0xF8, 0, 0, 0, 0, 0, 0, 1, 0));
        assertEquals(0x80, r.readU8());
        assertTrue(r.readBool());
        assertEquals(1, r.readInt());
        assertEquals(1.5f, r.readFloat());
        assertEquals(1.5, r.readDouble());
        assertTrue(r.readBool());
        assertFalse(r.readBool());
    }

    @Test
    void readsStringsAndKeys() throws Exception {
        byte[] s = b(3, 'a', ':', 'b', 3, 'x', 'y', 'z');
        ByteReader r = new ByteReader(s);
        assertArrayEquals(new String[] {"a", "b"}, r.readResourceKey());
        assertEquals("xyz", r.readUtf());
        ByteReader bare = new ByteReader(b(3, 'f', 'o', 'o'));
        assertArrayEquals(new String[] {"minecraft", "foo"}, bare.readResourceKey());
    }

    @Test
    void readsUuid() throws Exception {
        byte[] d = new byte[16];
        d[7] = 1;
        d[15] = 2;
        assertEquals(new java.util.UUID(1, 2), new ByteReader(d).readUuid());
    }

    @Test
    void truncatedInputThrowsEof() {
        assertThrows(EOFException.class, () -> new ByteReader(b(0x80)).readVarint());
        assertThrows(EOFException.class, () -> new ByteReader(b(0, 0, 0)).readInt());
        assertThrows(EOFException.class, () -> new ByteReader(b(5, 'a')).readUtf());
        assertThrows(EOFException.class, () -> new ByteReader(new byte[0]).readU8());
    }

    @Test
    void overlongVarintAndUtfAreRejected() {
        assertThrows(IOException.class, () -> new ByteReader(b(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 1)).readVarint());
        assertThrows(IOException.class, () -> new ByteReader(b(0xFF, 0xFF, 0x7F)).readUtf(2));
    }
}
