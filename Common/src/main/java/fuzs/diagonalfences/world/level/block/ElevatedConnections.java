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
 * <p>
 * <strong>A flat arm on a face does not suppress a pitch on that face.</strong> It used to, on the
 * strength of an analogy to upstream's {@code isFreeForDiagonalProperty}; the analogy was wrong
 * twice over. The two arms overlap only inside the post's own 4x4 footprint -- the same reasoning
 * already accepted for arms buried in a riser block -- and suppressing on that basis is what stopped
 * a railing climbing past a two-tall fence column, which is the shape the feature exists to draw.
 * <p>
 * <strong>Consequence, and it is intended:</strong> two adjacent two-tall columns grow an X. The
 * lower fence rises to the far upper one while the upper fence falls to the far lower one. Each
 * direction still carries at most one arm, because {@link #computePitchMask} takes the first of
 * up-then-down.
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
     * The single symmetric connection predicate: two fences one block up and one step along attach,
     * full stop.
     * <p>
     * Both ends of a candidate arm resolve their pitch through this one method with identical
     * arguments -- {@code lower} is always the lower position. Writing it as "the lower block looks
     * up, the upper block looks down" instead lets the two ends diverge, which produces an arm that
     * exists at one end only. That risk is smaller now than it was, but the shape of the call is
     * kept because it is what makes the symmetry property testable.
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
        // Attachability is the whole rule. A flat arm on the same face used to veto the pitch here;
        // see the class javadoc for why that went away.
        return fenceView.attachable(lower, upper, dirLowerToUpper);
    }

    /**
     * Derives the full 16-bit pitch mask for the block at {@code blockPos}, asking the view about
     * every direction.
     * <p>
     * Up is tested before down, so a direction that could slope both ways rises. Only one arm per
     * direction is possible either way, since a single face cannot carry two arms.
     * <p>
     * <strong>There is deliberately no flat-index fast path.</strong> One used to live here: a
     * direction whose bit was set in the block's own flat arm index was skipped without asking the
     * view. That was sound only while a set property meant "this face carries a rail", and it does
     * not -- vanilla sets it against any sturdy face too, so the skip suppressed every staircase
     * railing in the game. Reinstating it would be worse than the bug it optimised: collision holds
     * the index and render does not, so the two would draw different fences from the same world.
     * If this ever needs to be cheaper, make {@link FenceView} cheaper -- do not reintroduce a
     * shortcut that only one of the two callers can take.
     */
    public static int computePitchMask(FenceView fenceView, BlockPos blockPos) {
        // One mutable position for all sixteen neighbour probes rather than sixteen allocations.
        // FenceView forbids implementations from retaining what they are handed, which is what makes
        // this safe; see the contract there.
        BlockPos.MutableBlockPos neighborBlockPos = new BlockPos.MutableBlockPos();
        int pitchMask = EMPTY_PITCH_MASK;
        for (EightWayDirection direction : EightWayDirection.values()) {
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
