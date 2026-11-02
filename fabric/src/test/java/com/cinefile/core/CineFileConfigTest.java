package com.cinefile.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CineFileConfigTest {
    @TempDir Path dir;

    private Path write(String json) throws Exception {
        Path p = dir.resolve("c.json");
        Files.writeString(p, json);
        return p;
    }

    @Test
    void missingFileGivesDefaults() {
        CineFileConfig c = CineFileConfig.load(dir.resolve("none.json"));
        assertEquals("", c.apiKey);
        assertEquals("https://api.deepseek.com", c.baseUrl);
        assertEquals("deepseek-flash", c.model);
        assertEquals("", c.project);
        assertEquals("auto", c.styleId);
        assertEquals(0.05, c.maxAiUsd);
        assertEquals(10, c.clipLengthS);
        assertEquals(180, c.durationS);
        assertEquals(12, c.teleportBlocks);
        assertFalse(c.think);
        assertFalse(c.visualReview);
        assertTrue(c.rejectCuts && c.rejectDimensionChanges && c.rejectBlockClipping && c.rejectBlockedView);
        assertEquals(5, c.tries);
        assertEquals(10, c.checkEveryTicks);
        assertEquals(0, c.cutLengthTicks);
        assertEquals(CineFileConfig.GapFill.CUT, c.gapFill);
    }

    @Test
    void saveThenLoadKeepsEveryField() throws Exception {
        CineFileConfig c = new CineFileConfig();
        c.apiKey = "k"; c.baseUrl = "http://x"; c.model = "m"; c.project = "p"; c.styleId = "orbit-reveal";
        c.maxAiUsd = 1.5; c.clipLengthS = 20; c.durationS = 99; c.teleportBlocks = 30;
        c.think = true; c.visualReview = true;
        c.rejectCuts = false; c.rejectDimensionChanges = false; c.rejectBlockClipping = false; c.rejectBlockedView = false;
        c.tries = 7; c.checkEveryTicks = 4; c.cutLengthTicks = 9; c.gapFill = CineFileConfig.GapFill.TIMELAPSE;
        Path p = dir.resolve("o.json");
        c.save(p);
        CineFileConfig r = CineFileConfig.load(p);
        assertEquals("k", r.apiKey);
        assertEquals("http://x", r.baseUrl);
        assertEquals("m", r.model);
        assertEquals("p", r.project);
        assertEquals("orbit-reveal", r.styleId);
        assertEquals(1.5, r.maxAiUsd);
        assertEquals(20, r.clipLengthS);
        assertEquals(99, r.durationS);
        assertEquals(30, r.teleportBlocks);
        assertTrue(r.think && r.visualReview);
        assertFalse(r.rejectCuts || r.rejectDimensionChanges || r.rejectBlockClipping || r.rejectBlockedView);
        assertEquals(7, r.tries);
        assertEquals(4, r.checkEveryTicks);
        assertEquals(9, r.cutLengthTicks);
        assertEquals(CineFileConfig.GapFill.TIMELAPSE, r.gapFill);
    }

    @Test
    void loadClampsOutOfRange() throws Exception {
        CineFileConfig c = CineFileConfig.load(write("{\"tries\":999,\"cutLengthTicks\":-3,\"maxAiUsd\":-1}"));
        assertEquals(50, c.tries);
        assertEquals(0, c.cutLengthTicks);
        assertEquals(0, c.maxAiUsd);
    }

    @Test
    void unknownKeysIgnored() throws Exception {
        CineFileConfig c = CineFileConfig.load(write("{\"nope\":1,\"model\":\"abc\"}"));
        assertEquals("abc", c.model);
    }

    @Test
    void corruptFileGivesDefaultsAndIsLeftAlone() throws Exception {
        Path p = write("{not json");
        CineFileConfig c = CineFileConfig.load(p);
        assertEquals(5, c.tries);
        assertEquals("{not json", Files.readString(p));
        Path q = write("{\"tries\":\"abc\"}");
        assertEquals(5, CineFileConfig.load(q).tries);
        assertEquals("{\"tries\":\"abc\"}", Files.readString(q));
    }

    @Test
    void unknownStyleBecomesAuto() throws Exception {
        assertEquals("auto", CineFileConfig.load(write("{\"styleId\":\"x\"}")).styleId);
    }

    @Test
    void nullsBecomeDefaults() throws Exception {
        CineFileConfig c = CineFileConfig.load(write("{\"apiKey\":null,\"gapFill\":null}"));
        assertEquals("", c.apiKey);
        assertEquals(CineFileConfig.GapFill.CUT, c.gapFill);
    }

    @Test
    void toStringOmitsApiKey() {
        CineFileConfig c = new CineFileConfig();
        c.apiKey = "sk-secret";
        assertFalse(c.toString().contains("sk-secret"));
    }

    @Test
    void saveIsOwnerOnly() throws Exception {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path p = dir.resolve("p.json");
        new CineFileConfig().save(p);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(p)));
    }

    @Test
    void saveCreatesParentDirectories() throws Exception {
        Path p = dir.resolve("a/b/c.json");
        new CineFileConfig().save(p);
        assertTrue(Files.exists(p));
    }
}
