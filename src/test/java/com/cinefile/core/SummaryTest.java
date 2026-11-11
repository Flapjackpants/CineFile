package com.cinefile.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SummaryTest {
    private static AutoCreateJob.Result r(int placed, Map<Verdict, Integer> rej, boolean cancelled, String err) {
        return new AutoCreateJob.Result(placed, 18, rej, cancelled, err, 0);
    }

    @Test
    void placedWithRejectsCostAndAiError() {
        assertEquals("Placed 16/18 clips. Rejected: 3 BLOCK_CLIP, 1 CUT. AI cost ~$0.02. AI: HTTP 401",
            Summary.text(r(16, Map.of(Verdict.CUT, 1, Verdict.BLOCK_CLIP, 3), false, null), 0.02, "HTTP 401"));
    }

    @Test
    void zeroCostAndNoRejectsOmitted() {
        assertEquals("Placed 18/18 clips.", Summary.text(r(18, Map.of(), false, null), 0, null));
    }

    @Test
    void cancelledPrefix() {
        assertEquals("Cancelled. Placed 2/18 clips.", Summary.text(r(2, Map.of(), true, null), 0, null));
    }

    @Test
    void errorAlone() {
        assertEquals("No valid clip windows found", Summary.text(r(0, Map.of(), false, "No valid clip windows found"), 0.5, "x"));
    }
}
