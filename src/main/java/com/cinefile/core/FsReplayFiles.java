package com.cinefile.core;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Stream;

/** Replay entries read from a (zip) file system, names resolved from its root. */
public final class FsReplayFiles implements ReplayFiles {
    private final FileSystem fs;

    public FsReplayFiles(FileSystem fs) {
        this.fs = fs;
    }

    @Override
    public byte[] read(String name) throws IOException {
        return Files.readAllBytes(fs.getPath("/", name));
    }

    @Override
    public List<String> list() throws IOException {
        try (Stream<java.nio.file.Path> s = Files.list(fs.getPath("/"))) {
            return s.map(p -> p.getFileName().toString()).toList();
        }
    }
}
