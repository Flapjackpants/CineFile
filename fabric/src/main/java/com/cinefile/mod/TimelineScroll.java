package com.cinefile.mod;

import com.moulberry.flashback.editor.keybinds.Keybind;
import com.moulberry.flashback.editor.keybinds.Keybinds;

public final class TimelineScroll {
    private static boolean done;

    private TimelineScroll() {}

    public static boolean shouldRebind(boolean zShift, boolean zCtrl, boolean zAlt, boolean zSuper, boolean mShift, boolean mCtrl, boolean mAlt, boolean mSuper) {
        boolean zoomPlain = !zShift && !zCtrl && !zAlt && !zSuper;
        boolean panDefault = !mShift && !mAlt && !mSuper; // Ctrl (default) or nothing (saved as "none")
        return zoomPlain && panDefault;
    }

    public static void rebindOnce() {
        if (done) return;
        done = true;
        Keybind z = Keybinds.TIMELINE_ZOOM_SCROLL, m = Keybinds.TIMELINE_MOVE_SCROLL;
        if (shouldRebind(mod(z, "isShiftMod"), mod(z, "isCtrlMod"), mod(z, "isAltMod"), mod(z, "isSuperMod"),
                mod(m, "isShiftMod"), mod(m, "isCtrlMod"), mod(m, "isAltMod"), mod(m, "isSuperMod"))) {
            set(z, false, true);
            set(m, true, false);
        }
    }

    public static float newScroll(float scrollY, float wheel, float maxScroll, float rowHeight) {
        return Math.clamp(scrollY - wheel * 3 * rowHeight, 0f, maxScroll);
    }

    // Reflection because Keybind's interface (Lattice) is only bundled inside Flashback and not on our compile classpath.
    public static boolean mod(Keybind k, String getter) {
        try {
            return (boolean) Keybind.class.getMethod(getter).invoke(k);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Keybind k, boolean shift, boolean ctrl) {
        try {
            Keybind.class.getMethod("set", int.class, boolean.class, boolean.class, boolean.class, boolean.class)
                    .invoke(k, Keybind.FAKE_SCROLL_KEY, shift, ctrl, false, false);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
