package fuzs.diagonalfences.world.level.block;

import fuzs.diagonalblocks.api.v2.util.EightWayDirection;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geometry of a single sloped arm, checked against the analytic slope rather than against a recorded
 * snapshot of whatever the code happened to produce.
 * <p>
 * Dimensions throughout are a vanilla fence's outline arm:
 * {@code CrossCollisionBlock(4, 16, 4, 16, 24)} reaches
 * {@code makeShapes(4, 16, 4, 0, 16)}, which upstream halves into
 * {@code extensionWidth = 2, extensionBottom = 0, extensionTop = 16}.
 */
class ElevatedShapesTest {
    private static final float EXTENSION_WIDTH = 2.0F;
    private static final float EXTENSION_BOTTOM = 0.0F;
    private static final float EXTENSION_TOP = 16.0F;
    private static final boolean STRETCH_WIDTH = true;

    private static VoxelShape arm(EightWayDirection direction, int pitch) {
        return ElevatedShapes.pitchedArmCollisionShape(EXTENSION_WIDTH,
                EXTENSION_BOTTOM,
                EXTENSION_TOP,
                direction,
                pitch);
    }

    private static Vec3[] edges(EightWayDirection direction, int pitch) {
        return ElevatedShapes.pitchedArmEdges(EXTENSION_WIDTH,
                EXTENSION_BOTTOM,
                EXTENSION_TOP,
                direction,
                pitch,
                STRETCH_WIDTH);
    }

    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void everyStepOfAnUpArmSitsWhereTheSlopePutsIt(EightWayDirection direction) {
        VoxelShape voxelShape = arm(direction, ElevatedConnections.PITCH_UP);
        for (int i = 0; i < ElevatedShapes.STEPS; i++) {
            double x = ElevatedShapes.stepCoordinate(direction.getX(), i);
            double z = ElevatedShapes.stepCoordinate(direction.getZ(), i);
            double y = EXTENSION_BOTTOM + (ElevatedShapes.STEPS - i) + 0.5;
            ShapeProbe.assertTouches(voxelShape, x, y, z, direction + " up arm, step " + i);
        }
    }

    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void everyStepOfADownArmSitsWhereTheSlopePutsIt(EightWayDirection direction) {
        VoxelShape voxelShape = arm(direction, ElevatedConnections.PITCH_DOWN);
        for (int i = 0; i < ElevatedShapes.STEPS; i++) {
            double x = ElevatedShapes.stepCoordinate(direction.getX(), i);
            double z = ElevatedShapes.stepCoordinate(direction.getZ(), i);
            double y = EXTENSION_TOP - (ElevatedShapes.STEPS - i) - 0.5;
            ShapeProbe.assertTouches(voxelShape, x, y, z, direction + " down arm, step " + i);
        }
    }

    /**
     * The half that makes the tests above non-vacuous: a shape that simply filled the whole arm
     * region would pass them. At the block edge the arm has moved a full {@link
     * ElevatedShapes#HALF_RISE} away, so the flat rail level there must be empty -- that is the
     * difference between a slope and a wall.
     */
    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void anArmHasLeftTheFlatLevelByTheTimeItReachesTheBlockEdge(EightWayDirection direction) {
        double x = ElevatedShapes.edgeCoordinate(direction.getX());
        double z = ElevatedShapes.edgeCoordinate(direction.getZ());
        ShapeProbe.assertDoesNotTouch(arm(direction, ElevatedConnections.PITCH_UP),
                x,
                EXTENSION_BOTTOM + 0.5,
                z,
                direction + " up arm at the block edge");
        ShapeProbe.assertDoesNotTouch(arm(direction, ElevatedConnections.PITCH_DOWN),
                x,
                EXTENSION_TOP - 0.5,
                z,
                direction + " down arm at the block edge");
    }

    /**
     * The two halves of one arm are drawn by different blocks, so nothing forces them to line up
     * except the arithmetic. This pins that down: the step sitting on the shared plane is the same box
     * seen from both sides, so the beam is continuous rather than merely close.
     */
    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void theTwoHalvesOfAnArmMeetExactlyOnTheSharedPlane(EightWayDirection direction) {
        VoxelShape lowerArm = arm(direction, ElevatedConnections.PITCH_UP);
        // the neighbour is one block up and one step along the direction, and draws the mirror half
        VoxelShape upperArm = arm(direction.getOpposite(), ElevatedConnections.PITCH_DOWN)
                .move(direction.getX(), 1.0, direction.getZ());

        double edgeX = ElevatedShapes.edgeCoordinate(direction.getX());
        double edgeZ = ElevatedShapes.edgeCoordinate(direction.getZ());
        VoxelShape sharedStep = ShapeProbe.box(edgeX - EXTENSION_WIDTH,
                EXTENSION_BOTTOM + ElevatedShapes.HALF_RISE,
                edgeZ - EXTENSION_WIDTH,
                edgeX + EXTENSION_WIDTH,
                EXTENSION_TOP + ElevatedShapes.HALF_RISE,
                edgeZ + EXTENSION_WIDTH);

        ShapeProbe.assertContains(lowerArm, sharedStep, direction + " lower half");
        ShapeProbe.assertContains(upperArm, sharedStep, direction + " upper half");
    }

    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void anArmDrawsTwelveEdgesAndItsTopEdgeRisesByHalfABlock(EightWayDirection direction) {
        Vec3[] upEdges = edges(direction, ElevatedConnections.PITCH_UP);
        assertEquals(24, upEdges.length, "twelve edges, two endpoints each");
        // create12Edges emits the top edge of one side first: endpoint 0 at the block edge,
        // endpoint 1 at the block centre
        double expectedRise = ElevatedShapes.HALF_RISE / 16.0;
        assertEquals(expectedRise, upEdges[0].y - upEdges[1].y, 1.0E-6, direction + " up arm top edge rise");

        Vec3[] downEdges = edges(direction, ElevatedConnections.PITCH_DOWN);
        assertEquals(-expectedRise, downEdges[0].y - downEdges[1].y, 1.0E-6, direction + " down arm top edge drop");
    }

    /**
     * The outline is a straight beam, so its edge endpoints must land on the block centre at one end
     * and the block boundary at the other -- not on the staircase the collision shape uses.
     */
    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void theOutlineRunsFromTheBlockCentreToTheBlockBoundary(EightWayDirection direction) {
        Vec3[] armEdges = edges(direction, ElevatedConnections.PITCH_UP);
        // create12Edges emits the cross-connections at indices 8..15, so 8 and 9 are the two sides of
        // the beam at its edge end and 10 and 11 the two sides at its centre end. Averaging a pair
        // cancels the perpendicular half width and leaves the beam's own axis.
        Vec3 atEdge = armEdges[8].add(armEdges[9]).scale(0.5);
        Vec3 atCentre = armEdges[10].add(armEdges[11]).scale(0.5);

        assertEquals(ElevatedShapes.edgeCoordinate(direction.getX()) / 16.0, atEdge.x, 1.0E-6, direction + " outline x at the block boundary");
        assertEquals(ElevatedShapes.edgeCoordinate(direction.getZ()) / 16.0, atEdge.z, 1.0E-6, direction + " outline z at the block boundary");
        assertEquals(ElevatedShapes.CENTRE / 16.0, atCentre.x, 1.0E-6, direction + " outline x at the block centre");
        assertEquals(ElevatedShapes.CENTRE / 16.0, atCentre.z, 1.0E-6, direction + " outline z at the block centre");
    }

    @Test
    void anIntercardinalArmIsShallowerThanACardinalOne() {
        // same rise, longer run: 8 pixels of rise over 8 versus over 8 * sqrt(2)
        Vec3[] cardinal = edges(EightWayDirection.SOUTH, ElevatedConnections.PITCH_UP);
        Vec3[] intercardinal = edges(EightWayDirection.SOUTH_EAST, ElevatedConnections.PITCH_UP);
        double cardinalRun = Math.hypot(cardinal[0].x - cardinal[1].x, cardinal[0].z - cardinal[1].z);
        double intercardinalRun = Math.hypot(intercardinal[0].x - intercardinal[1].x,
                intercardinal[0].z - intercardinal[1].z);
        assertTrue(intercardinalRun > cardinalRun,
                "intercardinal run " + intercardinalRun + " should exceed cardinal run " + cardinalRun);
        assertEquals(Math.sqrt(2.0), intercardinalRun / cardinalRun, 1.0E-6, "run ratio should be sqrt(2)");
    }

    @Test
    void thereIsNoArmForAnAbsentOrUnknownPitch() {
        assertThrows(IllegalArgumentException.class,
                () -> arm(EightWayDirection.NORTH, ElevatedConnections.PITCH_NONE),
                "PITCH_NONE must not quietly build a flat arm");
        assertThrows(IllegalArgumentException.class, () -> arm(EightWayDirection.NORTH, 3));
        assertThrows(IllegalArgumentException.class, () -> edges(EightWayDirection.NORTH, ElevatedConnections.PITCH_NONE));
    }
}
