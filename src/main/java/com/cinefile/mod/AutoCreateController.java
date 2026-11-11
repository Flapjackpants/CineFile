package com.cinefile.mod;

import com.cinefile.core.AiClient;
import com.cinefile.core.AutoCreateJob;
import com.cinefile.core.CineFileConfig;
import com.cinefile.core.FsReplayFiles;
import com.cinefile.core.ReplayParser;
import com.cinefile.core.PlayerPacketIds;
import com.cinefile.core.Summary;
import com.cinefile.core.Trajectory;
import com.cinefile.mixin.ReplayServerAccessor;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import java.util.concurrent.CompletableFuture;

/** Runs one AutoCreateJob at a time on the live editor, driven from the client tick. */
public final class AutoCreateController {
    private static ReplayServer server;
    private static EditorState state;
    private static CineFileConfig cfg;
    private static AiClient ai;
    private static CompletableFuture<Trajectory> parse;
    private static AutoCreateJob job;
    private static LiveReplayDriver driver;
    private static boolean parseCancelled;

    private AutoCreateController() {}

    public static boolean isRunning() {
        return parse != null || job != null;
    }

    public static String progressLabel() {
        return job == null ? "Cancel" : "Cancel (" + job.placed() + "/" + job.target() + ")";
    }

    public static String statusLine() {
        return driver != null ? driver.status() : "Reading replay...";
    }

    public static void start(double budgetUsd) {
        if (isRunning() || Flashback.isExporting()) return;
        ReplayServer s = Flashback.getReplayServer();
        EditorState e = EditorStateManager.getCurrent();
        if (s == null || e == null) return;
        server = s;
        state = e;
        cfg = CineFileClient.config();
        ai = cfg.apiKey.isBlank() ? null : new AiClient(cfg.apiKey, cfg.baseUrl, cfg.model, Math.max(0, budgetUsd), cfg.think);
        parseCancelled = false;
        driver = null;
        double teleport = cfg.teleportBlocks;
        FsReplayFiles files = new FsReplayFiles(((ReplayServerAccessor) s).cinefile$fileSystem());
        PlayerPacketIds ids;
        try {
            ids = PlayerPackets.current();
        } catch (RuntimeException ex) {
            CineFileClient.LOGGER.warn("CineFile: could not resolve player packet ids", ex);
            ids = null;
        }
        PlayerPacketIds parseIds = ids;
        parse = CompletableFuture.supplyAsync(() -> {
            try {
                return ReplayParser.parse(files, teleport, parseIds);
            } catch (java.io.IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        });
    }

    public static void cancel() {
        if (job != null) job.cancel();
        else if (parse != null) parseCancelled = true;
    }

    private static void reset() {
        parse = null;
        job = null;
        driver = null;
        server = null;
        state = null;
        ai = null;
    }

    public static void tick() {
        try {
            if (parse != null) {
                if (!parse.isDone()) return;
                CompletableFuture<Trajectory> p = parse;
                if (parseCancelled) {
                    reset();
                    return;
                }
                try {
                    Trajectory t = p.join();
                    parse = null;
                    driver = new LiveReplayDriver(state, server, cfg);
                    job = new AutoCreateJob(driver, t, cfg, ai);
                } catch (RuntimeException ex) {
                    CineFileClient.LOGGER.error("CineFile: could not read replay", ex);
                    ReplayUI.setInfoOverlayShort("CineFile: could not read this replay");
                    reset();
                    return;
                }
            }
            if (job == null) return;
            if (Flashback.getReplayServer() != server || Flashback.isExporting() || EditorStateManager.getCurrent() != state) job.cancel();
            try {
                job.tick();
            } catch (RuntimeException ex) {
                CineFileClient.LOGGER.error("CineFile: auto-create failed", ex);
                ReplayUI.setInfoOverlayShort("CineFile stopped: see log");
                job.cancel();
                try {
                    job.tick();
                } catch (RuntimeException ex2) {
                    reset();
                    return;
                }
            }
            if (job.isDone()) {
                ReplayUI.setInfoOverlayShort(Summary.text(job.result(), ai == null ? 0 : ai.usage().estimateUsd(), ai == null ? null : ai.lastError()));
                reset();
            }
        } catch (RuntimeException ex) {
            CineFileClient.LOGGER.error("CineFile: tick failed", ex);
            reset();
        }
    }
}
