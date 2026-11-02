package com.cinefile.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReplayParserTest {
    private static final String OW = "minecraft:overworld";
    private static final String META = "{\"uuid\":\"abc\",\"name\":\"tour\",\"total_ticks\":%d,\"chunks\":{%s}}";

    private static Pose p(double x) {
        return new Pose(x, 64, 0, 10, 5);
    }

    private static Trajectory one(List<Replays.Action> stream, int total) throws IOException {
        byte[] c = Replays.chunk(List.of(Replays.create(p(0))), stream);
        return ReplayParser.parse(Replays.files(META.formatted(total, "\"c0.flashback\":{}"), Map.of("c0.flashback", c)), 12.0);
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
