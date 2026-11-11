package com.cinefile.mixin;

import com.moulberry.flashback.editor.SelectedKeyframes;
import com.moulberry.flashback.editor.ui.windows.TimelineWindow;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TimelineWindow.class)
public interface TimelineWindowAccessor {
    @Accessor("selectedKeyframesList")
    static List<SelectedKeyframes> cinefile$selected() {
        return new java.util.ArrayList<>(); // replaced by Mixin in game; harmless stand-in in unit tests
    }
}
