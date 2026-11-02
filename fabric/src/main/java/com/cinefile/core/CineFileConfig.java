package com.cinefile.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CineFileConfig {
    public enum GapFill { CUT, TIMELAPSE, NONE }
    public String apiKey = "", baseUrl = "https://api.deepseek.com", model = "deepseek-flash", project = "", styleId = "auto";
    public double maxAiUsd = 0.05, clipLengthS = 10, durationS = 180, teleportBlocks = 12;
    public boolean think = false, visualReview = false;
    public boolean rejectCuts = true, rejectDimensionChanges = true, rejectBlockClipping = true, rejectBlockedView = true;
    public boolean rejectOffscreenPlayer = true;
    public int tries = 5, checkEveryTicks = 10, cutLengthTicks = 0;
    public GapFill gapFill = GapFill.CUT;

    private static final Logger LOGGER = LoggerFactory.getLogger("cinefile");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> STYLES = Set.of("auto", "locked-dolly", "hero-low", "high-crane", "side-track", "orbit-reveal");

    public static CineFileConfig load(Path path) {
        CineFileConfig c = null;
        try {
            if (Files.exists(path)) {
                c = GSON.fromJson(Files.readString(path), CineFileConfig.class);
            }
        } catch (IOException | JsonParseException e) {
            LOGGER.warn("Ignoring unreadable config {}: {}", path, e.getMessage());
        }
        if (c == null) {
            c = new CineFileConfig();
        }
        c.clamp();
        return c;
    }

    public void save(Path path) throws IOException {
        clamp();
        Path dir = path.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "cinefile", ".tmp");
        try {
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX filesystem
            }
            Files.writeString(tmp, GSON.toJson(this));
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public void clamp() {
        CineFileConfig d = new CineFileConfig();
        if (apiKey == null) apiKey = d.apiKey;
        if (baseUrl == null) baseUrl = d.baseUrl;
        if (model == null) model = d.model;
        if (project == null) project = d.project;
        if (styleId == null || !STYLES.contains(styleId)) styleId = d.styleId;
        if (gapFill == null) gapFill = d.gapFill;
        maxAiUsd = Math.clamp(maxAiUsd, 0, 100);
        clipLengthS = Math.clamp(clipLengthS, 1, 600);
        durationS = Math.clamp(durationS, 1, 36000);
        teleportBlocks = Math.clamp(teleportBlocks, 1, 1000);
        tries = Math.clamp(tries, 1, 50);
        checkEveryTicks = Math.clamp(checkEveryTicks, 1, 200);
        cutLengthTicks = Math.clamp(cutLengthTicks, 0, 200);
    }

    @Override
    public String toString() {
        return "CineFileConfig{baseUrl=" + baseUrl + ", model=" + model + ", styleId=" + styleId + ", tries=" + tries
            + ", gapFill=" + gapFill + "}";
    }
}
