package com.cinefile.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReplayParserTest {
    private static final String OW = "minecraft:overworld";
    private static final String META = "{\"uuid\":\"abc\",\"name\":\"tour\",\"total_ticks\":%d,\"chunks\":{%s}}";
    private static final PlayerPacketIds IDS = new PlayerPacketIds(90, 91, 150);
    private static final UUID OTHER = new UUID(3, 4);
    private static final String OTHER_ID = OTHER.toString();

    private static Pose p(double x) {
        return new Pose(x, 64, 0, 10, 5);
    }

    private static Trajectory one(List<Replays.Action> stream, int total) throws IOException {
        byte[] c = Replays.chunk(List.of(Replays.create(p(0))), stream);
        return ReplayParser.parse(Replays.files(META.formatted(total, "\"c0.flashback\":{}"), Map.of("c0.flashback", c)), 12.0);
    }

    private static Trajectory withPlayers(List<Replays.Action> snapshot, List<Replays.Action> stream, int total) throws IOException {
        byte[] c = Replays.chunk(snapshot, stream);
        return ReplayParser.parse(Replays.files(META.formatted(total, "\"c0.flashback\":{}"), Map.of("c0.flashback", c)), 12.0, IDS);
    }

    @Test
    void ignoresOtherPlayersWithoutIdsAndExposesLocalUuid() throws Exception {
        List<Replays.Action> snap = List.of(Replays.login(7), Replays.create(p(0)), Replays.add(8, OTHER, 150, p(4)));
        byte[] c = Replays.chunk(snap, List.of(Replays.next(), Replays.move(OW, 8, p(5))));
        Trajectory t = ReplayParser.parse(Replays.files(META.formatted(2, "\"c0.flashback\":{}"), Map.of("c0.flashback", c)), 12);
        assertEquals("00000000-0000-0001-0000-000000000002", t.playerUuid());
        assertEquals(List.of(t), t.players());
        assertNull(t.player(OTHER_ID));
    }

    @Test
    void tracksSnapshotPlayerAndHoldsPoseAcrossTicks() throws Exception {
        Trajectory t = withPlayers(List.of(Replays.login(7), Replays.create(p(0)), Replays.add(8, OTHER, 150, p(4))),
                List.of(Replays.next(), Replays.move(OW, 8, p(5)), Replays.next()), 3);
        Trajectory other = t.player(OTHER_ID);
        assertNotNull(other);
        assertEquals(List.of(t, other), t.players());
        assertEquals(OTHER_ID, other.playerUuid());
        assertEquals(t.uuid(), other.uuid());
        assertEquals(t.name(), other.name());
        assertEquals(3, other.totalTicks());
        assertEquals(List.of(0, 1, 2), List.copyOf(other.poses().keySet()));
        assertEquals(p(5), other.sample(2));
    }

    @Test
    void ordersOtherPlayersAndOmitsSinglePosePlayer() throws Exception {
        UUID later = new UUID(5, 6);
        UUID earlier = new UUID(3, 4);
        UUID single = new UUID(7, 8);
        Trajectory t = withPlayers(List.of(Replays.login(7), Replays.create(p(0)), Replays.add(9, later, 150, p(9)),
                        Replays.add(8, earlier, 150, p(8)), Replays.add(10, single, 150, p(10))),
                List.of(Replays.remove(10), Replays.next(), Replays.move(OW, 8, p(11)), Replays.move(OW, 9, p(12))), 2);
        assertEquals(List.of(t.playerUuid(), earlier.toString(), later.toString()),
                t.players().stream().map(Trajectory::playerUuid).toList());
        assertNull(t.player(single.toString()));
    }

    @Test
    void readsEveryMoveEntryAndIgnoresUntrackedEntities() throws Exception {
        List<Replays.Action> snap = List.of(Replays.login(7), Replays.create(p(0)), Replays.add(8, OTHER, 150, p(4)),
                Replays.add(9, new UUID(5, 6), 151, p(9)));
        Trajectory t = withPlayers(snap, List.of(Replays.next(), Replays.moves(OW, new int[] {7, 8, 9, 99}, new Pose[] {p(1), p(5), p(10), p(99)}),
                Replays.next(), Replays.moves(OW, new int[] {8, 7}, new Pose[] {p(6), p(2)})), 3);
        assertEquals(p(2), t.sample(2));
        assertEquals(p(6), t.player(OTHER_ID).sample(2));
        assertEquals(2, t.players().size());
    }

    @Test
    void streamSpawnAndPresenceGap() throws Exception {
        List<Replays.Action> stream = new ArrayList<>();
        for (int tick = 1; tick <= 25; tick++) {
            stream.add(Replays.next());
            if (tick == 5 || tick == 20) stream.add(Replays.add(8, OTHER, 150, p(tick)));
            if (tick == 10) stream.add(Replays.remove(8));
        }
        Trajectory t = withPlayers(List.of(Replays.login(7), Replays.create(p(0))), stream, 26);
        Trajectory other = t.player(OTHER_ID);
        assertEquals(5, other.poses().firstKey());
        assertEquals(List.of(10, 20), other.presenceBreaks());
        for (int tick = 11; tick < 20; tick++) assertFalse(other.poses().containsKey(tick));
    }

    @Test
    void chunkSnapshotReidentifiesOrEndsOtherPlayer() throws Exception {
        byte[] c0 = Replays.chunk(List.of(Replays.login(7), Replays.create(p(0)), Replays.add(8, OTHER, 150, p(1))), List.of(Replays.next()));
        byte[] c1 = Replays.chunk(List.of(Replays.login(7), Replays.add(9, OTHER, 150, p(2))),
                List.of(Replays.next(), Replays.move(OW, 9, p(3))));
        byte[] c2 = Replays.chunk(List.of(Replays.login(7)), List.of(Replays.next()));
        Trajectory t = ReplayParser.parse(Replays.files(META.formatted(4, "\"c0.flashback\":{},\"c1.flashback\":{},\"c2.flashback\":{}"),
                Map.of("c0.flashback", c0, "c1.flashback", c1, "c2.flashback", c2)), 12, IDS);
        Trajectory other = t.player(OTHER_ID);
        assertEquals(p(3), other.sample(2));
        assertEquals(List.of(2), other.presenceBreaks());
    }

    @Test
    void localUuidDuplicateAndTruncatedPacketsDoNotBreakParsing() throws Exception {
        Replays.Action shortAdd = new Replays.Action(Replays.PACKET, new byte[] {90, 8, 1});
        Replays.Action shortRemove = new Replays.Action(Replays.PACKET, new byte[] {91, 2, 8});
        Trajectory t = withPlayers(List.of(Replays.login(7), Replays.create(p(0)), shortAdd,
                Replays.add(8, new UUID(1, 2), 150, p(2)), Replays.add(9, OTHER, 150, p(3))),
                List.of(Replays.next(), shortRemove, Replays.move(OW, 9, p(4))), 2);
        assertEquals(2, t.players().size());
        assertEquals(p(4), t.player(OTHER_ID).sample(1));
    }

    @Test
    void otherTeleportsAndDimensionChangesStaySeparate() throws Exception {
        Trajectory t = withPlayers(List.of(Replays.login(7), Replays.create(p(0)), Replays.add(8, OTHER, 150, p(0))),
                List.of(Replays.next(), Replays.moves(OW, new int[] {7, 8}, new Pose[] {p(1), p(1)}),
                        Replays.next(), Replays.move("minecraft:the_nether", 8, p(50))), 3);
        assertTrue(t.cuts().isEmpty());
        assertTrue(t.dimensionChanges().isEmpty());
        assertEquals(List.of(2), t.player(OTHER_ID).cuts());
        assertEquals(List.of(2), t.player(OTHER_ID).dimensionChanges());
    }

    @Test
    void parsesPosesAndMetadata() throws Exception {
        Trajectory t = one(Replays.ticks(OW, 7, List.of(p(1), p(2), p(3))), 4);
        assertEquals("abc", t.uuid());
        assertEquals("tour", t.name());
        assertEquals(4, t.totalTicks());
        assertEquals(List.of(0, 1, 2, 3), List.copyOf(t.poses().keySet()));
        assertEquals(new Pose(3, 64, 0, 10, 5), t.sample(3));
        assertTrue(t.cuts().isEmpty());
    }

    @Test
    void totalTicksFallsBackToLastPose() throws Exception {
        assertEquals(3, one(Replays.ticks(OW, 7, List.of(p(1), p(2))), 0).totalTicks());
    }

    @Test
    void sampleFollowsNearestEarlier() throws Exception {
        // no player is known for ticks 0-1; the first move is at tick 2
        byte[] c = Replays.chunk(List.of(), List.of(Replays.next(), Replays.next(), Replays.move(OW, 7, p(1)), Replays.next(), Replays.move(OW, 7, p(9))));
        Trajectory t = ReplayParser.parse(Replays.files(META.formatted(4, "\"c0.flashback\":{}"), Map.of("c0.flashback", c)), 12.0);
        assertNull(t.sample(1));
        assertEquals(p(1), t.sample(2));
        assertEquals(p(9), t.sample(3));
        assertEquals(p(9), t.sample(100));
    }

    @Test
    void detectsTeleportCuts() throws Exception {
        Trajectory t = one(Replays.ticks(OW, 7, List.of(p(1), p(50), p(51))), 4);
        assertEquals(List.of(2), t.cuts());
        // a jump under the threshold is not a cut
        assertTrue(one(Replays.ticks(OW, 7, List.of(p(1), p(10))), 3).cuts().isEmpty());
    }

    @Test
    void detectsDimensionChange() throws Exception {
        List<Replays.Action> s = List.of(
                Replays.next(), Replays.move(OW, 7, p(1)),
                Replays.next(), Replays.move("minecraft:the_nether", 7, p(2)),
                Replays.next(), Replays.move("minecraft:the_nether", 7, p(3)));
        assertEquals(List.of(2), one(s, 4).dimensionChanges());
        assertTrue(one(Replays.ticks(OW, 7, List.of(p(1), p(2))), 3).dimensionChanges().isEmpty());
    }

    @Test
    void followsPlayerAcrossJoinedRecordings() throws Exception {
        byte[] c0 = Replays.chunk(List.of(Replays.login(7), Replays.create(p(0))), Replays.ticks(OW, 7, List.of(p(1), p(2))));
        byte[] c1 = Replays.chunk(List.of(Replays.login(9), Replays.create(p(2))), Replays.ticks(OW, 9, List.of(p(3), p(4))));
        String m = META.formatted(5, "\"c0.flashback\":{},\"c1.flashback\":{}");
        Trajectory t = ReplayParser.parse(Replays.files(m, Map.of("c0.flashback", c0, "c1.flashback", c1)), 12.0);
        assertEquals(4, t.poses().lastKey());
        assertEquals(p(4), t.sample(4));
    }

    @Test
    void discoversChunksInNumericOrder() throws Exception {
        Map<String, byte[]> m = new LinkedHashMap<>();
        // each later chunk continues from the previous tick; the final pose proves the order c0, c2, c10
        m.put("c10.flashback", Replays.chunk(List.of(), Replays.ticks(OW, 7, List.of(p(30)))));
        m.put("c2.flashback", Replays.chunk(List.of(), Replays.ticks(OW, 7, List.of(p(20)))));
        m.put("c0.flashback", Replays.chunk(List.of(Replays.create(p(10))), List.of()));
        Trajectory t = ReplayParser.parse(Replays.files("{\"uuid\":\"u\",\"total_ticks\":0}", m), 12.0);
        assertEquals(p(10), t.sample(0));
        assertEquals(p(20), t.sample(1));
        assertEquals(p(30), t.sample(2));
        assertEquals(3, t.totalTicks());
    }

    @Test
    void rejectsBadInput() throws Exception {
        byte[] good = Replays.chunk(List.of(Replays.create(p(0))), Replays.ticks(OW, 7, List.of(p(1))));
        byte[] badMagic = good.clone();
        badMagic[0] = 0;
        byte[] truncated = java.util.Arrays.copyOf(good, good.length - 3);
        for (byte[] c : new byte[][] {badMagic, truncated}) {
            assertThrows(IOException.class, () -> ReplayParser.parse(
                    Replays.files(META.formatted(2, "\"c0.flashback\":{}"), Map.of("c0.flashback", c)), 12.0));
        }
        assertThrows(IOException.class, () -> ReplayParser.parse(
                Replays.files("{\"total_ticks\":2,\"chunks\":{\"c0.flashback\":{}}}", Map.of("c0.flashback", good)), 12.0));
    }

    @Test
    void rejectsNegativeSnapshotSize() throws Exception {
        byte[] good = Replays.chunk(List.of(), List.of());
        // header: magic(4) + varint 4 + 4 names; snapshot size int follows
        int off = 4 + 1;
        for (String n : Replays.TABLE) off += 1 + n.length();
        good[off] = (byte) 0xFF;
        assertThrows(IOException.class, () -> ReplayParser.parse(
                Replays.files(META.formatted(1, "\"c0.flashback\":{}"), Map.of("c0.flashback", good)), 12.0));
    }
}
