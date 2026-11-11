package com.moulberry.flashback;

import com.moulberry.flashback.configuration.FlashbackConfigV1;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import sun.misc.Unsafe;

/** Test-only stub: the real Flashback cannot initialise outside the game. Shadows the real class on the test classpath. */
public class Flashback {
    private static FlashbackConfigV1 config;

    public static synchronized FlashbackConfigV1 getConfig() {
        if (config == null) {
            try {
                Field f = Unsafe.class.getDeclaredField("theUnsafe");
                f.setAccessible(true);
                Unsafe u = (Unsafe) f.get(null);
                config = (FlashbackConfigV1) u.allocateInstance(FlashbackConfigV1.class);
                for (Field field : FlashbackConfigV1.class.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                    try {
                        field.setAccessible(true);
                        field.set(config, u.allocateInstance(field.getType()));
                    } catch (Throwable ignored) {
                        // not constructible outside the game; leave null
                    }
                }
                config.keyframes.useRealtimeInterpolation = true;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return config;
    }
}
