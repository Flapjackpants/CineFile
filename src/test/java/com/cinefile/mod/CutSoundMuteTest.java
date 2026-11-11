package com.cinefile.mod;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.sounds.SoundSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CutSoundMuteTest {
    private static final AudioFormat FORMAT = new AudioFormat(44100f, 16, 2, true, false);

    @BeforeEach
    void setUp() {
        CutSoundMute.reset();
    }

    @Test
    void notMutedBlocksNothing() {
        for (SoundSource source : SoundSource.values()) {
            assertFalse(CutSoundMute.shouldBlock(sound(source)), source.getName());
            assertFalse(CutSoundMute.onPlay(sound(source)), source.getName());
        }
        assertFalse(CutSoundMute.shouldBlock(tickable(SoundSource.BLOCKS)));
    }

    @Test
    void mutedBlocksWorldSoundsOnly() {
        CutSoundMute.applyMarker(true, 0);
        for (SoundSource source : new SoundSource[] {SoundSource.BLOCKS, SoundSource.RECORDS, SoundSource.PLAYERS, SoundSource.MASTER}) {
            assertTrue(CutSoundMute.shouldBlock(sound(source)), source.getName());
        }
        assertFalse(CutSoundMute.shouldBlock(sound(SoundSource.UI)));
        assertFalse(CutSoundMute.shouldBlock(sound(SoundSource.MUSIC)));
        assertFalse(CutSoundMute.shouldBlock(tickable(SoundSource.BLOCKS)));
    }

    @Test
    void resumesRecordsAtMarkerOffset() {
        CutSoundMute.applyMarker(true, 100);
        CutSoundMute.applyMarker(true, 140);
        SoundInstance records = sound(SoundSource.RECORDS);
        assertTrue(CutSoundMute.onPlay(records));
        List<CutSoundMute.Resume> resumes = CutSoundMute.applyMarker(false, 200);
        assertEquals(1, resumes.size());
        assertSame(records, resumes.get(0).sound());
        assertEquals(3.0, resumes.get(0).seconds());
        assertTrue(CutSoundMute.applyMarker(false, 200).isEmpty());
        assertFalse(CutSoundMute.onPlay(records));
    }

    @Test
    void blockedNonRecordsIsNotResumed() {
        CutSoundMute.applyMarker(true, 100);
        assertTrue(CutSoundMute.onPlay(sound(SoundSource.BLOCKS)));
        assertTrue(CutSoundMute.applyMarker(false, 200).isEmpty());
    }

    @Test
    void forgetDropsSong() {
        CutSoundMute.applyMarker(true, 100);
        SoundInstance records = sound(SoundSource.RECORDS);
        CutSoundMute.onPlay(records);
        CutSoundMute.forget(records);
        assertTrue(CutSoundMute.applyMarker(false, 200).isEmpty());
    }

    @Test
    void seekBackDropsSong() {
        CutSoundMute.applyMarker(true, 140);
        CutSoundMute.onPlay(sound(SoundSource.RECORDS));
        assertTrue(CutSoundMute.applyMarker(false, 100).isEmpty());
    }

    @Test
    void unmuteWithoutMuteReturnsEmpty() {
        assertTrue(CutSoundMute.applyMarker(false, 5).isEmpty());
    }

    @Test
    void resetClearsState() {
        CutSoundMute.applyMarker(true, 100);
        SoundInstance records = sound(SoundSource.RECORDS);
        CutSoundMute.onPlay(records);
        CutSoundMute.reset();
        assertFalse(CutSoundMute.shouldBlock(records));
        assertTrue(CutSoundMute.applyMarker(false, 200).isEmpty());
    }

    @Test
    void withSeekSetsAndClears() {
        CutSoundMute.withSeek(1.5, () -> assertEquals(1.5, CutSoundMute.seekSeconds()));
        assertEquals(0.0, CutSoundMute.seekSeconds());
        assertThrows(IllegalStateException.class, () -> CutSoundMute.withSeek(1.5, () -> {
            throw new IllegalStateException("boom");
        }));
        assertEquals(0.0, CutSoundMute.seekSeconds());
    }

    @Test
    void skipConsumesExactBytes() {
        FakeStream stream = new FakeStream(1_000_000);
        assertSame(stream, CutSoundMute.skip(stream, 2.0));
        assertEquals(352_800, stream.consumed);
    }

    @Test
    void skipStopsAtEndOfStream() {
        FakeStream stream = new FakeStream(1000);
        assertSame(stream, CutSoundMute.skip(stream, 2.0));
        assertEquals(1000, stream.consumed);
    }

    @Test
    void skipSurvivesIOException() {
        AudioStream stream = new ThrowingStream();
        assertSame(stream, CutSoundMute.skip(stream, 2.0));
    }

    private static SoundInstance sound(SoundSource source) {
        return (SoundInstance) Proxy.newProxyInstance(CutSoundMuteTest.class.getClassLoader(),
                new Class<?>[] {SoundInstance.class}, (proxy, method, args) -> answer(source, method.getReturnType()));
    }

    private static SoundInstance tickable(SoundSource source) {
        return (SoundInstance) Proxy.newProxyInstance(CutSoundMuteTest.class.getClassLoader(),
                new Class<?>[] {TickableSoundInstance.class}, (proxy, method, args) -> answer(source, method.getReturnType()));
    }

    private static Object answer(SoundSource source, Class<?> type) {
        if (type == SoundSource.class) return source;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == float.class) return 0f;
        if (type == double.class) return 0.0;
        if (type == long.class) return 0L;
        return null;
    }

    private static final class FakeStream implements AudioStream {
        private final long budget;
        private long consumed;

        FakeStream(long budget) {
            this.budget = budget;
        }

        @Override
        public AudioFormat getFormat() {
            return FORMAT;
        }

        @Override
        public ByteBuffer read(int size) {
            if (consumed >= budget) return ByteBuffer.allocate(0);
            int n = (int) Math.min(size, budget - consumed);
            consumed += n;
            return ByteBuffer.allocate(n);
        }

        @Override
        public void close() {}
    }

    private static final class ThrowingStream implements AudioStream {
        @Override
        public AudioFormat getFormat() {
            return FORMAT;
        }

        @Override
        public ByteBuffer read(int size) throws IOException {
            throw new IOException("boom");
        }

        @Override
        public void close() {}
    }
}
