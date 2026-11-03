package com.cinefile.mod;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.junit.jupiter.api.Test;

class FlashbackHooksTest {
    private static Class<?> load(String name) throws Exception {
        return Class.forName(name, false, FlashbackHooksTest.class.getClassLoader());
    }

    @Test
    void mixinTargetsExist() throws Exception {
        String p = "com.moulberry.flashback.";
        assertNotNull(load(p + "state.KeyframeTrack").getDeclaredMethod("tryApplyKeyframesTimelapse", float.class));
        Class<?> tw = load(p + "editor.ui.windows.TimelineWindow");
        assertNotNull(tw.getDeclaredMethod("renderInner", load(p + "playback.ReplayServer"), load(p + "record.FlashbackMeta")));
        assertNotNull(tw.getDeclaredField("selectedKeyframesList"));
        assertNotNull(load(p + "editor.ui.windows.MainMenuBar").getDeclaredMethod("renderInner"));
    }

    @Test
    void realTimeMappingTargetExists() throws Exception {
        assertNotNull(load("com.moulberry.flashback.state.RealTimeMapping").getDeclaredMethod("addMapping", int.class, float.class));
    }

    @Test
    void liveHooksExist() throws Exception {
        String p = "com.moulberry.flashback.";
        Class<?> rs = load(p + "playback.ReplayServer");
        for (String f : new String[] {"playbackFileSystem", "jumpToTick", "fastForwarding", "replayPaused", "forceApplyKeyframes"}) {
            assertNotNull(rs.getDeclaredField(f), f);
        }
        assertNotNull(load(p + "keyframe.change.KeyframeChangeCameraPosition").getMethod("position"));
    }

    @Test
    void cutMuteSoundTargetsExist() throws Exception {
        Class<?> soundInstance = load("net.minecraft.client.resources.sounds.SoundInstance");
        Class<?> engine = load("net.minecraft.client.sounds.SoundEngine");
        assertNotNull(engine.getDeclaredMethod("play", soundInstance));
        assertNotNull(engine.getDeclaredMethod("playDelayed", soundInstance, int.class));
        assertNotNull(engine.getDeclaredMethod("stop", soundInstance));
        assertNotNull(load("net.minecraft.client.sounds.SoundBufferLibrary")
                .getDeclaredMethod("getStream", load("net.minecraft.resources.Identifier"), boolean.class));
    }

    @Test
    void cutSoundHandlerTargetsExist() throws Exception {
        String p = "com.moulberry.flashback.playback.";
        Class<?> handler = load(p + "ReplayGamePacketHandler");
        assertEquals(load(p + "ReplayServer"), handler.getDeclaredField("replayServer").getType());
        assertEquals(int.class, load(p + "ReplayServer").getDeclaredField("currentTick").getType());
        String packets = "net.minecraft.network.protocol.game.";
        Set<String> methods = new HashSet<>();
        try (InputStream in = handler.getResourceAsStream("ReplayGamePacketHandler.class")) {
            assertNotNull(in);
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    methods.add(name + descriptor);
                    return null;
                }
            }, ClassReader.SKIP_CODE);
        }
        assertTrue(methods.contains("handleSoundEvent(" + Type.getDescriptor(load(packets + "ClientboundSoundPacket")) + ")V"));
        assertTrue(methods.contains("handleSoundEntityEvent(" + Type.getDescriptor(load(packets + "ClientboundSoundEntityPacket")) + ")V"));
        assertTrue(methods.contains("handleLevelEvent(" + Type.getDescriptor(load(packets + "ClientboundLevelEventPacket")) + ")V"));
        assertTrue(methods.contains("handleExplosion(" + Type.getDescriptor(load(packets + "ClientboundExplodePacket")) + ")V"));
    }
}
