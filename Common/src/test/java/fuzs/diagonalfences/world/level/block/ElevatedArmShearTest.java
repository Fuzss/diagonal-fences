package fuzs.diagonalfences.world.level.block;

import fuzs.diagonalblocks.api.v2.util.EightWayDirection;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The render shear, checked <em>against Phase 2's outline geometry</em> rather than against itself.
 * <p>
 * The point of most of these tests is that {@link ElevatedArmShear} and
 * {@link ElevatedShapes#pitchedArmEdges} must describe the same beam. They are separate code, in
 * separate coordinate conventions, written for separate purposes -- one feeds baked quads, the other
 * feeds the block highlight -- and nothing but a test stops them drifting apart into a rail that is
 * drawn in one place and outlined in another.
 */
class ElevatedArmShearTest {
    private static final float EXTENSION_WIDTH = 2.0F;
    private static final float EXTENSION_BOTTOM = 0.0F;
    private static final float EXTENSION_TOP = 16.0F;
    private static final boolean STRETCH_WIDTH = true;
    /** Block space, so a pixel is 1/16. Well inside that. */
    private static final double EPSILON = 1.0E-6;

    private static Vec3[] edges(EightWayDirection direction, int pitch) {
        return ElevatedShapes.pitchedArmEdges(EXTENSION_WIDTH,
                EXTENSION_BOTTOM,
                EXTENSION_TOP,
                direction,
                pitch,
                STRETCH_WIDTH);
    }

    /**
     * The load-bearing test. An up arm and a down arm differ only in the sign of the rise, so at
     * every one of the outline's points the vertical gap between them must be exactly twice what the
     * shear predicts from that point's horizontal position -- and the shear is never told which
     * points those are.
     * <p>
     * This is what makes the two implementations one implementation. Change the slope in either and
     * this fails.
     */
    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void theShearPredictsTheGapBetweenUpAndDownOutlines(EightWayDirection direction) {
        Vec3[] up = edges(direction, ElevatedConnections.PITCH_UP);
        Vec3[] down = edges(direction, ElevatedConnections.PITCH_DOWN);
        assertEquals(up.length, down.length, "outline point counts differ");
        for (int i = 0; i < up.length; i++) {
            String where = direction + " outline point " + i;
            // the pitch moves nothing horizontally; if it did, the shear would be the wrong shape of
            // transform entirely and the rest of this test would be comparing the wrong points
            assertEquals(up[i].x, down[i].x, EPSILON, where + " moved in x");
            assertEquals(up[i].z, down[i].z, EPSILON, where + " moved in z");
            float rise = ElevatedArmShear.riseAt(direction,
                    ElevatedConnections.PITCH_UP,
                    (float) up[i].x,
                    (float) up[i].z);
            assertEquals(2.0 * rise, up[i].y - down[i].y, EPSILON, where + " gap disagrees with the shear");
        }
    }

    /**
     * The shear is a linear function of position, so the up and down arms must straddle the flat one
     * symmetrically. Together with the test above this pins the absolute height, not just the gap.
     */
    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void upAndDownOutlinesStraddleTheFlatArm(EightWayDirection direction) {
        Vec3[] up = edges(direction, ElevatedConnections.PITCH_UP);
        Vec3[] down = edges(direction, ElevatedConnections.PITCH_DOWN);
        for (int i = 0; i < up.length; i++) {
            double flatY = (up[i].y + down[i].y) / 2.0;
            // every outline y is either the arm's bottom or its top, both in block space
            double bottom = EXTENSION_BOTTOM / 16.0;
            double top = EXTENSION_TOP / 16.0;
            boolean onFlatArm = Math.abs(flatY - bottom) < EPSILON || Math.abs(flatY - top) < EPSILON;
            assertTrue(onFlatArm,
                    direction + " outline point " + i + " midpoint " + flatY
                            + " is not on the flat arm (" + bottom + " or " + top + ")");
        }
    }

    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void theCentreOfTheBlockDoesNotMove(EightWayDirection direction) {
        assertEquals(0.0F,
                ElevatedArmShear.riseAt(direction, ElevatedConnections.PITCH_UP, 0.5F, 0.5F),
                EPSILON,
                direction + " lifted the block centre");
    }

    /**
     * At the block boundary the arm has climbed half a block, which is
     * {@link ElevatedShapes#HALF_RISE} of 16 pixels. This is the number that makes the two halves of
     * an arm meet on the shared plane, so it is worth asserting directly rather than only through
     * the outline comparison.
     */
    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void theBlockBoundaryIsHalfARiseUp(EightWayDirection direction) {
        float edgeX = direction.getX() == 0 ? 0.5F : (direction.getX() > 0 ? 1.0F : 0.0F);
        float edgeZ = direction.getZ() == 0 ? 0.5F : (direction.getZ() > 0 ? 1.0F : 0.0F);
        assertEquals(ElevatedShapes.HALF_RISE / 16.0F,
                ElevatedArmShear.riseAt(direction, ElevatedConnections.PITCH_UP, edgeX, edgeZ),
                EPSILON,
                direction + " does not reach half a block at the boundary");
        assertEquals(-ElevatedShapes.HALF_RISE / 16.0F,
                ElevatedArmShear.riseAt(direction, ElevatedConnections.PITCH_DOWN, edgeX, edgeZ),
                EPSILON,
                direction + " down arm does not reach half a block at the boundary");
    }

    /**
     * A cardinal arm reaches the boundary in a run of {@code 0.5}, an intercardinal in
     * {@code 0.5*sqrt(2)}, and both rise the same {@code 0.5} -- which is the whole reason the two
     * pitches differ. Asserting the run keeps the {@code dx*dx + dz*dz} normaliser honest; drop it
     * and intercardinal arms rise twice as fast as they should.
     */
    @Test
    void anIntercardinalArmClimbsMoreSlowlyThanACardinalOne() {
        // one pixel along the run, in block space, on each
        float cardinal = ElevatedArmShear.riseAt(EightWayDirection.SOUTH,
                ElevatedConnections.PITCH_UP,
                0.5F,
                0.5F + 1.0F / 16.0F);
        float intercardinal = ElevatedArmShear.riseAt(EightWayDirection.SOUTH_EAST,
                ElevatedConnections.PITCH_UP,
                0.5F + 1.0F / 16.0F,
                0.5F + 1.0F / 16.0F);
        assertEquals(1.0F / 16.0F, cardinal, EPSILON, "cardinal slope is not 1:1");
        assertEquals(1.0F / 16.0F, intercardinal, EPSILON, "intercardinal rise per axis step is wrong");
        // the intercardinal covered sqrt(2) times as much ground for that same rise, which is the
        // 35.26 degrees ElevatedShapes documents
        double cardinalRun = 1.0 / 16.0;
        double intercardinalRun = Math.sqrt(2.0) / 16.0;
        assertEquals(45.0,
                Math.toDegrees(Math.atan2(cardinal, cardinalRun)),
                1.0E-4,
                "cardinal pitch is not 45 degrees");
        assertEquals(Math.toDegrees(Math.atan(1.0 / Math.sqrt(2.0))),
                Math.toDegrees(Math.atan2(intercardinal, intercardinalRun)),
                1.0E-4,
                "intercardinal pitch is not 35.26 degrees");
    }

    /**
     * There is no arm to shear when there is no slope. Returning zero would draw a flat arm on top
     * of the one the multipart already drew, which reads as a z-fighting bug a long way from here.
     */
    @Test
    void thereIsNoShearForAFlatArm() {
        assertThrows(IllegalArgumentException.class,
                () -> ElevatedArmShear.riseAt(EightWayDirection.SOUTH,
                        ElevatedConnections.PITCH_NONE,
                        0.5F,
                        1.0F));
    }
}
