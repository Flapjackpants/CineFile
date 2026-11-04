package com.cinefile.mod;

import com.cinefile.core.CameraClip;
import com.cinefile.core.CameraKey;
import com.cinefile.core.CineFileConfig;
import com.cinefile.core.Framing;
import com.cinefile.core.ReplayDriver;
import com.mojang.blaze3d.platform.NativeImage;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.change.KeyframeChangeCameraPosition;
import com.moulberry.flashback.keyframe.impl.CameraKeyframe;
import com.moulberry.flashback.keyframe.interpolation.InterpolationType;
import com.moulberry.flashback.keyframe.types.CameraKeyframeType;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorSceneHistoryAction;
import com.moulberry.flashback.state.EditorSceneHistoryEntry;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.KeyframeTrack;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Vector3d;

/** ReplayDriver on the live Flashback editor. Client thread only; needs a running game, so it has no unit tests. */
public final class LiveReplayDriver implements ReplayDriver {
    private record Handle(KeyframeTrack track, CameraClip clip) {}

    private static final int MAX_SHOT_WIDTH = 512;
    private static final double CAMERA_MARGIN = 0.2;

    private final EditorState state;
    private final ReplayServer server;
    private final CineFileConfig cfg;
    private int settle;
    private volatile String status = "";

    public LiveReplayDriver(EditorState state, ReplayServer server, CineFileConfig cfg) {
        this.state = state;
        this.server = server;
        this.cfg = cfg;
    }

    public String status() {
        return status;
    }

    @Override
    public void seek(int tick) {
        server.replayPaused = true;
        server.goToReplayTick(tick);
        server.forceApplyKeyframes.set(true);
        settle = 0;
    }

    @Override
    public boolean atTick(int tick) {
        if (server.jumpToTick == -1 && !server.fastForwarding && server.getReplayTick() == tick) {
            if (++settle == 3) server.forceApplyKeyframes.set(true);
            return settle >= 3;
        }
        settle = 0;
        return false;
    }

    @Override
    public String dimension() {
        return Minecraft.getInstance().level.dimension().toString();
    }

    @Override
    public double fovDegrees() {
        return state.replayVisuals.overrideFov ? state.replayVisuals.overrideFovAmount : Minecraft.getInstance().options.fov().get();
    }

    @Override
    public boolean isLoaded(double[] pos) {
        return Minecraft.getInstance().level.getChunkSource().hasChunk((int) Math.floor(pos[0]) >> 4, (int) Math.floor(pos[2]) >> 4);
    }

    @Override
    public boolean blocked(double[] from, double[] to) {
        ClientLevel level = Minecraft.getInstance().level;
        BlockPos bp = BlockPos.containing(from[0], from[1], from[2]);
        // level.clip misses when from equals to, so test the start point against the block's visual shape
        for (AABB box : level.getBlockState(bp).getVisualShape(level, bp, CollisionContext.empty()).toAabbs()) {
            if (box.move(bp).contains(from[0], from[1], from[2])) return true;
        }
        ClipContext cc = new ClipContext(new Vec3(from[0], from[1], from[2]), new Vec3(to[0], to[1], to[2]),
            ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty());
        return level.clip(cc).getType() != HitResult.Type.MISS;
    }

    @Override
    public boolean clips(double[] from, double[] to) {
        ClientLevel level = Minecraft.getInstance().level;
        AABB box = new AABB(to[0], to[1], to[2], to[0], to[1], to[2]).inflate(CAMERA_MARGIN);
        if (!level.noBlockCollision(null, box)) return true;
        ClipContext cc = new ClipContext(new Vec3(from[0], from[1], from[2]), new Vec3(to[0], to[1], to[2]),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty());
        return level.clip(cc).getType() != HitResult.Type.MISS;
    }

    private static Keyframe key(CameraKey k) {
        return new CameraKeyframe(new Vector3d(k.x(), k.keyframeY(), k.z()), (float) k.yaw(), (float) k.pitch(), 0f, InterpolationType.SMOOTH);
    }

    @Override
    public Object showCandidate(CameraClip clip) {
        KeyframeTrack track = new KeyframeTrack(CameraKeyframeType.INSTANCE);
        track.customName = "CineFile: checking";
        track.keyframesByTick.put(clip.a().tick(), key(clip.a()));
        track.keyframesByTick.put(clip.b().tick(), key(clip.b()));
        long stamp = state.acquireWrite();
        try {
            state.getCurrentScene(stamp).keyframeTracks.add(track); // no history, no markDirty: not saved on its own
        } finally {
            state.release(stamp);
        }
        return new Handle(track, clip);
    }

    private void remove(Handle h) {
        long stamp = state.acquireWrite();
        try {
            state.getCurrentScene(stamp).keyframeTracks.removeIf(t -> t == h.track());
        } finally {
            state.release(stamp);
        }
    }

    @Override
    public void discardCandidate(Object handle) {
        remove((Handle) handle);
        server.forceApplyKeyframes.set(true);
    }

    @Override
    public void commitCandidate(Object handle, CameraClip clip) {
        Handle h = (Handle) handle;
        remove(h);
        long stamp = state.acquireWrite();
        try {
            EditorScene scene = state.getCurrentScene(stamp);
            var type = CameraKeyframeType.INSTANCE;
            int index = scene.keyframeTracks.size();
            scene.push(new EditorSceneHistoryEntry(
                List.of(new EditorSceneHistoryAction.RemoveTrack(type, index)),
                List.of(new EditorSceneHistoryAction.AddTrack(type, index),
                    new EditorSceneHistoryAction.SetKeyframe(type, index, clip.a().tick(), h.track().keyframesByTick.get(clip.a().tick())),
                    new EditorSceneHistoryAction.SetKeyframe(type, index, clip.b().tick(), h.track().keyframesByTick.get(clip.b().tick()))),
                "Add CineFile clip"));
            scene.keyframeTracks.get(index).customName = "CineFile: " + clip.styleId();
            state.markDirty();
        } finally {
            state.release(stamp);
        }
    }

    @Override
    public double[] cameraAt(Object handle, int tick) {
        Handle h = (Handle) handle;
        KeyframeChange c = h.track().createKeyframeChange(tick, null);
        if (c instanceof KeyframeChangeCameraPosition p) {
            return new double[] {p.position().x, CameraKey.eyeY(p.position().y), p.position().z, p.yaw(), p.pitch()};
        }
        return Framing.cameraAt(h.clip(), tick);
    }

    @Override
    public void capture(Consumer<byte[]> png) {
        try {
            Screenshot.takeScreenshot(Minecraft.getInstance().gameRenderer.mainRenderTarget(), image -> png.accept(encode(image)));
        } catch (RuntimeException e) {
            png.accept(null);
        }
    }

    private static byte[] encode(NativeImage image) {
        NativeImage small = null;
        Path tmp = null;
        try {
            NativeImage out = image;
            if (image.getWidth() > MAX_SHOT_WIDTH) {
                small = new NativeImage(MAX_SHOT_WIDTH, Math.max(1, image.getHeight() * MAX_SHOT_WIDTH / image.getWidth()), false);
                image.resizeSubRectTo(0, 0, image.getWidth(), image.getHeight(), small);
                out = small;
            }
            tmp = Files.createTempFile("cinefile", ".png");
            out.writeToFile(tmp);
            return Files.readAllBytes(tmp);
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            image.close();
            if (small != null) small.close();
            if (tmp != null) tmp.toFile().delete();
        }
    }

    @Override
    public void addGap(int t0, int t1) {
        switch (cfg.gapFill) {
            case CUT -> CutTracks.add(state, t0, t1, cfg.cutLengthTicks);
            case TIMELAPSE -> CutTracks.addTimelapse(state, t0, t1, 20);
            case NONE -> {}
        }
    }

    @Override
    public void setExportRange(int t0, int t1) {
        long stamp = state.acquireWrite();
        try {
            EditorScene scene = state.getCurrentScene(stamp);
            if (scene.exportStartTicks < 0 && scene.exportEndTicks < 0) {
                scene.exportStartTicks = t0;
                scene.exportEndTicks = t1;
                state.markDirty();
            }
        } finally {
            state.release(stamp);
        }
    }

    @Override
    public List<int[]> occupiedRanges() {
        List<int[]> out = new ArrayList<>();
        long stamp = state.acquireRead();
        try {
            for (KeyframeTrack t : state.getCurrentScene(stamp).keyframeTracks) {
                if (!t.keyframesByTick.isEmpty()) out.add(new int[] {t.keyframesByTick.firstKey(), t.keyframesByTick.lastKey()});
            }
        } finally {
            state.release(stamp);
        }
        return out;
    }

    @Override
    public void status(String text) {
        status = text;
    }
}
