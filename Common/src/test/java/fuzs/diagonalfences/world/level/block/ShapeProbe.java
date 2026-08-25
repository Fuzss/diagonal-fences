package fuzs.diagonalfences.world.level.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Point and box probes against a {@link VoxelShape}.
 * <p>
 * A {@code VoxelShape} exposes no containment test, so everything here goes through
 * {@link Shapes#joinIsNotEmpty}: {@link BooleanOp#AND} answers "do these overlap at all",
 * {@link BooleanOp#ONLY_FIRST} answers "is any part of the first outside the second", which negated
 * is full containment. Coordinates are in pixels, the same units the shape builders take.
 */
final class ShapeProbe {
    /**
     * Half-extent of a point probe, in pixels. Small enough to sit inside one sub-box, large enough
     * that the probe has real volume -- a zero-volume box never intersects anything.
     */
    private static final double POINT_PROBE_RADIUS = 0.25;

    private ShapeProbe() {
        // static utility
    }

    static VoxelShape box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return Block.box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    static boolean touches(VoxelShape voxelShape, double x, double y, double z) {
        VoxelShape probe = Block.box(x - POINT_PROBE_RADIUS,
                y - POINT_PROBE_RADIUS,
                z - POINT_PROBE_RADIUS,
                x + POINT_PROBE_RADIUS,
                y + POINT_PROBE_RADIUS,
                z + POINT_PROBE_RADIUS);
        return Shapes.joinIsNotEmpty(voxelShape, probe, BooleanOp.AND);
    }

    static void assertTouches(VoxelShape voxelShape, double x, double y, double z, String message) {
        assertTrue(touches(voxelShape, x, y, z), message + " -- expected the shape to occupy (" + x + ", " + y + ", " + z + ")");
    }

    static void assertDoesNotTouch(VoxelShape voxelShape, double x, double y, double z, String message) {
        assertFalse(touches(voxelShape, x, y, z), message + " -- expected the shape to leave (" + x + ", " + y + ", " + z + ") free");
    }

    /**
     * Asserts that {@code probe} lies entirely inside {@code voxelShape}, not merely that they touch.
     */
    static void assertContains(VoxelShape voxelShape, VoxelShape probe, String message) {
        assertFalse(Shapes.joinIsNotEmpty(probe, voxelShape, BooleanOp.ONLY_FIRST),
                message + " -- part of " + probe.bounds() + " falls outside the shape");
    }

    /** How many edges {@link VoxelShape#forAllEdges} emits. */
    static int countEdges(VoxelShape voxelShape) {
        int[] count = new int[1];
        voxelShape.forAllEdges((double x1, double y1, double z1, double x2, double y2, double z2) -> count[0]++);
        return count[0];
    }
}
