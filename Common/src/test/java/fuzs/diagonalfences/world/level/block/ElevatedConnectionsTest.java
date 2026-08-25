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
