package com.cinefile.mod;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.cinefile.core.PlayerPacketIds;
import com.cinefile.core.ReplayFiles;
import com.cinefile.core.ReplayParser;
import com.cinefile.core.Trajectory;
import java.io.IOException;
import java.util.List;
import java.util.zip.ZipFile;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.Test;

class PlayerPacketsTest {
    private static PlayerPacketIds ids() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        return PlayerPackets.current();
    }

    @Test
    void resolvesCurrentGamePacketAndPlayerTypeIds() {
        PlayerPacketIds ids = ids();
        assertTrue(ids.addEntity() >= 0);
        assertTrue(ids.removeEntities() >= 0);
        assertNotEquals(ids.addEntity(), ids.removeEntities());
        assertTrue(ids.playerType() >= 0);
    }

    @Test
    void realReplayPreservesLocalTrajectory() throws Exception {
        String path = System.getenv("CINEFILE_TEST_REPLAY");
        assumeTrue(path != null && !path.isBlank(), "needs CINEFILE_TEST_REPLAY");
        try (ZipFile zip = new ZipFile(path)) {
            ReplayFiles files = new ReplayFiles() {
                public byte[] read(String name) throws IOException {
                    return zip.getInputStream(zip.getEntry(name)).readAllBytes();
                }
                public List<String> list() {
                    return zip.stream().map(e -> e.getName()).toList();
                }
            };
            Trajectory before = ReplayParser.parse(files, 12.0);
            Trajectory after = ReplayParser.parse(files, 12.0, ids());
            assertEquals(before.poses().size(), after.poses().size());
            assertEquals(before.cuts(), after.cuts());
            System.out.println("players().size()=" + after.players().size());
        }
    }
}
