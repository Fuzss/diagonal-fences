package fuzs.diagonalfences.world.level.block;

import fuzs.diagonalblocks.api.v2.util.EightWayDirection;
import fuzs.diagonalblocks.impl.world.phys.shapes.VoxelUtils;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Geometry for a single sloped arm. Pure functions -- no block, no block state, no cache.
 * <p>
 * An arm between two fences one block apart horizontally and one block apart vertically is split in
 * half, and each block draws only the half inside itself: from the block centre (run {@code 8},
 * {@code dy 0}) out to the block edge (run {@code 16} or {@code 0}, {@code dy +/-8}). The neighbour
 * draws the mirror half as the opposite pitch, and the two meet exactly on the shared plane. For a
 * {@link EightWayDirection#SOUTH} arm the lower block's step {@code i} sits at world
 * {@code (z = 16 - i, y = base + 8 - i)} and the upper block's {@link EightWayDirection#NORTH} step
 * {@code i} at {@code (z = 16 + i, y = base + 8 + i)}: they agree at {@code i = 0} and continue at
 * the same gradient.
 * <p>
 * Splitting the beam this way -- rather than letting the lower block draw the whole thing -- keeps
 * every block's geometry inside (or barely outside) its own bounds, which is what chunk section
 * meshing and culling assume.
 * <p>
 * The rise is {@link #HALF_RISE} across the half-arm either way, so the pitch is 45 degrees on a
 * cardinal (run 8) and 35.26 degrees on an intercardinal (run 8 * sqrt(2)). Those are the angles the
 * Phase 4 render path has to rotate quads by.
 *
 * @see fuzs.diagonalblocks.api.v2.block.StarShapeProvider#getDiagonalShape
 */
public final class ElevatedShapes {
    /**
     * Sub-boxes per pitched arm, mirroring
     * {@link fuzs.diagonalblocks.api.v2.block.StarShapeProvider#getDiagonalCollisionShape}. Step
     * {@code 0} sits on the block edge, step {@code STEPS - 1} one pixel short of the centre, where
     * the post covers the remainder.
     */
    public static final int STEPS = 8;
    /** Rise in pixels across the half of the arm that lives inside one block. */
    public static final int HALF_RISE = 8;
    /** Run coordinate of the block centre, in pixels. */
    static final float CENTRE = 8.0F;
    /** {@code cos(-pi/4)} -- upstream's own constant for a 45 degree rotated extension. */
    private static final float DIAGONAL_FACTOR = 0.7071067812F;

    private ElevatedShapes() {
        // static utility
    }

    /**
     * The stepped collision geometry of one sloped arm: {@link #STEPS} boxes of the arm's
     * cross-section, each one pixel further along the run and one pixel further up (or down).
     * <p>
     * The one position formula covers cardinal and intercardinal alike -- a cardinal arm holds the
     * perpendicular axis at the centre while the run axis steps -- so cardinal arms get a genuinely
     * stepped shape rather than the single {@code Block.box} upstream uses for a flat one.
     * <p>
     * Boxes deliberately leave the {@code 0..16} range: a down-pitched arm reaches {@code y = -8} at
     * the block edge and an up-pitched collision arm reaches {@code y = 32}. Both are legal, and
     * upstream's own diagonal arms already leave the range horizontally.
     *
     * @param extensionWidth  half-width of the arm's cross-section, in pixels
     * @param extensionBottom bottom of the arm at the block centre, in pixels
     * @param extensionTop    top of the arm at the block centre, in pixels
     * @param direction       the direction the arm actually points toward -- <strong>not</strong>
     *                        upstream's inverted convention, see {@link #pitchedArmEdges}
     * @param pitch           {@link ElevatedConnections#PITCH_UP} or
     *                        {@link ElevatedConnections#PITCH_DOWN}
     * @return the optimized union of the stepped boxes
     */
    public static VoxelShape pitchedArmCollisionShape(float extensionWidth, float extensionBottom, float extensionTop, EightWayDirection direction, int pitch) {
        int riseSign = riseSign(pitch);
        VoxelShape collisionShape = Shapes.empty();
        for (int i = 0; i < STEPS; i++) {
            float posX = stepCoordinate(direction.getX(), i);
            float posZ = stepCoordinate(direction.getZ(), i);
            float dy = riseSign * (STEPS - i);
            VoxelShape cuboidShape = Block.box(posX - extensionWidth,
                    extensionBottom + dy,
                    posZ - extensionWidth,
                    posX + extensionWidth,
                    extensionTop + dy,
                    posZ + extensionWidth);
            collisionShape = Shapes.joinUnoptimized(collisionShape, cuboidShape, BooleanOp.OR);
        }
        return collisionShape.optimize();
    }

    /**
     * The twelve edges of the sloped arm as a skewed box, already scaled to block space. These are
     * what the block highlight draws, so the outline is a clean sloped line rather than the staircase
     * {@link #pitchedArmCollisionShape} collides against.
     * <p>
     * <strong>Direction convention.</strong> This takes the direction the arm points toward.
     * Upstream's {@link fuzs.diagonalblocks.api.v2.block.StarShapeProvider#getDiagonalShape} takes the
     * <em>opposite</em> one -- it builds its beam toward the origin corner and relies on the call site
     * reshuffling {@code diagonalShapes[2], [3], [0], [1]} to line the array back up. Do not pass a
     * direction through both without converting.
     *
     * @param stretchWidth whether the cross-section is widened by sqrt(2) on an intercardinal so a
     *                     rotated arm still matches the post, as upstream does when the node and
     *                     extension widths are equal
     * @return edge endpoints in pairs, in block space
     */
    public static Vec3[] pitchedArmEdges(float extensionWidth, float extensionBottom, float extensionTop, EightWayDirection direction, int pitch, boolean stretchWidth) {
        float dy = riseSign(pitch) * (float) HALF_RISE;
        float edgeX = edgeCoordinate(direction.getX());
        float edgeZ = edgeCoordinate(direction.getZ());
        float offsetX;
        float offsetZ;
        if (direction.isIntercardinal()) {
            float halfWidth = stretchWidth ? (float) Math.sqrt(extensionWidth * extensionWidth * 2.0F) :
                    extensionWidth;
            float diagonalSide = DIAGONAL_FACTOR * halfWidth;
            // perpendicular to the run (x, z) is (z, -x), scaled to the diagonal half width
            offsetX = diagonalSide * direction.getZ();
            offsetZ = -diagonalSide * direction.getX();
        } else if (direction.getX() != 0) {
            offsetX = 0.0F;
            offsetZ = extensionWidth;
        } else {
            offsetX = extensionWidth;
            offsetZ = 0.0F;
        }
        // ordered the way create12Edges wants it: one whole side of the beam (top edge end, top
        // centre end, bottom edge end, bottom centre end), then the same four on the opposite side
        Vec3[] corners = VoxelUtils.createVectorArray(edgeX + offsetX,
                extensionTop + dy,
                edgeZ + offsetZ,
                CENTRE + offsetX,
                extensionTop,
                CENTRE + offsetZ,
                edgeX + offsetX,
                extensionBottom + dy,
                edgeZ + offsetZ,
                CENTRE + offsetX,
                extensionBottom,
                CENTRE + offsetZ,
                edgeX - offsetX,
                extensionTop + dy,
                edgeZ - offsetZ,
                CENTRE - offsetX,
                extensionTop,
                CENTRE - offsetZ,
                edgeX - offsetX,
                extensionBottom + dy,
                edgeZ - offsetZ,
                CENTRE - offsetX,
                extensionBottom,
                CENTRE - offsetZ);
        return VoxelUtils.scaleDown(VoxelUtils.create12Edges(corners));
    }

    /**
     * Where step {@code step} sits on one axis: held at the centre for an axis the arm does not move
     * along, otherwise walking inward from the block edge.
     */
    static float stepCoordinate(int directionComponent, int step) {
        if (directionComponent == 0) {
            return CENTRE;
        } else {
            return directionComponent > 0 ? 16.0F - step : step;
        }
    }

    /**
     * Where the arm meets the block boundary on one axis.
     */
    static float edgeCoordinate(int directionComponent) {
        if (directionComponent == 0) {
            return CENTRE;
        } else {
            return directionComponent > 0 ? 16.0F : 0.0F;
        }
    }

    /**
     * @throws IllegalArgumentException if {@code pitch} is not a sloped pitch; there is no arm to
     *                                  build for {@link ElevatedConnections#PITCH_NONE}, and quietly
     *                                  treating that as flat would put a horizontal arm where the
     *                                  caller asked for no arm at all
     */
    static int riseSign(int pitch) {
        return switch (pitch) {
            case ElevatedConnections.PITCH_UP -> 1;
            case ElevatedConnections.PITCH_DOWN -> -1;
            default -> throw new IllegalArgumentException("no sloped arm exists for pitch " + pitch);
        };
    }
}
