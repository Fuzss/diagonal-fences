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
    /** Width of upstream's flat arm index: one bit per direction. */
    public static final int FLAT_INDEX_WIDTH = 8;
    /** No sloped arm in any direction. */
    public static final int EMPTY_PITCH_MASK = 0;
    /**
     * Flat index value meaning "as far as the caller knows, self carries no flat arms".
     * <p>
     * Passing it to {@link #computePitchMask(FenceView, BlockPos, int)} disables the fast path, so
     * every direction is resolved through the view -- which is exactly what the two-argument
     * overload does. It is a conservative default, not a claim about the block.
     */
    public static final int NO_FLAT_ARMS = 0;

    private static final int BITS_PER_DIRECTION = 2;
    private static final int PITCH_BITS = 0b11;
    private static final int FLAT_INDEX_LIMIT = 1 << FLAT_INDEX_WIDTH;

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
     * Whether upstream's flat arm index says a flat arm points toward {@code direction}.
     * <p>
     * Indexed the same way {@code StarShapeProvider#makeIndex} builds it, which sets
     * {@link EightWayDirection#getHorizontalIndex()} -- exactly {@code 1 << directionIndex} -- for
     * every direction whose block state property is true. That identity is pinned by a test, so a
     * change to it upstream fails here rather than silently suppressing every sloped arm.
     */
    public static boolean hasFlatArm(int flatIndex, EightWayDirection direction) {
        return ((flatIndex >>> directionIndex(direction)) & 1) != 0;
    }

    /**
     * Derives the full 16-bit pitch mask for the block at {@code blockPos}, asking the view about
     * every direction.
     * <p>
     * Up is tested before down, so a direction that could slope both ways rises. Only one arm per
     * direction is possible either way, since a single face cannot carry two arms.
     */
    public static int computePitchMask(FenceView fenceView, BlockPos blockPos) {
        return computePitchMask(fenceView, blockPos, NO_FLAT_ARMS);
    }

    /**
     * Derives the full 16-bit pitch mask for the block at {@code blockPos}, using a flat arm index
     * the caller already holds to skip work.
     * <p>
     * A direction whose bit is set in {@code selfFlatIndex} is skipped outright. That is exact, not
     * an approximation: {@link #connectsElevated} suppresses an arm when <em>either</em> end already
     * carries a flat arm along it, and self is the {@code lower} end of the up candidate and the
     * {@code upper} end of the down candidate, so its flat arm kills both. A fence in a straight run
     * therefore skips two of the eight directions along with every neighbour lookup behind them.
     * <p>
     * The result is identical to the two-argument overload whenever the index agrees with the view,
     * which a test asserts across every index.
     *
     * @param selfFlatIndex upstream's flat arm index for the block at {@code blockPos}, or
     *                      {@link #NO_FLAT_ARMS} to resolve every direction through the view
     * @throws IllegalArgumentException if the index is outside {@code 0..255}. Checked on the
     *                                  collision path deliberately: a wider value would carry stray
     *                                  bits into the skip test and silently drop sloped arms.
     */
    public static int computePitchMask(FenceView fenceView, BlockPos blockPos, int selfFlatIndex) {
        if (selfFlatIndex < 0 || selfFlatIndex >= FLAT_INDEX_LIMIT) {
            throw new IllegalArgumentException("flat index " + selfFlatIndex + " outside 0.." + (FLAT_INDEX_LIMIT - 1));
        }
        // One mutable position for all sixteen neighbour probes rather than sixteen allocations.
        // FenceView forbids implementations from retaining what they are handed, which is what makes
        // this safe; see the contract there.
        BlockPos.MutableBlockPos neighborBlockPos = new BlockPos.MutableBlockPos();
        int pitchMask = EMPTY_PITCH_MASK;
        for (EightWayDirection direction : EightWayDirection.values()) {
            if (hasFlatArm(selfFlatIndex, direction)) {
                continue;
            }
            neighborBlockPos.set(blockPos.getX() + direction.getX(),
                    blockPos.getY() + 1,
                    blockPos.getZ() + direction.getZ());
            if (connectsElevated(fenceView, blockPos, neighborBlockPos, direction)) {
                pitchMask = withPitch(pitchMask, direction, PITCH_UP);
                continue;
            }
            neighborBlockPos.setY(blockPos.getY() - 1);
            if (connectsElevated(fenceView, neighborBlockPos, blockPos, direction.getOpposite())) {
                pitchMask = withPitch(pitchMask, direction, PITCH_DOWN);
            }
        }
        return pitchMask;
    }
}
