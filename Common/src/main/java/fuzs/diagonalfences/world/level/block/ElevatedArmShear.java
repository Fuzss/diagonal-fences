package fuzs.diagonalfences.world.level.block;

import fuzs.diagonalblocks.api.v2.util.EightWayDirection;

/**
 * The transform that turns a flat fence arm into a sloped one, for the render path.
 * <p>
 * <strong>This is a shear, not a rotation, and the difference is not cosmetic.</strong> Upstream's
 * {@code RotatedVariant} rotates its quads because a 45 degree turn about Y genuinely is a rotation.
 * The pitch is not: {@link ElevatedShapes} builds every collision step as
 * {@code extensionBottom + dy .. extensionTop + dy} and gives
 * {@link ElevatedShapes#pitchedArmEdges} two vertical end caps, so the arm's cross-section stays
 * vertical and slides upward in proportion to how far along the run it sits. A rotation would tilt
 * those end caps and shrink the rail's vertical thickness by {@code cos 45 = 0.71}, and the drawn
 * rail would then disagree with both the outline box the player aims at and the surface they walk
 * on. Render follows collision here; collision is not going to follow render, because a
 * {@link net.minecraft.world.phys.shapes.VoxelShape} cannot hold a rotated box.
 * <p>
 * There is deliberately <strong>no length scaling</strong>. The flat arm already reaches the block
 * edge, so shearing it there produces a beam {@code sqrt(2)} times longer on a cardinal and
 * {@code sqrt(1.5)} times longer on an intercardinal, with axis pitches of 45 and 35.26 degrees --
 * exactly the angles {@link ElevatedShapes} documents. Anything that also scales the arm along its
 * run is double-counting.
 * <p>
 * Coordinates are <strong>block space</strong> ({@code 0..1}), which is what baked quad positions
 * and {@link ElevatedShapes#pitchedArmEdges} both use -- not the {@code 0..16} pixel space the
 * {@link ElevatedShapes} collision boxes are built in.
 *
 * @see ElevatedShapes#pitchedArmEdges
 */
public final class ElevatedArmShear {
    /** The block centre on the horizontal axes, in block space. Also the shear's zero point. */
    static final float CENTRE = 0.5F;

    private ElevatedArmShear() {
        // static utility
    }

    /**
     * How far a vertex at {@code (x, z)} rises, in block space, for an arm pointing along
     * {@code direction} with the given pitch.
     * <p>
     * The run is the vertex's displacement from the block centre projected onto the arm direction,
     * normalised so that the block boundary is a full {@link ElevatedShapes#HALF_RISE}. Dividing by
     * {@code dx*dx + dz*dz} is what makes one formula cover both cases: it is {@code 1} on a
     * cardinal and {@code 2} on an intercardinal, where the two axis contributions each count half.
     * <p>
     * Vertices behind the centre yield a negative rise. That is correct and does not arise in
     * practice -- the {@code fence_side} model this is applied to lives entirely on the outward side
     * of the post -- but it means the shear is a genuine linear function rather than a clamped one,
     * which is what lets the {@code up}/{@code down} symmetry test work.
     *
     * @param direction the direction the arm points toward, using
     *                  {@link ElevatedShapes#pitchedArmEdges}' convention rather than upstream's
     *                  inverted one
     * @param pitch     {@link ElevatedConnections#PITCH_UP} or {@link ElevatedConnections#PITCH_DOWN}
     * @param x         vertex x in block space
     * @param z         vertex z in block space
     * @return the y offset to add to the vertex, in block space
     * @throws IllegalArgumentException if {@code pitch} is not a sloped pitch -- there is no arm to
     *                                  shear for {@link ElevatedConnections#PITCH_NONE}, and
     *                                  returning {@code 0} would silently draw a flat arm where the
     *                                  caller asked for no arm at all
     */
    public static float riseAt(EightWayDirection direction, int pitch, float x, float z) {
        int riseSign = ElevatedShapes.riseSign(pitch);
        int dirX = direction.getX();
        int dirZ = direction.getZ();
        float run = (x - CENTRE) * dirX + (z - CENTRE) * dirZ;
        return riseSign * run / (dirX * dirX + dirZ * dirZ);
    }
}
