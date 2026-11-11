package com.cinefile.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntFunction;

/** Builds synthetic Flashback chunks and replay file sets for tests. */
final class Replays {
    static final String NEXT = "flashback:action/next_tick";
    static final String MOVE = "flashback:action/move_entities";
    static final String CREATE = "flashback:action/create_local_player";
    static final String PACKET = "flashback:action/game_packet";
    static final String[] TABLE = {NEXT, MOVE, CREATE, PACKET};

    record Action(String name, byte[] payload) {}

    private Replays() {}

    static void varint(DataOutputStream o, int v) throws IOException {
        while ((v & ~0x7F) != 0) {
            o.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        o.writeByte(v);
    }

    static void ident(DataOutputStream o, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        varint(o, b.length);
        o.write(b);
    }

    static Action next() {
        return new Action(NEXT, new byte[0]);
    }

    static Action move(String level, int eid, Pose p) throws IOException {
        return moves(level, new int[] {eid}, new Pose[] {p});
    }

    static Action moves(String level, int[] eids, Pose[] poses) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        varint(o, 1);
        ident(o, level);
        varint(o, eids.length);
        for (int i = 0; i < eids.length; i++) {
            Pose p = poses[i];
            varint(o, eids[i]);
            o.writeDouble(p.x());
            o.writeDouble(p.y());
            o.writeDouble(p.z());
            o.writeFloat((float) p.yaw());
            o.writeFloat((float) p.pitch());
            o.writeFloat((float) p.yaw());
            o.writeBoolean(true);
        }
        return new Action(MOVE, b.toByteArray());
    }

    static Action add(int eid, UUID uuid, int type, Pose p) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        varint(o, 90);
        varint(o, eid);
        o.writeLong(uuid.getMostSignificantBits());
        o.writeLong(uuid.getLeastSignificantBits());
        varint(o, type);
        o.writeDouble(p.x());
        o.writeDouble(p.y());
        o.writeDouble(p.z());
        o.write(new byte[] {1, 2, 3});
        return new Action(PACKET, b.toByteArray());
    }

    static Action remove(int... eids) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        varint(o, 91);
        varint(o, eids.length);
        for (int eid : eids) varint(o, eid);
        o.write(new byte[] {1, 2, 3});
        return new Action(PACKET, b.toByteArray());
    }

    static Action create(Pose p) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        o.writeLong(1);
        o.writeLong(2);
        o.writeDouble(p.x());
        o.writeDouble(p.y());
        o.writeDouble(p.z());
        o.writeFloat((float) p.pitch());
        o.writeFloat((float) p.yaw());
        return new Action(CREATE, b.toByteArray());
    }

    static Action login(int eid) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        varint(o, 0);
        o.writeInt(eid);
        o.writeByte(0);
        varint(o, 1);
        ident(o, "minecraft:overworld");
        return new Action(PACKET, b.toByteArray());
    }

    static byte[] chunk(List<Action> snapshot, List<Action> stream) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        o.writeInt(0xD780E884);
        varint(o, TABLE.length);
        for (String n : TABLE) ident(o, n);
        ByteArrayOutputStream snap = new ByteArrayOutputStream();
        writeActions(new DataOutputStream(snap), snapshot);
        o.writeInt(snap.size());
        o.write(snap.toByteArray());
        writeActions(o, stream);
        return b.toByteArray();
    }

    private static void writeActions(DataOutputStream o, List<Action> actions) throws IOException {
        for (Action a : actions) {
            int id = List.of(TABLE).indexOf(a.name());
            varint(o, id);
            o.writeInt(a.payload().length);
            o.write(a.payload());
        }
    }

    /** Stream actions for ticks 1.. of the given poses (the tick-0 pose goes in the snapshot). */
    static List<Action> ticks(String level, int eid, List<Pose> poses) throws IOException {
        List<Action> out = new ArrayList<>();
        for (Pose p : poses) {
            out.add(next());
            out.add(move(level, eid, p));
        }
        return out;
    }

    static ReplayFiles files(String metadata, Map<String, byte[]> entries) {
        return new ReplayFiles() {
            public byte[] read(String name) throws IOException {
                if (name.equals("metadata.json")) return metadata.getBytes(StandardCharsets.UTF_8);
                byte[] b = entries.get(name);
                if (b == null) throw new FileNotFoundException(name);
                return b;
            }

            public List<String> list() {
                return new ArrayList<>(entries.keySet());
            }
        };
    }

    /** One chunk, one tracked player, pose(t) for t in 0..n-1. */
    static Trajectory trajectory(IntFunction<Pose> pose, int n) throws IOException {
        List<Pose> rest = new ArrayList<>();
        for (int t = 1; t < n; t++) rest.add(pose.apply(t));
        byte[] c = chunk(List.of(create(pose.apply(0))), ticks("minecraft:overworld", 7, rest));
        String meta = "{\"uuid\":\"u\",\"name\":\"n\",\"total_ticks\":" + n + ",\"chunks\":{\"c0.flashback\":{}}}";
        return ReplayParser.parse(files(meta, Map.of("c0.flashback", c)), 12.0);
    }
}
