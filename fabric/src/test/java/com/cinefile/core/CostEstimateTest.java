package com.cinefile.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CostEstimateTest {
    private static CineFileConfig keyed() {
        CineFileConfig cfg = new CineFileConfig();
        cfg.apiKey = "k";
        return cfg;
    }

    @Test
    void blankKeyHasNoCostButCountsClips() {
        CostEstimate e = CostEstimate.of(new CineFileConfig());
        assertEquals(18, e.clips());
        assertEquals(0, e.planUsd());
        assertEquals(0, e.reviewUsd());
        assertEquals(0, e.typicalUsd());
        assertEquals(0, e.worstUsd());
    }

    @Test
    void planOnlyUsesPoolCeiling() {
        CostEstimate e = CostEstimate.of(keyed());
        assertEquals(18, e.clips());
        assertEquals(0.0007224, e.planUsd(), 1e-10);
        assertEquals(e.planUsd(), e.typicalUsd());
        assertEquals(e.planUsd(), e.worstUsd());
    }

    @Test
    void reviewCostIncludesEveryPossibleVariant() {
        CineFileConfig cfg = keyed();
        cfg.visualReview = true;
        CostEstimate e = CostEstimate.of(cfg);
        assertEquals(0.0007428, e.reviewUsd(), 1e-10);
        assertEquals(0.0140928, e.typicalUsd(), 1e-10);
        assertEquals(2.8530744, e.worstUsd(), 1e-6);
    }

    @Test
    void fixedFocusAndStyleChangeVariantCount() {
        CineFileConfig cfg = keyed();
        cfg.visualReview = true;
        CostEstimate all = CostEstimate.of(cfg);
        cfg.focusLevel = FocusLevel.CENTER;
        CostEstimate center = CostEstimate.of(cfg);
        // auto has 30 variants across four levels and 9 at one level: top-down clips don't vary by level
        assertEquals((all.worstUsd() - all.planUsd()) * 9 / 30,
            center.worstUsd() - center.planUsd(), 1e-10);
        cfg.styleId = "locked-dolly";
        cfg.tries = 8;
        CostEstimate fixed = CostEstimate.of(cfg);
        assertEquals(fixed.planUsd() + (6 * 18 + 20) * 8 * fixed.reviewUsd(), fixed.worstUsd(), 1e-10);
        cfg.styleId = "straight-dolly";
        CostEstimate straight = CostEstimate.of(cfg);
        assertEquals(straight.planUsd() + (6 * 18 + 20) * 16 * straight.reviewUsd(), straight.worstUsd(), 1e-10);
        cfg.styleId = "top-down";
        cfg.focusLevel = FocusLevel.AUTO;
        CostEstimate topDown = CostEstimate.of(cfg);
        assertEquals(topDown.planUsd() + (6 * 18 + 20) * 16 * topDown.reviewUsd(), topDown.worstUsd(), 1e-10);
    }

    @Test
    void planStopsGrowingPastOneHundredTwentyClips() {
        CineFileConfig cfg = keyed();
        cfg.durationS = 1200;
        CostEstimate atCap = CostEstimate.of(cfg);
        cfg.durationS = 2400;
        assertEquals(120, atCap.clips());
        assertEquals(atCap.planUsd(), CostEstimate.of(cfg).planUsd());
    }

    @Test
    void estimateAgreesWithAiBudgetEnforcement() {
        CineFileConfig cfg = keyed();
        cfg.durationS = 10;
        double plan = CostEstimate.of(cfg).planUsd();
        AiClient ai = new AiClient("k", "https://example.invalid", "m", plan - 1e-9, false);
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 120; i++) candidates.add(new Candidate(i * 200, i * 200 + 100, 1));
        assertTrue(ai.planClips(candidates, "", 1, 10).join().isEmpty());
        assertEquals("Over budget", ai.lastError());
    }
}
