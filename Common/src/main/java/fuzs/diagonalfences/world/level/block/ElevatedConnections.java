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
 * <strong>Only the top of a column grows an arm.</strong> A fence with another fence of its own kind
 * directly above it is a post, not a railing top, and neither end of an arm may be one. That is the
 * one rule left standing after the flat-arm veto went away, and it is what keeps a stacked fence wall
 * from turning into a lattice -- Phase 9 shipped without it and every block of every column sprouted
 * rails. It also retires the X that Phase 9 accepted: two adjacent two-tall columns are four covered
 * or covered-facing blocks, so nothing crosses.
 * <p>
 * The rule is applied to <em>both</em> ends. Applied to the lower end alone it would still let a
 * buried fence draw a falling arm, which is the same artefact upside down.
 * <p>
 * <strong>At most one sloped arm leaves a side, and the shortest arm wins it.</strong> A cardinal
 * arm has run {@code 8}; an intercardinal arm has run {@code 8 * sqrt(2)}. Cardinal is therefore
 * always the shorter of two arms contesting a side, and length never has to be computed: a cardinal
 * sloped arm simply suppresses the intercardinal sloped arms that leave through the same side, and a
 * cardinal arm is never suppressed by anything. This is what stops two parallel runs one block apart
 * and one block up from crossing into an X -- each low fence wants a straight arm and a diagonal
 * one, the straight arm wins, and the crossing never forms.
 * <p>
 * Two things the length rule cannot decide, decided by the owner on 2026-08-27:
 * <ul>
 * <li><strong>A tie is a fork, and a fork is kept.</strong> {@code NORTH_EAST} and
 * {@code NORTH_WEST} with no cardinal {@code NORTH} both survive; they leave through opposite
 * corners of the post and draw a V. Only a cardinal ever shadows.</li>
 * <li><strong>Flat rails do not compete.</strong> Only a sloped arm claims a side. Restoring flat
 * precedence here is what Phase 9 removed, and it would break a railing climbing past a two-tall
 * post all over again.</li>
 * </ul>
 * The accepted cost is that a post arriving on {@code NORTH} and leaving on {@code NORTH_EAST} loses
 * the {@code NORTH_EAST} rail. That kink and the X are locally the same shape, so one cannot be cut
 * without the other.
 * <p>
 * <strong>Once a side slopes, its flat rail into terrain is not drawn.</strong> That is
 * {@link #suppressFlatArms}, and it is the one place where a slope reaches back and changes what
 * else the fence looks like. Read it together with the flat-arm paragraph above: a flat arm never
 * decides whether a slope <em>forms</em>, and a slope decides whether a flat arm is <em>drawn</em>.
 * Those are opposite arrows between the same two things, and only the second one is live. A flat arm
 * that reaches another rail is kept either way -- a slope only ever takes over the invisible arm a
 * fence grows into a sturdy face.
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
     * unless a shorter arm has already claimed the side this one would leave through.
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
        if (!attachesElevated(fenceView, lower, upper, dirLowerToUpper)) {
            return false;
        }
        // A cardinal arm is the shortest arm there is, so nothing can outbid it for its own side and
        // the two shadow probes below are never worth paying for one.
        if (dirLowerToUpper.isCardinal()) {
            return true;
        }
        // Evaluated at both ends with arguments that do not depend on which end is asking, which is
        // what keeps the whole predicate symmetric -- exactly how the two ceiling probes above it
        // are written, and for the same reason.
        return !shadowedByCardinalArm(fenceView, lower, dirLowerToUpper)
                && !shadowedByCardinalArm(fenceView, upper, dirLowerToUpper.getOpposite());
    }

    /**
     * The length-blind half of {@link #connectsElevated}: the two blocks may attach and neither is
     * buried under a fence of its own kind. This is the whole rule for a cardinal arm and the
     * precondition for an intercardinal one.
     * <p>
     * Attachability is asked first because it is the test that fails for the overwhelming majority
     * of the sixteen probes a mask costs, so the two ceiling probes are only paid where an arm is
     * otherwise real. A flat arm on the same face used to veto the pitch here; see the class javadoc
     * for why that went away, and for why being buried under another fence still does.
     */
    private static boolean attachesElevated(FenceView fenceView, BlockPos lower, BlockPos upper, EightWayDirection dirLowerToUpper) {
        return fenceView.attachable(lower, upper, dirLowerToUpper) && !fenceView.fenceAbove(lower)
                && !fenceView.fenceAbove(upper);
    }

    /**
     * Whether a cardinal sloped arm at {@code blockPos} already claims one of the two sides
     * {@code intercardinal} leaves through.
     * <p>
     * {@link EightWayDirection#getCardinalNeighbors()} is upstream's own answer to "which two sides
     * does this corner sit between" -- {@code NORTH_EAST} yields {@code NORTH} and {@code EAST} --
     * so the pairing is not restated here where it could drift.
     * <p>
     * A rising arm and a falling arm shadow alike. The rule is about how many rails leave through
     * one side of a post, not about where they go, and a post that sheds one rail up and another
     * down through the same side is the fan this exists to thin.
     */
    private static boolean shadowedByCardinalArm(FenceView fenceView, BlockPos blockPos, EightWayDirection intercardinal) {
        for (EightWayDirection cardinal : intercardinal.getCardinalNeighbors()) {
            if (hasCardinalArm(fenceView, blockPos, cardinal)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code blockPos} carries a cardinal sloped arm toward {@code cardinal}, rising or
     * falling. Mirrors the up-then-down pairing {@link #computePitchMask} walks, so the two cannot
     * disagree about whether an arm is there.
     * <p>
     * <strong>This calls {@link #attachesElevated}, never {@link #connectsElevated}.</strong> That is
     * what makes the shadow rule terminate rather than recurse, and it is sound because a cardinal
     * arm is never itself shadowed -- nothing shorter exists to outbid it.
     */
    private static boolean hasCardinalArm(FenceView fenceView, BlockPos blockPos, EightWayDirection cardinal) {
        // A fresh position rather than the one computePitchMask reuses: this method is reached from
        // inside that loop, and blockPos may be that very mutable position.
        BlockPos.MutableBlockPos neighborBlockPos = new BlockPos.MutableBlockPos();
        neighborBlockPos.set(blockPos.getX() + cardinal.getX(),
                blockPos.getY() + 1,
                blockPos.getZ() + cardinal.getZ());
        if (attachesElevated(fenceView, blockPos, neighborBlockPos, cardinal)) {
            return true;
        }
        neighborBlockPos.setY(blockPos.getY() - 1);
        return attachesElevated(fenceView, neighborBlockPos, blockPos, cardinal.getOpposite());
    }

    /**
     * The pitch for one direction alone, without paying for the other seven.
     * <p>
     * This exists for the render path. {@code MultiPartModel} hands every submodel its own
     * {@code emitQuads} call with no shared per-position scratch, so a wrapped flat arm asking "does
     * my side slope?" cannot reuse a mask somebody else computed -- and computing the whole mask
     * eight more times per fence would cost sixteen probes where two will do.
     * <p>
     * <strong>It is the same code {@link #computePitchMask} runs</strong>, not a second opinion that
     * happens to agree today. The two disagreeing is exactly how the flat arm a shape suppresses and
     * the flat arm a model suppresses would drift apart.
     *
     * @return one of {@link #PITCH_NONE}, {@link #PITCH_UP}, {@link #PITCH_DOWN}
     */
    public static int pitchFor(FenceView fenceView, BlockPos blockPos, EightWayDirection direction) {
        return pitchFor(fenceView, blockPos, direction, new BlockPos.MutableBlockPos());
    }

    /**
     * Up is tested before down, so a direction that could slope both ways rises. Only one arm per
     * direction is possible either way, since a single face cannot carry two arms.
     *
     * @param neighborBlockPos scratch the caller owns, so the mask loop can probe sixteen positions
     *                         with one allocation. Overwritten on every call and never retained.
     */
    private static int pitchFor(FenceView fenceView, BlockPos blockPos, EightWayDirection direction, BlockPos.MutableBlockPos neighborBlockPos) {
        neighborBlockPos.set(blockPos.getX() + direction.getX(),
                blockPos.getY() + 1,
                blockPos.getZ() + direction.getZ());
        if (connectsElevated(fenceView, blockPos, neighborBlockPos, direction)) {
            return PITCH_UP;
        }
        neighborBlockPos.setY(blockPos.getY() - 1);
        if (connectsElevated(fenceView, neighborBlockPos, blockPos, direction.getOpposite())) {
            return PITCH_DOWN;
        }
        return PITCH_NONE;
    }

    /**
     * Clears the flat arm bit of every direction that carries a sloped arm: <strong>a sloped
     * connection on a side takes precedence over a flat one through the same side.</strong>
     * <p>
     * Owner's rule, 2026-08-27, from a playtest screenshot -- a post drew a flat rail into the grass
     * block beside it and a sloped rail to the fence one up and one along, both leaving through the
     * same face. <strong>This is not Phase 9's flat precedence coming back.</strong> That rule ran
     * the other way and decided where an arm may form; this one changes nothing about where a slope
     * forms and only removes the flat rail once one has.
     * <p>
     * The bit for a direction is {@link EightWayDirection#getHorizontalIndex()}, which is
     * {@code 1 << }{@link #directionIndex}; upstream's {@code StarShapeProvider#makeIndex} builds
     * the flat index out of exactly those bits.
     * <p>
     * <strong>Only a flat arm that runs into terrain is dropped.</strong> An arm that reaches
     * another rail is a rail somebody built and can see, and it survives the slope beside it --
     * owner's narrowing, 2026-08-27, and the reason this takes a {@link FenceView} rather than being
     * pure bit arithmetic. The one it does remove is the arm a fence grows into any sturdy face,
     * which is buried inside a full cube and invisible; the sloped arm takes that face over. See
     * {@link FenceView#hasFlatArmToRail}, and read its warning before using it anywhere else.
     * <p>
     * <strong>The block state is not touched.</strong> The {@code north}/{@code east}/... properties
     * still say what vanilla says -- suppression happens where the pitch itself happens, in the
     * shape and in the emitted quads, so no world can be left holding a state this feature invented.
     *
     * @param fenceView the world view, for the "does this arm reach a rail?" question
     * @param blockPos  the fence being resolved
     * @param flatIndex upstream's flat arm index
     * @param pitchMask a mask from {@link #computePitchMask}
     * @return {@code flatIndex} with the suppressed sides' bits cleared; unchanged for an empty mask
     */
    public static int suppressFlatArms(FenceView fenceView, BlockPos blockPos, int flatIndex, int pitchMask) {
        if (pitchMask == EMPTY_PITCH_MASK) {
            return flatIndex;
        }
        int suppressedFlatIndex = flatIndex;
        for (EightWayDirection direction : EightWayDirection.values()) {
            int bit = 1 << directionIndex(direction);
            // The bit test comes first and is not just an optimisation: with no flat arm on that
            // side there is nothing to suppress, and asking the view would be a level read per
            // direction per shape query for an answer that cannot change the result.
            if ((flatIndex & bit) != 0
                    && flatArmSuppressed(fenceView, blockPos, direction, pitchFor(pitchMask, direction))) {
                suppressedFlatIndex &= ~bit;
            }
        }
        return suppressedFlatIndex;
    }

    /**
     * Whether the flat arm on one side of {@code blockPos} gives way to a sloped one, asked about a
     * single direction.
     * <p>
     * This is what the render path calls -- a wrapped flat arm submodel deciding whether to emit --
     * while {@link #suppressFlatArms} answers the same question for all eight at once from a mask it
     * already has. Both end in {@link #flatArmSuppressed}, so there is one rule and only the way the
     * pitch is obtained differs; that the two ways of obtaining it agree is itself under test.
     */
    public static boolean suppressesFlatArm(FenceView fenceView, BlockPos blockPos, EightWayDirection direction) {
        return flatArmSuppressed(fenceView, blockPos, direction, pitchFor(fenceView, blockPos, direction));
    }

    /**
     * The rule itself: a side that slopes takes its flat arm, unless that arm reaches another rail.
     *
     * @param pitch the pitch already resolved for {@code direction}, however the caller got it
     */
    private static boolean flatArmSuppressed(FenceView fenceView, BlockPos blockPos, EightWayDirection direction, int pitch) {
        return pitch != PITCH_NONE && !fenceView.hasFlatArmToRail(blockPos, direction);
    }

    /**
     * Derives the full 16-bit pitch mask for the block at {@code blockPos}, asking the view about
     * every direction.
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
        // A buried fence is refused by connectsElevated as the lower end and as the upper end alike, so
        // every one of the sixteen probes below would come back false. Collapsing them to this single
        // lookup must stay exactly equivalent to the per-arm rule: widen one and this goes stale, and
        // the collision path and the render path would then draw different fences from one world.
        if (fenceView.fenceAbove(blockPos)) {
            return EMPTY_PITCH_MASK;
        }
        // One mutable position for all sixteen neighbour probes rather than sixteen allocations.
        // FenceView forbids implementations from retaining what they are handed, which is what makes
        // this safe; see the contract there.
        BlockPos.MutableBlockPos neighborBlockPos = new BlockPos.MutableBlockPos();
        int pitchMask = EMPTY_PITCH_MASK;
        for (EightWayDirection direction : EightWayDirection.values()) {
            int pitch = pitchFor(fenceView, blockPos, direction, neighborBlockPos);
            if (pitch != PITCH_NONE) {
                pitchMask = withPitch(pitchMask, direction, pitch);
            }
        }
        return pitchMask;
    }
}
