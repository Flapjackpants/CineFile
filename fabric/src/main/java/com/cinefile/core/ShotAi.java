package com.cinefile.core;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface ShotAi {
    boolean enabled();
    CompletableFuture<Optional<List<Integer>>> planClips(List<Candidate> ranked, String project, int targetClips, double clipSeconds);
    CompletableFuture<Optional<Double>> reviewClip(List<byte[]> pngFrames, String project);
}
