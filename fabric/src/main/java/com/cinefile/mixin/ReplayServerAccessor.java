package com.cinefile.mixin;

import com.moulberry.flashback.playback.ReplayServer;
import java.nio.file.FileSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ReplayServer.class)
public interface ReplayServerAccessor {
    @Accessor("playbackFileSystem")
    FileSystem cinefile$fileSystem();
}
