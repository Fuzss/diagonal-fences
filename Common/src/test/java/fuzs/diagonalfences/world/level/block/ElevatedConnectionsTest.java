package fuzs.diagonalfences.world.level.block;

import fuzs.diagonalblocks.api.v2.util.EightWayDirection;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElevatedConnectionsTest {
    private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);

    private static BlockPos up(EightWayDirection direction) {
        return ORIGIN.offset(direction.getX(), 1, direction.getZ());
    }

    private static BlockPos down(EightWayDirection direction) {
        return ORIGIN.offset(direction.getX(), -1, direction.getZ());
    }

    // --- mask packing -------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void maskPackingRoundTrips(EightWayDirection direction) {
        for (int pitch : new int[]{
                ElevatedConnections.PITCH_NONE, ElevatedConnections.PITCH_UP, ElevatedConnections.PITCH_DOWN
        }) {
            int pitchMask = ElevatedConnections.withPitch(0, direction, pitch);
            assertEquals(pitch, ElevatedConnections.pitchFor(pitchMask, direction));
        }
    }

    @Test
    void everyDirectionOccupiesItsOwnBitsAndFitsIn16() {
        int pitchMask = 0;
        for (EightWayDirection direction : EightWayDirection.values()) {
            pitchMask = ElevatedConnections.withPitch(pitchMask,
                    direction,
                    direction.isIntercardinal() ? ElevatedConnections.PITCH_DOWN : ElevatedConnections.PITCH_UP);
        }
        // Writing all eight directions must not disturb any other direction.
        for (EightWayDirection direction : EightWayDirection.values()) {
            assertEquals(direction.isIntercardinal() ? ElevatedConnections.PITCH_DOWN : ElevatedConnections.PITCH_UP,
                    ElevatedConnections.pitchFor(pitchMask, direction),
                    "direction " + direction + " was clobbered");
        }
        assertEquals(0, pitchMask >>> ElevatedConnections.PITCH_MASK_WIDTH, "mask overflowed 16 bits");
    }

    @Test
    void withPitchOverwritesRatherThanOrs() {
        int pitchMask = ElevatedConnections.withPitch(0, EightWayDirection.NORTH, ElevatedConnections.PITCH_DOWN);
        pitchMask = ElevatedConnections.withPitch(pitchMask, EightWayDirection.NORTH, ElevatedConnections.PITCH_UP);
        assertEquals(ElevatedConnections.PITCH_UP, ElevatedConnections.pitchFor(pitchMask, EightWayDirection.NORTH));
    }

    @Test
    void withPitchRejectsUndefinedValues() {
        assertThrows(IllegalArgumentException.class,
                () -> ElevatedConnections.withPitch(0, EightWayDirection.NORTH, 3));
    }

    @Test
    void directionIndexIsDistinctAndAgreesWithTheFlatIndex() {
        Set<Integer> seen = new HashSet<>();
        for (EightWayDirection direction : EightWayDirection.values()) {
            assertEquals(direction.getHorizontalIndex(),
                    1 << ElevatedConnections.directionIndex(direction),
                    "pitch index for " + direction + " must sit at the same position as its flat index bit");
            assertTrue(seen.add(ElevatedConnections.directionIndex(direction)),
                    "two directions share index " + ElevatedConnections.directionIndex(direction));
        }
        assertEquals(8, seen.size());
    }

    // --- pitch detection ----------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void detectsUpwardPitchInEveryDirection(EightWayDirection direction) {
        FakeFenceView fenceView = new FakeFenceView().withFence(ORIGIN).withFence(up(direction));
        assertEquals(ElevatedConnections.PITCH_UP,
                ElevatedConnections.pitchFor(ElevatedConnections.computePitchMask(fenceView, ORIGIN), direction));
    }

    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void detectsDownwardPitchInEveryDirection(EightWayDirection direction) {
        FakeFenceView fenceView = new FakeFenceView().withFence(ORIGIN).withFence(down(direction));
        assertEquals(ElevatedConnections.PITCH_DOWN,
                ElevatedConnections.pitchFor(ElevatedConnections.computePitchMask(fenceView, ORIGIN), direction));
    }

    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void aLoneFenceHasNoPitch(EightWayDirection direction) {
        FakeFenceView fenceView = new FakeFenceView().withFence(ORIGIN);
        assertEquals(ElevatedConnections.PITCH_NONE,
                ElevatedConnections.pitchFor(ElevatedConnections.computePitchMask(fenceView, ORIGIN), direction));
    }

    /**
     * The property that catches a one-sided arm: whatever the lower block reports as
     * {@link ElevatedConnections#PITCH_UP} toward D, the upper block must report as
     * {@link ElevatedConnections#PITCH_DOWN} toward the opposite of D.
     */
    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void pitchIsSymmetricAtBothEnds(EightWayDirection direction) {
        BlockPos upper = up(direction);
        FakeFenceView fenceView = new FakeFenceView().withFence(ORIGIN).withFence(upper);

        int lowerMask = ElevatedConnections.computePitchMask(fenceView, ORIGIN);
        int upperMask = ElevatedConnections.computePitchMask(fenceView, upper);

        assertEquals(ElevatedConnections.PITCH_UP, ElevatedConnections.pitchFor(lowerMask, direction));
        assertEquals(ElevatedConnections.PITCH_DOWN,
                ElevatedConnections.pitchFor(upperMask, direction.getOpposite()),
                "arm exists at the lower end but not at the upper end");
    }

    @Test
    void aSameHeightOrStackedNeighborIsNotASlopedArm() {
        FakeFenceView fenceView = new FakeFenceView().withFence(ORIGIN)
                .withFence(ORIGIN.offset(1, 0, 0))
                .withFence(ORIGIN.offset(0, 1, 0));
        assertEquals(0,
                ElevatedConnections.computePitchMask(fenceView, ORIGIN),
                "a same-height neighbor and a directly stacked neighbor are not sloped arms");
    }

    @Test
    void upWinsOverDownWhenBothAreAvailable() {
        EightWayDirection direction = EightWayDirection.NORTH;
        FakeFenceView fenceView = new FakeFenceView().withFence(ORIGIN)
                .withFence(up(direction))
                .withFence(down(direction));
        assertEquals(ElevatedConnections.PITCH_UP,
                ElevatedConnections.pitchFor(ElevatedConnections.computePitchMask(fenceView, ORIGIN), direction));
    }

    // --- only the top of a column grows an arm (Phase 10) ---------------------------------------

    /**
     * <strong>Phase 10, and the reported artefact.</strong> The owner's screenshot: a stacked fence
     * wall where every block of every column had sprouted a rail. A fence with another fence on top
     * of it is a post; it draws nothing, in any of the eight directions, up or down.
     */
    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void aBuriedFenceGrowsNoArmAtAll(EightWayDirection direction) {
        FakeFenceView fenceView = new FakeFenceView().withFence(ORIGIN)
                .withFence(ORIGIN.above())
                .withFence(up(direction))
                .withFence(down(direction));
        assertEquals(ElevatedConnections.EMPTY_PITCH_MASK,
                ElevatedConnections.computePitchMask(fenceView, ORIGIN),
                "a fence with a fence on top must draw no rail toward " + direction);
    }

    /**
     * The other half of the rule, and the half a lower-end-only implementation would drop: the
     * block being asked is a perfectly good top piece, but the far end of the arm is buried. No arm
     * -- otherwise the buried fence would be seen carrying a falling rail, which is the same
     * artefact mirrored.
     */
    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void anArmIsRefusedWhenTheFarEndIsBuried(EightWayDirection direction) {
        FakeFenceView fenceView = new FakeFenceView().withFence(ORIGIN)
                .withFence(up(direction))
                .withFence(up(direction).above());
        assertEquals(ElevatedConnections.PITCH_NONE,
                ElevatedConnections.pitchFor(ElevatedConnections.computePitchMask(fenceView, ORIGIN), direction),
                "the near fence is a top piece but the far one is not");
        assertEquals(ElevatedConnections.EMPTY_PITCH_MASK,
                ElevatedConnections.computePitchMask(fenceView, up(direction)),
                "and the buried far fence must not report the arm either");
    }

    /**
     * The control that keeps the two tests above from passing vacuously. The same two-tall column,
     * read from its <em>top</em> block: that one is a railing top and does connect. Without this,
     * code that had simply stopped sloping anywhere near a vertical stack would pass, and it would
     * break the shape Phase 9 exists for -- a railing climbing past a post.
     */
    @Test
    void theTopOfAColumnStillClimbs() {
        BlockPos columnTop = ORIGIN.above();
        FakeFenceView fenceView = new FakeFenceView().withFence(ORIGIN)
                .withFence(columnTop)
                .withFence(columnTop.offset(1, 1, 0));
        assertEquals(ElevatedConnections.PITCH_UP,
                ElevatedConnections.pitchFor(ElevatedConnections.computePitchMask(fenceView, columnTop),
                        EightWayDirection.EAST),
                "the top block of a two-tall column must still rise east");
    }

    /**
     * <strong>Phase 10 inverts Phase 9's X.</strong> Two adjacent two-tall columns used to cross:
     * the lower fence of each rose to the top of the other. Seen in game and rejected by the owner
     * -- every block involved is buried or faces a buried block, so all four arms are gone. The
     * fixture is Phase 9's, unchanged, so the diff is the assertion and nothing else.
     *
     * @see ElevatedConnections
     */
    @Test
    void twoAdjacentTwoTallColumnsDoNotCross() {
        BlockPos nearTop = ORIGIN.above();
        BlockPos farBottom = ORIGIN.offset(1, 0, 0);
        BlockPos farTop = ORIGIN.offset(1, 1, 0);
        FakeFenceView fenceView = new FakeFenceView().withFence(ORIGIN)
                .withFence(nearTop)
                .withFence(farBottom)
                .withFence(farTop);

        assertEquals(ElevatedConnections.EMPTY_PITCH_MASK,
                ElevatedConnections.computePitchMask(fenceView, ORIGIN),
                "the near lower fence is buried and must not rise to the far upper one");
        assertEquals(ElevatedConnections.EMPTY_PITCH_MASK,
                ElevatedConnections.computePitchMask(fenceView, nearTop),
                "the near upper fence must not fall to the far lower one -- the far one is buried");
        assertEquals(ElevatedConnections.EMPTY_PITCH_MASK,
                ElevatedConnections.computePitchMask(fenceView, farBottom));
        assertEquals(ElevatedConnections.EMPTY_PITCH_MASK,
                ElevatedConnections.computePitchMask(fenceView, farTop));
    }

    // --- a flat arm suppresses nothing (Phase 9) ------------------------------------------------

    /**
     * The Phase 9 build, at this seam: a fence whose face already carries a flat rail must still
     * reach the fence one up and one along. Nothing here is buried, so Phase 10 leaves it alone --
     * the two rules are independent and this is the test that says so. The fake records only
     * attachability, so the assertion that the flat neighbour's <em>property</em> changes nothing
     * lives in {@code ElevatedFenceInLevelTest}, where a real side property exists to be ignored.
     */
    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void aFlatNeighbourDoesNotBlockTheArmAboveIt(EightWayDirection direction) {
        FakeFenceView fenceView = new FakeFenceView().withFence(ORIGIN)
                .withFence(ORIGIN.offset(direction.getX(), 0, direction.getZ()))
                .withFence(up(direction));

        assertEquals(ElevatedConnections.PITCH_UP,
                ElevatedConnections.pitchFor(ElevatedConnections.computePitchMask(fenceView, ORIGIN), direction),
                "a rail on the flat face must not stop the arm climbing over it");
    }

    // --- a cardinal arm shadows the diagonals through its side (Phase 11) ----------------------

    /**
     * <strong>Phase 11, the rule itself.</strong> A cardinal arm has run {@code 8} and an
     * intercardinal one run {@code 8 * sqrt(2)}, so the cardinal is always the shorter arm and takes
     * the side. The two diagonals that leave through the same side lose.
     */
    @Test
    void aCardinalArmShadowsTheDiagonalsThatShareItsSide() {
        FakeFenceView fenceView = new FakeFenceView().withFence(ORIGIN)
                .withFence(up(EightWayDirection.NORTH))
                .withFence(up(EightWayDirection.NORTH_EAST))
                .withFence(up(EightWayDirection.NORTH_WEST));

        int pitchMask = ElevatedConnections.computePitchMask(fenceView, ORIGIN);
        assertEquals(ElevatedConnections.PITCH_UP,
                ElevatedConnections.pitchFor(pitchMask, EightWayDirection.NORTH),
                "the shortest arm keeps the side and is never itself shadowed");
        assertEquals(ElevatedConnections.PITCH_NONE,
                ElevatedConnections.pitchFor(pitchMask, EightWayDirection.NORTH_EAST),
                "the north-east arm leaves through the north side, which north already took");
        assertEquals(ElevatedConnections.PITCH_NONE,
                ElevatedConnections.pitchFor(pitchMask, EightWayDirection.NORTH_WEST),
                "and so does the north-west arm");
    }

    /**
     * Shadowing is per side, not per post. A cardinal arm on the far side of the block takes nothing
     * away from a diagonal that never touches it -- otherwise one straight rail anywhere would strip
     * a post of every diagonal it has.
     */
    @Test
    void aCardinalArmOnAnUnrelatedSideShadowsNothing() {
        FakeFenceView fenceView = new FakeFenceView().withFence(ORIGIN)
                .withFence(up(EightWayDirection.SOUTH))
                .withFence(up(EightWayDirection.NORTH_EAST));

        int pitchMask = ElevatedConnections.computePitchMask(fenceView, ORIGIN);
        assertEquals(ElevatedConnections.PITCH_UP,
                ElevatedConnections.pitchFor(pitchMask, EightWayDirection.SOUTH));
        assertEquals(ElevatedConnections.PITCH_UP,
                ElevatedConnections.pitchFor(pitchMask, EightWayDirection.NORTH_EAST),
                "south and north-east share no side; the diagonal must survive");
    }

    /**
     * The owner's tie-break, 2026-08-27: two diagonals through one side are equal length, so nothing
     * outbids anything and <em>both</em> are kept. They leave through opposite corners of the post
     * and draw a V. Only a cardinal ever shadows.
     */
    @Test
    void twoDiagonalsThroughOneSideAreAForkAndBothSurvive() {
        FakeFenceView fenceView = new FakeFenceView().withFence(ORIGIN)
                .withFence(up(EightWayDirection.NORTH_EAST))
                .withFence(up(EightWayDirection.NORTH_WEST));

        int pitchMask = ElevatedConnections.computePitchMask(fenceView, ORIGIN);
        assertEquals(ElevatedConnections.PITCH_UP,
                ElevatedConnections.pitchFor(pitchMask, EightWayDirection.NORTH_EAST));
        assertEquals(ElevatedConnections.PITCH_UP,
                ElevatedConnections.pitchFor(pitchMask, EightWayDirection.NORTH_WEST),
                "with no cardinal north arm to outbid them, a fork keeps both rails");
    }

    /**
     * The half a near-end-only implementation would drop, and the half that would produce a
     * one-ended arm: the block being asked has no cardinal arm at all, but the far end of the
     * diagonal does, on a side the diagonal arrives through.
     */
    @Test
    void theFarEndsCardinalArmShadowsToo() {
        BlockPos upper = up(EightWayDirection.NORTH_EAST);
        // a rising west arm from the far end -- west is one of the two sides the north-east arm
        // arrives through, since the arm enters the far end travelling south-west
        BlockPos farCardinal = upper.offset(EightWayDirection.WEST.getX(), 1, EightWayDirection.WEST.getZ());
        FakeFenceView fenceView = new FakeFenceView().withFence(ORIGIN).withFence(upper).withFence(farCardinal);

        assertEquals(ElevatedConnections.PITCH_NONE,
                ElevatedConnections.pitchFor(ElevatedConnections.computePitchMask(fenceView, ORIGIN),
                        EightWayDirection.NORTH_EAST),
                "the near end is clear but the far end's west side is already taken");
        int upperMask = ElevatedConnections.computePitchMask(fenceView, upper);
        assertEquals(ElevatedConnections.PITCH_NONE,
                ElevatedConnections.pitchFor(upperMask, EightWayDirection.SOUTH_WEST),
                "and the far end must not report the arm either");
        assertEquals(ElevatedConnections.PITCH_UP,
                ElevatedConnections.pitchFor(upperMask, EightWayDirection.WEST),
                "the arm that did the shadowing is still there");
    }

    /**
     * A side is a side whichever way its rail goes. This is also the accepted cost of the rule
     * written down: a post that arrives on {@code NORTH} and leaves on {@code NORTH_EAST} -- an
     * ordinary kink in a railing -- loses the diagonal. The kink and the X the rule exists to cut
     * are locally the same shape, so one cannot be kept without the other.
     */
    @Test
    void aFallingCardinalArmShadowsAsWellAsARisingOne() {
        FakeFenceView fenceView = new FakeFenceView().withFence(ORIGIN)
                .withFence(down(EightWayDirection.NORTH))
                .withFence(up(EightWayDirection.NORTH_EAST));

        int pitchMask = ElevatedConnections.computePitchMask(fenceView, ORIGIN);
        assertEquals(ElevatedConnections.PITCH_DOWN,
                ElevatedConnections.pitchFor(pitchMask, EightWayDirection.NORTH));
        assertEquals(ElevatedConnections.PITCH_NONE,
                ElevatedConnections.pitchFor(pitchMask, EightWayDirection.NORTH_EAST),
                "a falling north arm holds the north side just as a rising one does");
    }

    /**
     * The property that catches a one-sided shadow: a suppressed arm must be suppressed at
     * <em>both</em> ends. Here only the near end carries the shadowing cardinal arm, so an
     * implementation that evaluated the rule from the asking block alone would draw half a rail at
     * the far end.
     */
    @ParameterizedTest
    @EnumSource(value = EightWayDirection.class, names = {"NORTH_EAST", "NORTH_WEST", "SOUTH_EAST", "SOUTH_WEST"})
    void shadowingIsSymmetricAtBothEnds(EightWayDirection direction) {
        BlockPos upper = up(direction);
        EightWayDirection cardinal = direction.getCardinalNeighbors()[0];
        FakeFenceView fenceView = new FakeFenceView().withFence(ORIGIN).withFence(upper).withFence(up(cardinal));

        assertEquals(ElevatedConnections.PITCH_NONE,
                ElevatedConnections.pitchFor(ElevatedConnections.computePitchMask(fenceView, ORIGIN), direction),
                "the near end must drop " + direction + "; " + cardinal + " holds that side");
        assertEquals(ElevatedConnections.PITCH_NONE,
                ElevatedConnections.pitchFor(ElevatedConnections.computePitchMask(fenceView, upper),
                        direction.getOpposite()),
                "arm suppressed at the lower end but still drawn at the upper end");
    }

    /**
     * <strong>The reported artefact, 2026-08-27.</strong> Two parallel runs one block apart and one
     * block up used to slope into each other diagonally as well as straight, drawing an X between
     * them. Every low fence wants a straight arm and a diagonal one; the straight arm is shorter and
     * takes the side, so the crossing never forms.
     * <p>
     * This is the test that fails if Phase 11 is reverted.
     */
    @Test
    void twoParallelRunsOneUpDoNotCross() {
        BlockPos lowerWest = ORIGIN;
        BlockPos lowerEast = ORIGIN.offset(1, 0, 0);
        BlockPos upperWest = ORIGIN.offset(0, 1, -1);
        BlockPos upperEast = ORIGIN.offset(1, 1, -1);
        FakeFenceView fenceView = new FakeFenceView().withFence(lowerWest)
                .withFence(lowerEast)
                .withFence(upperWest)
                .withFence(upperEast);

        int lowerWestMask = ElevatedConnections.computePitchMask(fenceView, lowerWest);
        assertEquals(ElevatedConnections.PITCH_UP,
                ElevatedConnections.pitchFor(lowerWestMask, EightWayDirection.NORTH),
                "the straight rail up to the fence directly opposite must stay");
        assertEquals(ElevatedConnections.PITCH_NONE,
                ElevatedConnections.pitchFor(lowerWestMask, EightWayDirection.NORTH_EAST),
                "the crossing arm to the far upper fence is the X and must be gone");

        int lowerEastMask = ElevatedConnections.computePitchMask(fenceView, lowerEast);
        assertEquals(ElevatedConnections.PITCH_UP,
                ElevatedConnections.pitchFor(lowerEastMask, EightWayDirection.NORTH));
        assertEquals(ElevatedConnections.PITCH_NONE,
                ElevatedConnections.pitchFor(lowerEastMask, EightWayDirection.NORTH_WEST),
                "the other half of the X");

        // and the same read from the upper run, which is what a one-ended shadow would betray
        assertEquals(ElevatedConnections.PITCH_NONE,
                ElevatedConnections.pitchFor(ElevatedConnections.computePitchMask(fenceView, upperWest),
                        EightWayDirection.SOUTH_EAST));
        assertEquals(ElevatedConnections.PITCH_NONE,
                ElevatedConnections.pitchFor(ElevatedConnections.computePitchMask(fenceView, upperEast),
                        EightWayDirection.SOUTH_WEST));
    }

    // --- position reuse -------------------------------------------------------------------------

    /**
     * The neighbour position is a single reused {@link BlockPos.MutableBlockPos}. If a probe ever
     * left it holding the previous direction's coordinates, the mask would come out wrong; asking
     * for four directions at alternating heights is what would catch that.
     * <p>
     * Cardinals and intercardinals are probed in <em>separate</em> fixtures on purpose. A single
     * eight-way star cannot test this any more: Phase 11 legitimately suppresses every diagonal in
     * it, so a stale position would no longer change the expected mask.
     */
    @Test
    void reusingOneMutablePositionDoesNotLeakBetweenCardinals() {
        assertNoPositionLeak(EightWayDirection.getCardinalDirections());
    }

    /** @see #reusingOneMutablePositionDoesNotLeakBetweenCardinals() */
    @Test
    void reusingOneMutablePositionDoesNotLeakBetweenIntercardinals() {
        assertNoPositionLeak(EightWayDirection.getIntercardinalDirections());
    }

    private static void assertNoPositionLeak(EightWayDirection[] directions) {
        FakeFenceView fenceView = new FakeFenceView().withFence(ORIGIN);
        for (int i = 0; i < directions.length; i++) {
            // alternating up and down, so a stale position cannot accidentally land somewhere that
            // happens to be right
            fenceView.withFence(i % 2 == 0 ? up(directions[i]) : down(directions[i]));
        }
        int pitchMask = ElevatedConnections.computePitchMask(fenceView, ORIGIN);
        for (int i = 0; i < directions.length; i++) {
            assertEquals(i % 2 == 0 ? ElevatedConnections.PITCH_UP : ElevatedConnections.PITCH_DOWN,
                    ElevatedConnections.pitchFor(pitchMask, directions[i]),
                    "direction " + directions[i] + " resolved against a stale position");
        }
    }

    // --- a sloped side hides its own flat arm (Phase 12) ----------------------------------------

    /** Every flat bit set, and a lone fence with no rail beside it in any direction. */
    private static final int ALL_FLAT_ARMS = (1 << ElevatedConnections.FLAT_INDEX_WIDTH) - 1;

    private static FakeFenceView loneFence() {
        return new FakeFenceView().withFence(ORIGIN);
    }

    /**
     * The bit a direction owns in upstream's flat index is
     * {@link EightWayDirection#getHorizontalIndex()}, and {@code suppressFlatArms} has to clear that
     * one and nothing else. Asserted against a full index rather than a single bit, so a shift that
     * is off by one shows up as a surviving neighbour rather than as a mask that happens to be zero.
     * <p>
     * No rail stands beside this fence in any direction, so every flat arm it carries runs into
     * terrain and is a candidate.
     */
    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void aSlopedSideClearsItsOwnFlatBitAndNoOther(EightWayDirection direction) {
        FenceView fenceView = loneFence();
        for (int pitch : new int[]{ElevatedConnections.PITCH_UP, ElevatedConnections.PITCH_DOWN}) {
            int pitchMask = ElevatedConnections.withPitch(0, direction, pitch);
            assertEquals(ALL_FLAT_ARMS & ~direction.getHorizontalIndex(),
                    ElevatedConnections.suppressFlatArms(fenceView, ORIGIN, ALL_FLAT_ARMS, pitchMask),
                    direction + " at pitch " + pitch + " cleared the wrong flat arm");
        }
    }

    /** A falling arm hides a flat rail exactly as a rising one does; the side is what matters. */
    @Test
    void everySlopedSideIsSuppressedAtOnce() {
        int pitchMask = 0;
        for (EightWayDirection direction : EightWayDirection.values()) {
            pitchMask = ElevatedConnections.withPitch(pitchMask,
                    direction,
                    direction.isIntercardinal() ? ElevatedConnections.PITCH_DOWN : ElevatedConnections.PITCH_UP);
        }
        assertEquals(0, ElevatedConnections.suppressFlatArms(loneFence(), ORIGIN, ALL_FLAT_ARMS, pitchMask));
    }

    @Test
    void aFlatArmOnASideThatDoesNotSlopeSurvives() {
        int northAndEast = EightWayDirection.NORTH.getHorizontalIndex() | EightWayDirection.EAST.getHorizontalIndex();
        int northUp = ElevatedConnections.withPitch(0, EightWayDirection.NORTH, ElevatedConnections.PITCH_UP);
        assertEquals(EightWayDirection.EAST.getHorizontalIndex(),
                ElevatedConnections.suppressFlatArms(loneFence(), ORIGIN, northAndEast, northUp));
    }

    /**
     * <strong>The owner's narrowing, at unit level.</strong> A flat rail that reaches another rail is
     * one somebody built and can see, and it survives the slope leaving through the same side. Only
     * the arm a fence grows into a sturdy face -- invisible, buried in a full cube -- gives way.
     * <p>
     * The fixture differs from {@link #aSlopedSideClearsItsOwnFlatBitAndNoOther} by one fence, placed
     * beside the origin rather than above and along, and that one fence flips the answer.
     */
    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void aFlatRailThatReachesAnotherRailSurvivesTheSlopeOnItsOwnSide(EightWayDirection direction) {
        FenceView fenceView = loneFence().withFence(ORIGIN.offset(direction.getX(), 0, direction.getZ()));
        int pitchMask = ElevatedConnections.withPitch(0, direction, ElevatedConnections.PITCH_UP);
        assertEquals(ALL_FLAT_ARMS,
                ElevatedConnections.suppressFlatArms(fenceView, ORIGIN, ALL_FLAT_ARMS, pitchMask),
                direction + " dropped a flat rail that reaches a real rail");
    }

    /**
     * A fence with no slope anywhere must come back byte-identical, because that is the state
     * upstream's own shapes are indexed by and the one every fence in the game outside a staircase
     * is in.
     */
    @Test
    void anEmptyPitchMaskLeavesTheFlatIndexAlone() {
        FenceView fenceView = loneFence();
        for (int flatIndex = 0; flatIndex < (1 << ElevatedConnections.FLAT_INDEX_WIDTH); flatIndex++) {
            assertEquals(flatIndex,
                    ElevatedConnections.suppressFlatArms(fenceView,
                            ORIGIN,
                            flatIndex,
                            ElevatedConnections.EMPTY_PITCH_MASK));
        }
    }

    /**
     * The render path's entry point and the shape path's must not be able to disagree: one rule,
     * reached two ways. The fixture gives the origin a rising arm on one side, a falling one on the
     * opposite side, and a rail beside it on a third, so a suppressed side, an unsuppressed sloped
     * side and a flat side are all present at once.
     */
    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void theRenderPathAndTheShapePathSuppressTheSameSides(EightWayDirection direction) {
        FakeFenceView fenceView = loneFence().withFence(up(direction))
                .withFence(down(direction.getOpposite()))
                .withFence(ORIGIN.offset(direction.getX(), 0, direction.getZ()));
        int pitchMask = ElevatedConnections.computePitchMask(fenceView, ORIGIN);
        int suppressedFlatIndex = ElevatedConnections.suppressFlatArms(fenceView, ORIGIN, ALL_FLAT_ARMS, pitchMask);
        for (EightWayDirection probed : EightWayDirection.values()) {
            boolean clearedByTheMask = (suppressedFlatIndex & probed.getHorizontalIndex()) == 0;
            assertEquals(clearedByTheMask,
                    ElevatedConnections.suppressesFlatArm(fenceView, ORIGIN, probed),
                    "the two paths disagree about " + probed);
        }
    }

    /**
     * The single-direction question the render path asks must give the same answer as the mask the
     * collision path builds. They are one implementation on purpose; this is what fails if somebody
     * writes a second one.
     * <p>
     * The fixture puts a rising arm on one side of {@code ORIGIN} and a falling arm on the opposite
     * side, so both pitches and a genuine {@link ElevatedConnections#PITCH_NONE} are all covered for
     * every direction.
     */
    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void askingAboutOneDirectionAgreesWithTheWholeMask(EightWayDirection direction) {
        FakeFenceView fenceView = new FakeFenceView().withFence(ORIGIN)
                .withFence(up(direction))
                .withFence(down(direction.getOpposite()));
        int pitchMask = ElevatedConnections.computePitchMask(fenceView, ORIGIN);
        for (EightWayDirection probed : EightWayDirection.values()) {
            assertEquals(ElevatedConnections.pitchFor(pitchMask, probed),
                    ElevatedConnections.pitchFor(fenceView, ORIGIN, probed),
                    "the mask and the single-direction probe disagree about " + probed);
        }
    }

    /**
     * A buried fence has no arms at all, and the single-direction probe has to reach that on the
     * per-arm rule alone -- {@code computePitchMask}'s early-out on a covered block is an
     * optimisation this path deliberately does not repeat.
     */
    @Test
    void askingAboutOneDirectionRefusesABuriedFenceToo() {
        FakeFenceView fenceView = new FakeFenceView().withFence(ORIGIN)
                .withFence(ORIGIN.above())
                .withFence(up(EightWayDirection.NORTH));
        assertEquals(ElevatedConnections.PITCH_NONE,
                ElevatedConnections.pitchFor(fenceView, ORIGIN, EightWayDirection.NORTH));
    }

    // --- argument guard -----------------------------------------------------------------------

    @Test
    void connectsElevatedRejectsPositionsThatAreNotAnArm() {
        FenceView fenceView = new FakeFenceView().withFence(ORIGIN);
        // same height
        assertThrows(IllegalArgumentException.class,
                () -> ElevatedConnections.connectsElevated(fenceView,
                        ORIGIN,
                        ORIGIN.offset(0, 0, -1),
                        EightWayDirection.NORTH));
        // two blocks up
        assertThrows(IllegalArgumentException.class,
                () -> ElevatedConnections.connectsElevated(fenceView,
                        ORIGIN,
                        ORIGIN.offset(0, 2, -1),
                        EightWayDirection.NORTH));
        // horizontal offset disagrees with the direction
        assertThrows(IllegalArgumentException.class,
                () -> ElevatedConnections.connectsElevated(fenceView,
                        ORIGIN,
                        ORIGIN.offset(0, 1, 1),
                        EightWayDirection.NORTH));
        // arguments swapped, so the "lower" position is actually the upper one
        assertThrows(IllegalArgumentException.class,
                () -> ElevatedConnections.connectsElevated(fenceView,
                        ORIGIN.offset(0, 1, -1),
                        ORIGIN,
                        EightWayDirection.NORTH));
    }
}
