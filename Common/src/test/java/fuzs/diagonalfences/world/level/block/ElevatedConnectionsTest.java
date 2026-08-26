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

    // --- a flat arm suppresses nothing (Phase 9) ------------------------------------------------

    /**
     * <strong>Phase 9.</strong> A flat arm on a face used to veto a pitch on that face. It no longer
     * does, so at this seam the rule is simply attachability and there is nothing left to suppress
     * with -- {@link FakeFenceView} cannot even express a flat arm any more.
     * <p>
     * What is worth pinning here is the <em>shape</em> that change produces, because it is the part
     * an onlooker is most likely to mistake for a bug and "fix": two adjacent two-tall fence columns
     * cross. The lower fence rises to the far upper one while the upper fence falls to the far lower
     * one, and both arms are real. Removing precedence without meaning to allow this would be a
     * misunderstanding of the change, not a refinement of it.
     *
     * @see ElevatedConnections
     */
    @Test
    void twoAdjacentTwoTallColumnsCross() {
        BlockPos nearTop = ORIGIN.above();
        BlockPos farBottom = ORIGIN.offset(1, 0, 0);
        BlockPos farTop = ORIGIN.offset(1, 1, 0);
        FakeFenceView fenceView = new FakeFenceView().withFence(ORIGIN)
                .withFence(nearTop)
                .withFence(farBottom)
                .withFence(farTop);

        assertEquals(ElevatedConnections.PITCH_UP,
                ElevatedConnections.pitchFor(ElevatedConnections.computePitchMask(fenceView, ORIGIN),
                        EightWayDirection.EAST),
                "the near lower fence must rise to the far upper one");
        assertEquals(ElevatedConnections.PITCH_DOWN,
                ElevatedConnections.pitchFor(ElevatedConnections.computePitchMask(fenceView, nearTop),
                        EightWayDirection.EAST),
                "the near upper fence must fall to the far lower one -- this is the other half of the X");
        // And the same X read from the far column, which is the symmetry property applied to it.
        assertEquals(ElevatedConnections.PITCH_UP,
                ElevatedConnections.pitchFor(ElevatedConnections.computePitchMask(fenceView, farBottom),
                        EightWayDirection.WEST));
        assertEquals(ElevatedConnections.PITCH_DOWN,
                ElevatedConnections.pitchFor(ElevatedConnections.computePitchMask(fenceView, farTop),
                        EightWayDirection.WEST));
    }

    /**
     * The reported build, at this seam: a fence whose face already carries a flat rail must still
     * reach the fence one up and one along. The fake records only attachability, so this is the
     * upper half of {@link #twoAdjacentTwoTallColumnsCross} isolated -- the assertion that the flat
     * neighbour's presence changes nothing lives in {@code ElevatedFenceInLevelTest}, where a real
     * side property exists to be ignored.
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

    /**
     * The neighbour position is a single reused {@link BlockPos.MutableBlockPos}. If a probe ever
     * left it holding the previous direction's coordinates, the mask would come out wrong; asking
     * for all eight directions at once is what would catch that.
     */
    @Test
    void reusingOneMutablePositionDoesNotLeakBetweenDirections() {
        FakeFenceView fenceView = new FakeFenceView().withFence(ORIGIN);
        for (EightWayDirection direction : EightWayDirection.values()) {
            // up for cardinals, down for intercardinals, so a stale position cannot accidentally
            // land on a position that happens to be right
            fenceView.withFence(direction.isIntercardinal() ? down(direction) : up(direction));
        }
        int pitchMask = ElevatedConnections.computePitchMask(fenceView, ORIGIN);
        for (EightWayDirection direction : EightWayDirection.values()) {
            assertEquals(direction.isIntercardinal() ? ElevatedConnections.PITCH_DOWN : ElevatedConnections.PITCH_UP,
                    ElevatedConnections.pitchFor(pitchMask, direction),
                    "direction " + direction + " resolved against a stale position");
        }
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
