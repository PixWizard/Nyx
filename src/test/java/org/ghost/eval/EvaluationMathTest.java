package org.ghost.eval;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies mathematical functions in SteganographyEvaluationTool under Maven Surefire execution.
 */
public class EvaluationMathTest {

    @Test
    void testGammaMathVerification() {
        assertEquals(Math.exp(-1.0), SteganographyEvaluationTool.chiSquareSurvival(2, 2.0), 1e-10);
        assertEquals(Math.exp(-2.5), SteganographyEvaluationTool.chiSquareSurvival(2, 5.0), 1e-10);
        assertEquals(0.05, SteganographyEvaluationTool.chiSquareSurvival(1, 3.841458820694124), 1e-5);
        assertEquals(0.05, SteganographyEvaluationTool.chiSquareSurvival(10, 18.307038053275146), 1e-5);
    }
}
