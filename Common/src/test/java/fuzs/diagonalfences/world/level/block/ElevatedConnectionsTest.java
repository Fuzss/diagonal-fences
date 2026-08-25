package fuzs.diagonalfences.world.level.block;

import fuzs.diagonalblocks.api.v2.util.EightWayDirection;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * The same property under flat precedence, which is where the two ends are most likely to
     * disagree: the suppressing flat arm belongs to only one of them.
     */
    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void suppressionIsSymmetricAtBothEnds(EightWayDirection direction) {
        BlockPos upper = up(direction);
        FakeFenceView fenceView = new FakeFenceView().withFence(ORIGIN)
                .withFence(upper)
                .withFlatArm(ORIGIN, direction);

        assertEquals(ElevatedConnections.PITCH_NONE,
                ElevatedConnections.pitchFor(ElevatedConnections.computePitchMask(fenceView, ORIGIN), direction));
        assertEquals(ElevatedConnections.PITCH_NONE,
                ElevatedConnections.pitchFor(ElevatedConnections.computePitchMask(fenceView, upper),
                        direction.getOpposite()),
                "the upper end kept an arm the lower end suppressed");
    }

    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void aFlatArmAtTheUpperEndAlsoSuppressesBothEnds(EightWayDirection direction) {
        BlockPos upper = up(direction);
        FakeFenceView fenceView = new FakeFenceView().withFence(ORIGIN)
                .withFence(upper)
                .withFlatArm(upper, direction.getOpposite());

        assertEquals(ElevatedConnections.PITCH_NONE,
                ElevatedConnections.pitchFor(ElevatedConnections.computePitchMask(fenceView, ORIGIN), direction));
        assertEquals(ElevatedConnections.PITCH_NONE,
                ElevatedConnections.pitchFor(ElevatedConnections.computePitchMask(fenceView, upper),
                        direction.getOpposite()));
    }

    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void aFlatArmInAnotherDirectionDoesNotSuppress(EightWayDirection direction) {
        BlockPos upper = up(direction);
        FakeFenceView fenceView = new FakeFenceView().withFence(ORIGIN)
                .withFence(upper)
                .withFlatArm(ORIGIN, direction.rotateClockWise());

        assertEquals(ElevatedConnections.PITCH_UP,
                ElevatedConnections.pitchFor(ElevatedConnections.computePitchMask(fenceView, ORIGIN), direction));
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

    // --- flat index fast path -----------------------------------------------------------------

    /**
     * The whole fast path rests on upstream's flat index using one bit per direction at
     * {@code 1 << directionIndex}. Pinning it here means a change to {@code getHorizontalIndex}
     * upstream fails loudly instead of silently suppressing every sloped arm.
     */
    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void hasFlatArmMatchesUpstreamsHorizontalIndex(EightWayDirection direction) {
        int flatIndex = direction.getHorizontalIndex();
        assertTrue(ElevatedConnections.hasFlatArm(flatIndex, direction));
        for (EightWayDirection other : EightWayDirection.values()) {
            if (other != direction) {
                assertFalse(ElevatedConnections.hasFlatArm(flatIndex, other),
                        "direction " + other + " read a bit belonging to " + direction);
            }
        }
        assertFalse(ElevatedConnections.hasFlatArm(ElevatedConnections.NO_FLAT_ARMS, direction));
    }

    /**
     * A flat arm known from the index must kill the up arm <em>and</em> the down arm, because
     * {@code connectsElevated} suppresses on either end and self is the lower end of one candidate
     * and the upper end of the other. Skipping only one of the two would leave a one-sided arm.
     */
    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void aFlatArmInTheIndexSuppressesBothTheUpAndTheDownArm(EightWayDirection direction) {
        FakeFenceView fenceView = new FakeFenceView().withFence(ORIGIN)
                .withFence(up(direction))
                .withFence(down(direction));
        assertEquals(ElevatedConnections.PITCH_UP,
                ElevatedConnections.pitchFor(ElevatedConnections.computePitchMask(fenceView,
                        ORIGIN,
                        ElevatedConnections.NO_FLAT_ARMS), direction),
                "without the flat arm this direction should slope, or the test proves nothing");

        int flatIndex = direction.getHorizontalIndex();
        assertEquals(ElevatedConnections.EMPTY_PITCH_MASK,
                ElevatedConnections.computePitchMask(fenceView, ORIGIN, flatIndex),
                "a flat arm toward " + direction + " must suppress both candidates there");

        // and only there -- the other seven directions still resolve normally
        FakeFenceView allNeighbors = new FakeFenceView().withFence(ORIGIN);
        for (EightWayDirection other : EightWayDirection.values()) {
            allNeighbors.withFence(up(other));
        }
        int pitchMask = ElevatedConnections.computePitchMask(allNeighbors, ORIGIN, flatIndex);
        for (EightWayDirection other : EightWayDirection.values()) {
            assertEquals(other == direction ? ElevatedConnections.PITCH_NONE : ElevatedConnections.PITCH_UP,
                    ElevatedConnections.pitchFor(pitchMask, other),
                    "direction " + other + " was affected by a flat arm toward " + direction);
        }
    }

    /**
     * The fast path is an optimisation, so it must never change an answer. Exhaustive over all 256
     * indices, against a view whose flat arms agree with the index -- which is the only case
     * production ever passes, since the index is derived from the very block state being queried.
     */
    @Test
    void theFlatIndexOverloadAgreesWithTheViewDrivenOneForEveryIndex() {
        for (int flatIndex = 0; flatIndex < 1 << ElevatedConnections.FLAT_INDEX_WIDTH; flatIndex++) {
            FakeFenceView fenceView = new FakeFenceView().withFence(ORIGIN);
            for (EightWayDirection direction : EightWayDirection.values()) {
                fenceView.withFence(up(direction)).withFence(down(direction));
                if (ElevatedConnections.hasFlatArm(flatIndex, direction)) {
                    fenceView.withFlatArm(ORIGIN, direction);
                }
            }
            assertEquals(ElevatedConnections.computePitchMask(fenceView, ORIGIN),
                    ElevatedConnections.computePitchMask(fenceView, ORIGIN, flatIndex),
                    "the two overloads diverged at flat index " + flatIndex);
        }
    }

    @Test
    void computePitchMaskRejectsAFlatIndexOutsideEightBits() {
        FenceView fenceView = new FakeFenceView().withFence(ORIGIN);
        assertThrows(IllegalArgumentException.class,
                () -> ElevatedConnections.computePitchMask(fenceView, ORIGIN, -1));
        assertThrows(IllegalArgumentException.class,
                () -> ElevatedConnections.computePitchMask(fenceView,
                        ORIGIN,
                        1 << ElevatedConnections.FLAT_INDEX_WIDTH));
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
