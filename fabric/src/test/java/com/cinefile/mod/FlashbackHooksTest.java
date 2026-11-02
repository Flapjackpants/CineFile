package com.cinefile.mod;

import static org.junit.jupiter.api.Assertions.*;

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
}
