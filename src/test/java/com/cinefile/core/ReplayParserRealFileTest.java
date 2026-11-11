package com.cinefile.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

class ReplayParserRealFileTest {
    @Test
    void countsMatchPython() throws Exception {
        String path = System.getenv("CINEFILE_TEST_REPLAY");
        String expect = System.getenv("CINEFILE_TEST_EXPECT");
        assumeTrue(path != null && expect != null, "needs CINEFILE_TEST_REPLAY and CINEFILE_TEST_EXPECT");
        try (ZipFile zip = new ZipFile(path)) {
            ReplayFiles files = new ReplayFiles() {
                public byte[] read(String name) throws IOException {
                    return zip.getInputStream(zip.getEntry(name)).readAllBytes();
                }

                public List<String> list() {
                    return zip.stream().map(e -> e.getName()).toList();
                }
            };
            Trajectory t = ReplayParser.parse(files, 12.0);
            String[] want = expect.split(",");
            assertEquals(Integer.parseInt(want[0]), t.poses().size());
            assertEquals(Integer.parseInt(want[1]), t.cuts().size());
            assertEquals(Integer.parseInt(want[2]), t.totalTicks());
        }
    }
}
