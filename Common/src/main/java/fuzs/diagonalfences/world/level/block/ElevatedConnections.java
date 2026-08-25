package fuzs.diagonalfences.world.level.block;

import fuzs.diagonalblocks.api.v2.util.EightWayDirection;
import net.minecraft.core.BlockPos;

/**
 * Decides where a fence grows a sloped arm to a neighbor one block up or down.
 * <p>
 * Nothing here is persisted. The pitch is derived from neighbors at query time and stored in no
 * block state property, which is what keeps the block state count at the stock 512 per fence and
 * makes the feature revertible with no world migration.
 * <p>
 * The pitch mask is 16 bits, two per direction, at {@code 2 * } the direction's index in the
 * existing 8-bit flat index. Sharing that indexing lets a shape be keyed as
 * {@code flatIndex | pitchMask << 8}.
 */
public final class ElevatedConnections {
    /** No sloped arm in this direction. */
    public static final int PITCH_NONE = 0;
    /** A sloped arm rising toward the neighbor one block up. */
    public static final int PITCH_UP = 1;
    /** A sloped arm falling toward the neighbor one block down. */
    public static final int PITCH_DOWN = 2;

    /** Width of the whole pitch mask, for the Phase 2 shape key. */
    public static final int PITCH_MASK_WIDTH = 16;

    private static final int BITS_PER_DIRECTION = 2;
    private static final int PITCH_BITS = 0b11;

    private ElevatedConnections() {
        // static utility
    }

    /**
     * The direction's index in the existing 8-bit flat index, {@code 0-3} cardinal and {@code 4-7}
     * intercardinal. Mirrors {@link EightWayDirection#getHorizontalIndex()}, which returns the same
     * position as a set bit.
     */
    public static int directionIndex(EightWayDirection direction) {
        return direction.isIntercardinal() ? 4 + direction.data2d : direction.data2d;
    }

    /**
     * Reads the pitch for one direction out of a mask.
     *
     * @return one of {@link #PITCH_NONE}, {@link #PITCH_UP}, {@link #PITCH_DOWN}
     */
    public static int pitchFor(int pitchMask, EightWayDirection direction) {
        return (pitchMask >>> (BITS_PER_DIRECTION * directionIndex(direction))) & PITCH_BITS;
    }

    /**
     * Returns a copy of the mask with the pitch for one direction replaced.
     *
     * @throws IllegalArgumentException if {@code pitch} is not one of the three defined values
     */
    public static int withPitch(int pitchMask, EightWayDirection direction, int pitch) {
        if (pitch != PITCH_NONE && pitch != PITCH_UP && pitch != PITCH_DOWN) {
            throw new IllegalArgumentException("unknown pitch value " + pitch + " for direction " + direction);
        }
        int shift = BITS_PER_DIRECTION * directionIndex(direction);
        return (pitchMask & ~(PITCH_BITS << shift)) | (pitch << shift);
    }

    /**
     * The single symmetric connection predicate.
     * <p>
     * Both ends of a candidate arm resolve their pitch through this one method with identical
     * arguments -- {@code lower} is always the lower position. Writing it as "the lower block looks
     * up, the upper block looks down" instead lets flat precedence diverge at the two ends, which
     * produces an arm that exists at one end only.
     *
     * @param fenceView       the world view
     * @param lower           the lower end of the candidate arm
     * @param upper           the upper end, exactly one block up and one step along {@code dirLowerToUpper}
     * @param dirLowerToUpper the horizontal direction pointing from {@code lower} to {@code upper}
     * @return whether a sloped arm connects the two positions
     * @throws IllegalArgumentException if the two positions are not a valid arm for that direction
     */
    public static boolean connectsElevated(FenceView fenceView, BlockPos lower, BlockPos upper, EightWayDirection dirLowerToUpper) {
        if (upper.getY() - lower.getY() != 1 || upper.getX() - lower.getX() != dirLowerToUpper.getX()
                || upper.getZ() - lower.getZ() != dirLowerToUpper.getZ()) {
            throw new IllegalArgumentException(
                    "positions " + lower + " and " + upper + " are not a " + dirLowerToUpper + " arm rising by one");
        }
        // Flat connections win, at either end -- a flat arm already occupies that face. Both checks
        // key off the same (lower, upper, direction) triple, so the result stays symmetric.
        if (fenceView.hasFlatArm(lower, dirLowerToUpper) || fenceView.hasFlatArm(upper, dirLowerToUpper.getOpposite())) {
            return false;
        }
        return fenceView.attachable(lower, upper, dirLowerToUpper);
    }

    /**
     * Derives the full 16-bit pitch mask for the block at {@code blockPos}.
     * <p>
     * Up is tested before down, so a direction that could slope both ways rises. Only one arm per
     * direction is possible either way, since a single face cannot carry two arms.
     */
    public static int computePitchMask(FenceView fenceView, BlockPos blockPos) {
        int pitchMask = 0;
        for (EightWayDirection direction : EightWayDirection.values()) {
            BlockPos upperNeighbor = blockPos.offset(direction.getX(), 1, direction.getZ());
            if (connectsElevated(fenceView, blockPos, upperNeighbor, direction)) {
                pitchMask = withPitch(pitchMask, direction, PITCH_UP);
                continue;
            }
            BlockPos lowerNeighbor = blockPos.offset(direction.getX(), -1, direction.getZ());
            if (connectsElevated(fenceView, lowerNeighbor, blockPos, direction.getOpposite())) {
                pitchMask = withPitch(pitchMask, direction, PITCH_DOWN);
            }
        }
        return pitchMask;
    }
}
