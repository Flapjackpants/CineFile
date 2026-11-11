package com.cinefile.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Parses a Flashback replay into a per-tick player trajectory (port of cinefile/replay.py). */
public final class ReplayParser {
    private static final int MAGIC = 0xD780E884;
    private static final String NEXT_TICK = "flashback:action/next_tick";
    private static final String MOVE = "flashback:action/move_entities";
    private static final String CREATE_LOCAL = "flashback:action/create_local_player";
    private static final String GAME_PACKET = "flashback:action/game_packet";

    private final Trajectory traj;
    private final PlayerPacketIds ids;
    private final Map<String, Other> others = new HashMap<>();
    private final Map<Integer, Other> present = new HashMap<>();
    private Set<Other> snapshotPrevious = Set.of();
    private int tick;
    private Integer playerId;
    private Pose lastPlayer;
    private String lastLevel;

    private static final class Other {
        final Trajectory trajectory;
        Pose last;
        String level;
        boolean present;

        Other(Trajectory trajectory) {
            this.trajectory = trajectory;
        }
    }

    private ReplayParser(Trajectory traj, PlayerPacketIds ids) {
        this.traj = traj;
        this.ids = ids;
    }

    public static Trajectory parse(ReplayFiles files, double teleportBlocks) throws IOException {
        return parse(files, teleportBlocks, null);
    }

    public static Trajectory parse(ReplayFiles files, double teleportBlocks, PlayerPacketIds idsOrNull) throws IOException {
        JsonObject meta;
        try {
            meta = JsonParser.parseString(new String(files.read("metadata.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Bad metadata.json: " + e.getMessage(), e);
        }
        if (!meta.has("uuid") || !meta.get("uuid").isJsonPrimitive()) throw new IOException("metadata.json has no uuid");
        List<String> chunks = new ArrayList<>();
        JsonElement ce = meta.get("chunks");
        if (ce != null && ce.isJsonObject()) chunks.addAll(ce.getAsJsonObject().keySet());
        if (chunks.isEmpty()) {
            for (String n : files.list()) if (n.startsWith("c") && n.endsWith(".flashback")) chunks.add(n);
        }
        chunks.sort((a, b) -> {
            long[] ka = chunkKey(a), kb = chunkKey(b);
            int c = Long.compare(ka[0], kb[0]);
            return c != 0 ? c : base(a).compareTo(base(b));
        });
        int total;
        String name;
        try {
            total = meta.has("total_ticks") ? meta.get("total_ticks").getAsInt() : 0;
            name = meta.has("name") ? meta.get("name").getAsString() : "";
        } catch (RuntimeException e) {
            throw new IOException("Bad metadata.json: " + e.getMessage(), e);
        }
        Trajectory traj = new Trajectory(meta.get("uuid").getAsString(), name, total);
        ReplayParser p = new ReplayParser(traj, idsOrNull);
        for (String c : chunks) p.chunk(files.read(c));
        if (total <= 0 && !traj.poses.isEmpty()) traj.setTotalTicks(traj.poses.lastKey() + 1);

        List<Trajectory> all = new ArrayList<>();
        all.add(traj);
        p.others.entrySet().stream().filter(e -> !e.getKey().equals(traj.playerUuid()) && e.getValue().trajectory.poses.size() >= 2)
                .sorted(Map.Entry.comparingByKey()).forEach(e -> all.add(e.getValue().trajectory));
        traj.setPlayers(all);
        for (Trajectory other : all) other.setTotalTicks(traj.totalTicks());
        for (Trajectory other : all) detectTeleports(other, teleportBlocks);
        return traj;
    }

    private static void detectTeleports(Trajectory traj, double teleportBlocks) {
        Integer prevTick = null;
        Pose prev = null;
        for (Map.Entry<Integer, Pose> e : traj.poses.entrySet()) {
            if (prev != null && prev.dist(e.getValue()) > teleportBlocks && e.getKey() - prevTick <= 5) {
                traj.cuts.add(e.getKey());
            }
            prevTick = e.getKey();
            prev = e.getValue();
        }
    }

    private static String base(String n) {
        return n.substring(n.lastIndexOf('/') + 1);
    }

    /** Numeric chunk index first, so c2 sorts before c10; anything else goes last. */
    private static long[] chunkKey(String name) {
        String b = base(name);
        if (b.startsWith("c") && b.endsWith(".flashback")) {
            String mid = b.substring(1, b.length() - ".flashback".length());
            if (!mid.isEmpty() && mid.chars().allMatch(ch -> ch >= '0' && ch <= '9') && mid.length() < 18) {
                return new long[] {Long.parseLong(mid)};
            }
        }
        return new long[] {1_000_000_000L};
    }

    private void chunk(byte[] data) throws IOException {
        ByteReader r = new ByteReader(data);
        int magic = r.readInt();
        if (magic != MAGIC) throw new IOException(String.format("Invalid Flashback magic: 0x%08X", magic));
        int nActions = r.readVarint();
        Map<Integer, String> names = new HashMap<>();
        for (int i = 0; i < nActions; i++) names.put(i, r.readIdentifier());

        int snapshotSize = r.readInt();
        if (snapshotSize < 0) throw new IOException("Invalid snapshot size " + snapshotSize);
        int snapEnd = r.position() + snapshotSize;
        if (ids != null) {
            snapshotPrevious = new HashSet<>(present.values());
            present.clear();
            for (Other other : snapshotPrevious) other.present = false;
        }
        boolean loginSeen = false;
        while (r.position() < snapEnd) {
            int aid = r.readVarint();
            byte[] payload = r.readBytes(r.readInt());
            String name = names.getOrDefault(aid, "");
            if (name.equals(GAME_PACKET) && (ids == null || !playerPacket(payload)) && !loginSeen) {
                // a joined recording uses a new entity ID, so the login packet re-identifies the player
                Integer eid = loginEntityId(payload);
                if (eid != null) {
                    playerId = eid;
                    loginSeen = true;
                }
            } else if (name.equals(CREATE_LOCAL)) {
                createLocal(payload);
            } else if (name.equals(MOVE)) {
                moves(payload);
            }
        }
        if (ids != null) {
            for (Other other : snapshotPrevious) if (!other.present) other.trajectory.presenceBreaks.add(tick);
            snapshotPrevious = Set.of();
        }
        r.position(snapEnd);

        while (r.remaining() > 0) {
            int aid = r.readVarint();
            byte[] payload = r.readBytes(r.readInt());
            String name = names.getOrDefault(aid, "");
            if (name.equals(NEXT_TICK)) {
                tick++;
                if (lastPlayer != null) traj.poses.putIfAbsent(tick, lastPlayer);
                for (Other other : present.values()) if (other.last != null) other.trajectory.poses.putIfAbsent(tick, other.last);
            } else if (name.equals(CREATE_LOCAL)) {
                createLocal(payload);
            } else if (name.equals(MOVE)) {
                moves(payload);
            } else if (name.equals(GAME_PACKET) && ids != null) {
                playerPacket(payload);
            }
        }
    }

    private void createLocal(byte[] payload) {
        try {
            ByteReader r = new ByteReader(payload);
            UUID uuid = r.readUuid();
            double x = r.readDouble(), y = r.readDouble(), z = r.readDouble();
            double pitch = r.readFloat(), yaw = r.readFloat();
            lastPlayer = new Pose(x, y, z, yaw, pitch);
            traj.setPlayerUuid(uuid.toString());
        } catch (IOException e) {
            // truncated payload: keep the last known pose
        }
        if (lastPlayer != null) traj.poses.put(tick, lastPlayer);
    }

    /** Returns true only for the two configured entity packet ids. */
    private boolean playerPacket(byte[] payload) {
        ByteReader r = new ByteReader(payload);
        try {
            int packet = r.readVarint();
            if (packet == ids.addEntity()) {
                int eid = r.readVarint();
                String uuid = r.readUuid().toString();
                int type = r.readVarint();
                double x = r.readDouble(), y = r.readDouble(), z = r.readDouble();
                if (type != ids.playerType() || (playerId != null && eid == playerId)) return true;
                Other other = others.computeIfAbsent(uuid, key -> {
                    Trajectory t = new Trajectory(traj.uuid(), traj.name(), traj.totalTicks());
                    t.setPlayerUuid(key);
                    return new Other(t);
                });
                if (!other.present && !snapshotPrevious.contains(other) && !other.trajectory.poses.isEmpty()) {
                    other.trajectory.presenceBreaks.add(tick);
                }
                present.values().removeIf(value -> value == other);
                present.put(eid, other);
                other.present = true;
                double yaw = other.last == null ? 0 : other.last.yaw();
                double pitch = other.last == null ? 0 : other.last.pitch();
                other.last = new Pose(x, y, z, yaw, pitch);
                other.trajectory.poses.put(tick, other.last);
                return true;
            }
            if (packet == ids.removeEntities()) {
                int count = r.readVarint();
                if (count < 0 || count > r.remaining()) return true;
                int[] eids = new int[count];
                for (int i = 0; i < count; i++) eids[i] = r.readVarint();
                for (int eid : eids) {
                    Other other = present.remove(eid);
                    if (other != null) {
                        other.present = false;
                        other.trajectory.presenceBreaks.add(tick);
                    }
                }
                return true;
            }
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    /** Entity ID if the payload is shaped like a login game packet, else null. */
    private static Integer loginEntityId(byte[] payload) {
        try {
            ByteReader r = new ByteReader(payload);
            r.readVarint();
            int eid = r.readInt();
            int flag = r.readU8();
            if (eid <= 0 || (flag != 0 && flag != 1)) return null;
            int n = r.readVarint();
            if (n < 1 || n > 64 || !r.readIdentifier().contains(":")) return null;
            return eid;
        } catch (IOException e) {
            return null;
        }
    }

    private void level(String level) {
        if (lastLevel != null && !lastLevel.equals(level)) {
            List<Integer> d = traj.dimensionChanges;
            if (d.isEmpty() || d.get(d.size() - 1) != tick) d.add(tick);
        }
        lastLevel = level;
    }

    private void moves(byte[] payload) {
        ByteReader r = new ByteReader(payload);
        int levelCount;
        try {
            levelCount = r.readVarint();
        } catch (IOException e) {
            return;
        }
        double bestDist = 0;
        Integer bestEid = null;
        Pose bestPose = null;
        String bestLevel = null;
        boolean foundLocal = false;
        for (int l = 0; l < levelCount; l++) {
            String level;
            int count;
            try {
                String[] key = r.readResourceKey();
                level = key[0] + ":" + key[1];
                count = r.readVarint();
            } catch (IOException e) {
                break;
            }
            for (int j = 0; j < count; j++) {
                int eid;
                Pose pose;
                try {
                    eid = r.readVarint();
                    double x = r.readDouble(), y = r.readDouble(), z = r.readDouble();
                    float yaw = r.readFloat(), pitch = r.readFloat();
                    r.readFloat();
                    r.readBool();
                    pose = new Pose(x, y, z, yaw, pitch);
                } catch (IOException e) {
                    return;
                }
                if (playerId != null && eid == playerId) {
                    traj.poses.put(tick, pose);
                    lastPlayer = pose;
                    level(level);
                    foundLocal = true;
                    continue;
                }
                Other other = present.get(eid);
                if (other != null) {
                    other.last = pose;
                    other.trajectory.poses.put(tick, pose);
                    if (other.level != null && !other.level.equals(level)) {
                        List<Integer> changes = other.trajectory.dimensionChanges;
                        if (changes.isEmpty() || changes.get(changes.size() - 1) != tick) changes.add(tick);
                    }
                    other.level = level;
                    continue;
                }
                if (lastPlayer != null) {
                    double d = lastPlayer.dist(pose);
                    if (bestPose == null || d < bestDist) {
                        bestDist = d;
                        bestEid = eid;
                        bestPose = pose;
                        bestLevel = level;
                    }
                } else if (bestPose == null) {
                    bestDist = 0;
                    bestEid = eid;
                    bestPose = pose;
                    bestLevel = level;
                }
            }
        }
        if (playerId == null && bestPose != null) {
            // lock onto the nearest entity to the last known / first seen pose
            playerId = bestEid;
            lastPlayer = bestPose;
            traj.poses.put(tick, lastPlayer);
            level(bestLevel);
        } else if (!foundLocal && playerId != null && lastPlayer != null) {
            traj.poses.putIfAbsent(tick, lastPlayer);
        }
    }
}
