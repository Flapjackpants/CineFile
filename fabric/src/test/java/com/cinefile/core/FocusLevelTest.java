package com.cinefile.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class FocusLevelTest {
    @Test
    void fixedLevelsAndCandidates() {
        assertEquals(List.of(FocusLevel.AUTO, FocusLevel.CENTER, FocusLevel.EYE, FocusLevel.HIGH, FocusLevel.LOW, FocusLevel.STYLE), List.of(FocusLevel.values()));
        double[][] values = {{0.9, 0.9, 1}, {1.62, 1.62, 0.95}, {3, 0.9, 0.85}, {0.2, 0.9, 0.85}};
        FocusLevel[] fixed = {FocusLevel.CENTER, FocusLevel.EYE, FocusLevel.HIGH, FocusLevel.LOW};
        for (int i = 0; i < fixed.length; i++) {
            assertEquals(values[i][0], fixed[i].cameraHeight());
            assertEquals(values[i][1], fixed[i].aimHeight());
            assertEquals(values[i][2], fixed[i].weight());
            assertEquals(List.of(fixed[i]), fixed[i].candidates());
        }
        assertEquals(List.of(fixed), FocusLevel.AUTO.candidates());
        assertEquals(1, FocusLevel.AUTO.weight());
        assertEquals(1, FocusLevel.STYLE.weight());
        assertEquals(List.of(FocusLevel.STYLE), FocusLevel.STYLE.candidates());
    }
}
