package com.cinefile.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FsReplayFilesTest {
    private static final String META = "{\"uuid\":\"u\",\"name\":\"n\",\"total_ticks\":4,\"chunks\":{\"c0.flashback\":{}}}";

    private static FileSystem zip(Path dir, Map<String, byte[]> entries) throws IOException {
        Path z = dir.resolve("r.zip");
        try (FileSystem fs = FileSystems.newFileSystem(URI.create("jar:" + z.toUri()), Map.of("create", "true"))) {
            for (var e : entries.entrySet()) Files.write(fs.getPath("/" + e.getKey()), e.getValue());
        }
        return FileSystems.newFileSystem(z);
    }

    @Test
    void readsAndLists(@TempDir Path dir) throws Exception {
        try (FileSystem fs = zip(dir, Map.of("metadata.json", META.getBytes(StandardCharsets.UTF_8), "c0.flashback", new byte[] {1, 2}))) {
            FsReplayFiles f = new FsReplayFiles(fs);
            assertArrayEquals(new byte[] {1, 2}, f.read("c0.flashback"));
            assertEquals(List.of("c0.flashback", "metadata.json"), f.list().stream().sorted().toList());
            assertThrows(IOException.class, () -> f.read("nope"));
        }
    }

    @Test
    void parsesThroughFileSystem(@TempDir Path dir) throws Exception {
        Pose p0 = new Pose(0, 64, 0, 10, 5);
        byte[] c = Replays.chunk(List.of(Replays.create(p0)),
            Replays.ticks("minecraft:overworld", 7, List.of(new Pose(1, 64, 0, 10, 5), new Pose(2, 64, 0, 10, 5), new Pose(3, 64, 0, 10, 5))));
        try (FileSystem fs = zip(dir, Map.of("metadata.json", META.getBytes(StandardCharsets.UTF_8), "c0.flashback", c))) {
            Trajectory t = ReplayParser.parse(new FsReplayFiles(fs), 12.0);
            Trajectory ref = ReplayParser.parse(Replays.files(META, Map.of("c0.flashback", c)), 12.0);
            assertEquals(ref.totalTicks(), t.totalTicks());
            assertEquals(ref.sample(2), t.sample(2));
            assertNotNull(t.sample(3));
        }
    }
}
