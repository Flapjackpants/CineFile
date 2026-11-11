package com.cinefile.mod;

import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

public final class CutSoundMute {
    public static final CustomPacketPayload.Type<Payload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("cinefile", "cut_mute"));
    public static final StreamCodec<ByteBuf, Payload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, Payload::muted, ByteBufCodecs.VAR_INT, Payload::tick, Payload::new);

    private static boolean muted;
    private static int tick;
    private static final List<Song> remembered = new ArrayList<>();
    private static double seekSeconds;

    private CutSoundMute() {}

    public record Payload(boolean muted, int tick) implements CustomPacketPayload {
        @Override
        public CustomPacketPayload.Type<Payload> type() {
            return TYPE;
        }
    }

    record Resume(SoundInstance sound, double seconds) {}

    private record Song(SoundInstance sound, int startTick) {}

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
        ClientPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) -> {
            boolean wasMuted = muted;
            List<Resume> resumes = applyMarker(payload.muted(), payload.tick());
            if (wasMuted && !payload.muted()) {
                Minecraft minecraft = Minecraft.getInstance();
                minecraft.particleEngine.clearParticles();
                for (Resume resume : resumes) {
                    withSeek(resume.seconds(), () -> minecraft.getSoundManager().play(resume.sound()));
                }
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    static List<Resume> applyMarker(boolean muted, int tick) {
        boolean wasMuted = CutSoundMute.muted;
        CutSoundMute.muted = muted;
        CutSoundMute.tick = tick;
        if (!wasMuted || muted) return List.of();
        List<Resume> resumes = new ArrayList<>();
        for (Song song : remembered) {
            if (song.startTick() <= tick) {
                resumes.add(new Resume(song.sound(), (tick - song.startTick()) / 20.0));
            }
        }
        remembered.clear();
        return resumes;
    }

    public static boolean shouldBlock(SoundInstance sound) {
        return muted && sound.getSource() != SoundSource.UI && sound.getSource() != SoundSource.MUSIC
                && !(sound instanceof TickableSoundInstance);
    }

    public static boolean onPlay(SoundInstance sound) {
        if (!shouldBlock(sound)) return false;
        // The song must start after the cut at the position it reached, so keep it.
        if (sound.getSource() == SoundSource.RECORDS) remembered.add(new Song(sound, tick));
        return true;
    }

    public static void forget(SoundInstance sound) {
        remembered.removeIf(song -> song.sound() == sound);
    }

    static void withSeek(double seconds, Runnable play) {
        seekSeconds = seconds;
        try {
            play.run();
        } finally {
            seekSeconds = 0;
        }
    }

    public static double seekSeconds() {
        return seekSeconds;
    }

    public static AudioStream skip(AudioStream stream, double seconds) {
        AudioFormat format = stream.getFormat();
        long remaining = (long) (seconds * format.getSampleRate()) * format.getSampleSizeInBits() / 8 * format.getChannels();
        try {
            while (remaining > 0) {
                ByteBuffer buffer = stream.read((int) Math.min(remaining, 65536));
                if (buffer == null || !buffer.hasRemaining()) break;
                remaining -= buffer.remaining();
            }
        } catch (IOException e) {
            CineFileClient.LOGGER.warn("Failed to seek a cut sound", e);
        }
        return stream;
    }

    static void reset() {
        muted = false;
        tick = 0;
        remembered.clear();
        seekSeconds = 0;
    }
}
