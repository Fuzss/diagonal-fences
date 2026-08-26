package fuzs.diagonalfences.world.level.block;

import fuzs.diagonalblocks.api.v2.block.type.DiagonalBlockType;
import fuzs.diagonalblocks.api.v2.block.type.DiagonalBlockTypes;
import fuzs.diagonalblocks.api.v2.util.EightWayDirection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <strong>This is the test that fails if the feature is reverted.</strong>
 * <p>
 * Every other test in this package stops at a seam: {@link ElevatedConnectionsTest} drives
 * {@link FakeFenceView}, {@link ElevatedShapesTest} builds arms with no block behind them, and
 * {@link ElevatedDiagonalFenceBlockTest} queries {@link net.minecraft.world.level.EmptyBlockGetter},
 * which by design can only ever produce a flat shape. Every one of those still passes with
 * {@link LevelFenceView} deleted outright. This class runs the chain the game runs -- a populated
 * level, {@code BlockState#getCollisionShape(BlockGetter, BlockPos, CollisionContext)}, the adapter,
 * the pitch mask, the shape cache -- and is the only coverage {@code LevelFenceView} has.
 * <p>
 * Assertions are on shape <em>identity</em> rather than geometry. {@link ElevatedShapeCache} memoises
 * per key, so the shape the block hands back is the very object the expected key produces if and only
 * if the whole chain resolved to that key. {@link VoxelShape} has no {@code equals} anyway, and the
 * geometry itself is {@link ElevatedShapesTest}'s job, not this one's.
 * <p>
 * <strong>What this cannot cover</strong>, because there is no game here: chunk sections and
 * therefore the section-boundary case, rendering, and Sodium. Those stay on Phase 5's manual list and
 * this test is not a substitute for them.
 */
class ElevatedFenceInLevelTest {
    /** Comfortably inside {@link FakeLevel}'s bounds, and not on a section boundary. */
    private static final BlockPos ORIGIN = new BlockPos(8, 72, 8);
    /** Lifts a probe off a box edge, matched to how {@link ElevatedShapesTest} probes steps. */
    private static final double PROBE_INSET = 0.5;

    private static ElevatedDiagonalFenceBlock fenceBlock;
    private static ElevatedDiagonalFenceBlock foreignFenceBlock;

    @BeforeAll
    static void setUp() {
        // MUST come first, and not because createBlock would otherwise do it. Reading
        // ElevatedFenceBlockType.INSTANCE runs that class's static initializer, which builds a
        // DiagonalBlockTypeImpl and so touches BuiltInRegistries -- and an argument is evaluated
        // before the method it is passed to. Bootstrapping inside createBlock is therefore too late:
        // the registry throws "Not bootstrapped", and because that happens inside a static
        // initializer the class stays permanently unusable and every later test in the same JVM dies
        // with NoClassDefFoundError. Whether it bites depends on whether some other test class
        // bootstrapped this JVM first, which is exactly the kind of order-dependent flake that passes
        // locally and fails in CI.
        BlockTestSupport.bootstrap();
        fenceBlock = createFence(ElevatedFenceBlockType.INSTANCE);
        // A real DiagonalBlock carrying a different type identity. attachesDiagonallyTo compares types
        // by identity, so this is the neighbour that has to be refused for a reason other than "that
        // is not a fence".
        foreignFenceBlock = createFence(DiagonalBlockTypes.FENCE);
    }

    private static ElevatedDiagonalFenceBlock createFence(DiagonalBlockType diagonalBlockType) {
        ElevatedDiagonalFenceBlock fenceBlock = BlockTestSupport.createBlock((properties) -> new ElevatedDiagonalFenceBlock(
                properties,
                diagonalBlockType));
        // Without this every attachesDiagonallyTo returns false and this whole class passes vacuously.
        BlockTestSupport.bindTags(fenceBlock, BlockTags.FENCES, BlockTags.WOODEN_FENCES);
        return fenceBlock;
    }

    // --- the headline case: a railing following a flight of stairs ------------------------------

    /**
     * Three fences in a rising cardinal run, queried on the middle one. It must grow an arm rising
     * toward the fence above and another falling toward the fence below -- the arms for those two
     * specific directions, not merely "something other than flat".
     */
    @Test
    void slopesUpAndDownAlongsideAStairRun() {
        BlockPos middle = ORIGIN;
        FakeLevel level = new FakeLevel().withBlock(middle.offset(-1, -1, 0), fence())
                .withBlock(middle, fence())
                .withBlock(middle.offset(1, 1, 0), fence());

        int risingEastFallingWest = ElevatedConnections.withPitch(pitchMask(EightWayDirection.EAST,
                ElevatedConnections.PITCH_UP), EightWayDirection.WEST, ElevatedConnections.PITCH_DOWN);
        assertSame(expectedShape(fenceBlock.collisionShapes(), risingEastFallingWest),
                collisionShapeAt(level, middle),
                "the middle fence of a rising run must rise east and fall west");
    }

    /**
     * The half that makes the case above mean something. A different shape is only evidence of a slope
     * if the flat shape does not already reach where the arm is, so this probes the point where the
     * east arm has climbed a full {@link ElevatedShapes#HALF_RISE} above the rail -- open air on a
     * flat fence.
     */
    @Test
    void theSlopedShapeOccupiesSpaceTheFlatShapeLeavesFree() {
        FakeLevel level = new FakeLevel().withBlock(ORIGIN, fence()).withBlock(ORIGIN.offset(1, 1, 0), fence());

        VoxelShape slopedShape = collisionShapeAt(level, ORIGIN);
        VoxelShape flatShape = expectedShape(fenceBlock.collisionShapes(), ElevatedConnections.EMPTY_PITCH_MASK);
        double x = ElevatedShapes.stepCoordinate(EightWayDirection.EAST.getX(), 0);
        double z = ElevatedShapes.stepCoordinate(EightWayDirection.EAST.getZ(), 0);
        double y = ElevatedDiagonalFenceBlock.EXTENSION_BOTTOM + ElevatedShapes.HALF_RISE + PROBE_INSET;

        assertNotSame(flatShape, slopedShape);
        ShapeProbe.assertTouches(slopedShape, x, y, z, "the east arm where it meets the block edge");
        ShapeProbe.assertDoesNotTouch(flatShape, x, y, z, "a flat fence at the same point");
    }

    /** The control. Without it, code that returned a sloped shape unconditionally would pass. */
    @Test
    void anIsolatedFenceStaysFlat() {
        FakeLevel level = new FakeLevel().withBlock(ORIGIN, fence());
        assertSame(expectedShape(fenceBlock.collisionShapes(), ElevatedConnections.EMPTY_PITCH_MASK),
                collisionShapeAt(level, ORIGIN),
                "a fence with no neighbour must get upstream's flat instance back, untouched");
    }

    // --- all eight directions, both pitches -----------------------------------------------------

    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void slopesUpTowardAFenceOneBlockUp(EightWayDirection direction) {
        FakeLevel level = new FakeLevel().withBlock(ORIGIN, fence()).withBlock(neighbor(direction, 1), fence());
        assertSame(expectedShape(fenceBlock.collisionShapes(), pitchMask(direction, ElevatedConnections.PITCH_UP)),
                collisionShapeAt(level, ORIGIN),
                "a fence " + direction + " and one block up must produce a rising arm");
    }

    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void slopesDownTowardAFenceOneBlockDown(EightWayDirection direction) {
        FakeLevel level = new FakeLevel().withBlock(ORIGIN, fence()).withBlock(neighbor(direction, -1), fence());
        assertSame(expectedShape(fenceBlock.collisionShapes(), pitchMask(direction, ElevatedConnections.PITCH_DOWN)),
                collisionShapeAt(level, ORIGIN),
                "a fence " + direction + " and one block down must produce a falling arm");
    }

    /**
     * The symmetry contract, evaluated against a real level rather than against {@link FakeFenceView},
     * whose {@code attachable} is a set membership test and so is symmetric by construction and cannot
     * fail this. A one-sided result here means {@link LevelFenceView} evaluates only one end.
     */
    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void bothEndsOfAnArmGrowIt(EightWayDirection direction) {
        BlockPos upper = neighbor(direction, 1);
        FakeLevel level = new FakeLevel().withBlock(ORIGIN, fence()).withBlock(upper, fence());

        assertSame(expectedShape(fenceBlock.collisionShapes(), pitchMask(direction, ElevatedConnections.PITCH_UP)),
                collisionShapeAt(level, ORIGIN),
                "the lower end must rise toward " + direction);
        assertSame(expectedShape(fenceBlock.collisionShapes(),
                        pitchMask(direction.getOpposite(), ElevatedConnections.PITCH_DOWN)),
                collisionShapeAt(level, upper),
                "the upper end must fall back toward " + direction.getOpposite());
    }

    // --- the Phase 6 regression: a railing on a staircase made of solid blocks ------------------

    /**
     * <strong>This is the test the bug walked through.</strong> Phases 1-5 shipped with no sloped arm
     * ever forming in a real world, and every test above passed the whole time, because every fence
     * in them floats in an empty {@link FakeLevel} with all four side properties false.
     * <p>
     * A staircase is made of solid blocks. The upper fence stands on a riser, and that riser is a
     * sturdy full block directly east of the lower fence -- so
     * {@code FenceBlock#connectsTo(state, isSideSolid, direction)} sets {@code east} on the lower
     * fence against the <em>dirt</em>, not against any rail:
     * <pre>
     * y+1:        [F2]
     * y  :  [F1]  [riser]      F1.east == true, because the riser is sturdy
     * </pre>
     * Reading that property as "this face already carries an arm" suppressed the pitch on every
     * staircase anyone could build. The arm buried in the riser is invisible and its collision box is
     * inside a full cube; it occupies nothing the sloped arm wants.
     * <p>
     * Nothing here is contrived: {@code east=true} is exactly the state the game computes for a fence
     * placed against a block, and it is set explicitly only because {@link FakeLevel} does not run
     * {@code getStateForPlacement}.
     */
    @Test
    void slopesUpAlongsideAStairRunBuiltFromSolidBlocks() {
        BlockState lowerAgainstRiser = fence().setValue(CrossCollisionBlock.EAST, Boolean.TRUE);
        FakeLevel level = new FakeLevel().withBlock(ORIGIN, lowerAgainstRiser)
                .withBlock(ORIGIN.offset(1, 0, 0), Blocks.DIRT.defaultBlockState())
                .withBlock(ORIGIN.offset(1, 1, 0), fence());

        assertSame(expectedShape(fenceBlock.collisionShapes(),
                        lowerAgainstRiser,
                        pitchMask(EightWayDirection.EAST, ElevatedConnections.PITCH_UP)),
                lowerAgainstRiser.getCollisionShape(level, ORIGIN, CollisionContext.empty()),
                "a fence abutting the riser it climbs must still slope up to the fence above it");
    }

    /**
     * The upper end of the same staircase, which resolves the identical {@code (lower, upper, EAST)}
     * triple through {@code connectsElevated} and so died on the identical check. Asserting only the
     * lower end would leave half the bug in place and produce a one-sided arm.
     */
    @Test
    void theUpperFenceOfAStairRunFallsBackDownTheSlope() {
        BlockPos upper = ORIGIN.offset(1, 1, 0);
        BlockState lowerAgainstRiser = fence().setValue(CrossCollisionBlock.EAST, Boolean.TRUE);
        FakeLevel level = new FakeLevel().withBlock(ORIGIN, lowerAgainstRiser)
                .withBlock(ORIGIN.offset(1, 0, 0), Blocks.DIRT.defaultBlockState())
                .withBlock(upper, fence());

        assertSame(expectedShape(fenceBlock.collisionShapes(),
                        pitchMask(EightWayDirection.WEST, ElevatedConnections.PITCH_DOWN)),
                collisionShapeAt(level, upper),
                "the fence on the step above must fall back west to meet the arm rising to it");
    }

    /**
     * A diagonal railing climbing the same staircase: the lower fence is boxed in by terrain on both
     * of the cardinal faces that flank its arm, and must slope over the corner regardless.
     * <p>
     * <strong>This is a scenario control, not a regression guard</strong> -- unlike the two tests
     * above it also passes against the pre-fix code, because that read only the {@code south_east}
     * property and nothing here sets it. It is kept because it is the build the feature is for and
     * nothing else covers it, and it is labelled so nobody mistakes it for the test holding the fix
     * in place.
     * <p>
     * Phase 9 removed flat precedence entirely, so the cardinal arms into terrain here no longer
     * have anything to suppress with. The scenario is kept because it is the build the feature is
     * for; it just guards less than it used to.
     */
    @Test
    void slopesDiagonallyAlongsideAStairRunBuiltFromSolidBlocks() {
        BlockState lowerBoxedIn = fence().setValue(CrossCollisionBlock.EAST, Boolean.TRUE)
                .setValue(CrossCollisionBlock.SOUTH, Boolean.TRUE);
        FakeLevel level = new FakeLevel().withBlock(ORIGIN, lowerBoxedIn)
                .withBlock(ORIGIN.offset(1, 0, 0), Blocks.DIRT.defaultBlockState())
                .withBlock(ORIGIN.offset(0, 0, 1), Blocks.DIRT.defaultBlockState())
                .withBlock(ORIGIN.offset(1, 0, 1), Blocks.DIRT.defaultBlockState())
                .withBlock(ORIGIN.offset(1, 1, 1), fence());

        assertSame(expectedShape(fenceBlock.collisionShapes(),
                        lowerBoxedIn,
                        pitchMask(EightWayDirection.SOUTH_EAST, ElevatedConnections.PITCH_UP)),
                lowerBoxedIn.getCollisionShape(level, ORIGIN, CollisionContext.empty()),
                "cardinal arms into terrain must not suppress the diagonal slope over it");
    }

    /**
     * <strong>Phase 9, and the sharpest edge of it.</strong> A fence gate no longer suppresses the
     * slope either, because nothing does -- so a run stepping up beside a gate grows an arm across
     * the gate's own space.
     * <p>
     * This is a real visual artefact rather than a harmless overlap, and it is the one case where
     * dropping precedence costs something: the equivalent arm through a <em>solid</em> riser is
     * buried inside a full cube and invisible, while a gate is mostly open air and the arm will be
     * seen crossing it. Recorded deliberately in {@code decisions.md} as accepted-and-watched. If
     * playtesting rejects it, the narrow fix is to restore suppression for gates alone -- not to
     * reinstate flat precedence wholesale.
     */
    @Test
    void aConnectingFenceGateNoLongerSuppressesTheSlope() {
        BlockState lowerAgainstGate = fence().setValue(CrossCollisionBlock.EAST, Boolean.TRUE);
        BlockState gate = Blocks.OAK_FENCE_GATE.defaultBlockState()
                .setValue(FenceGateBlock.FACING, Direction.NORTH);
        FakeLevel level = new FakeLevel().withBlock(ORIGIN, lowerAgainstGate)
                .withBlock(ORIGIN.offset(1, 0, 0), gate)
                .withBlock(ORIGIN.offset(1, 1, 0), fence());

        assertSame(expectedShape(fenceBlock.collisionShapes(),
                        lowerAgainstGate,
                        pitchMask(EightWayDirection.EAST, ElevatedConnections.PITCH_UP)),
                lowerAgainstGate.getCollisionShape(level, ORIGIN, CollisionContext.empty()),
                "the gate occupies the flat face, but the flat face no longer vetoes the slope");
    }

    // --- a flat arm suppresses nothing (Phase 9) -------------------------------------------------

    /**
     * <strong>The reported build, and the test that fails if Phase 9 is reverted.</strong> This is
     * byte-for-byte the fixture that used to assert {@code EMPTY_PITCH_MASK}: a fence carrying a
     * real flat east arm to a real fence beside it, with a third fence up and east. The face is
     * occupied and the slope forms anyway.
     * <p>
     * Contrast {@link #slopesUpAlongsideAStairRunBuiltFromSolidBlocks}, the same block state with
     * terrain where this one has a fence. Under Phase 6 those two differed; the whole point of
     * Phase 9 is that they no longer do, which is what "any fence one up and one along should
     * connect" means when written out.
     */
    @Test
    void aFlatArmOnTheSameFaceStillGrowsASlope() {
        BlockState withFlatEastArm = fence().setValue(CrossCollisionBlock.EAST, Boolean.TRUE);
        FakeLevel level = new FakeLevel().withBlock(ORIGIN, withFlatEastArm)
                .withBlock(ORIGIN.offset(1, 0, 0), fence())
                .withBlock(ORIGIN.offset(1, 1, 0), fence());

        assertSame(expectedShape(fenceBlock.collisionShapes(),
                        withFlatEastArm,
                        pitchMask(EightWayDirection.EAST, ElevatedConnections.PITCH_UP)),
                withFlatEastArm.getCollisionShape(level, ORIGIN, CollisionContext.empty()),
                "a face carrying a flat rail must still climb to the fence above that rail");
    }

    /**
     * The far end's flat arm does not block it either. In a real build this is a rising run arriving
     * at a landing that already runs flat, and it is the case that would break first if
     * {@code connectsElevated} were ever rewritten as "the lower block looks up, the upper block
     * looks down" -- the flat arm belongs to only one of the two ends, so an asymmetric rule shows
     * up here as a one-sided arm.
     */
    @Test
    void theNeighboursFlatArmDoesNotBlockTheSlopeEither() {
        BlockPos upper = ORIGIN.offset(1, 1, 0);
        BlockState upperRunningFlatWest = fence().setValue(CrossCollisionBlock.WEST, Boolean.TRUE);
        FakeLevel level = new FakeLevel().withBlock(ORIGIN, fence())
                .withBlock(upper, upperRunningFlatWest)
                .withBlock(upper.offset(-1, 0, 0), fence());

        assertSame(expectedShape(fenceBlock.collisionShapes(),
                        pitchMask(EightWayDirection.EAST, ElevatedConnections.PITCH_UP)),
                collisionShapeAt(level, ORIGIN),
                "the far end's occupied face must not veto the arm reaching it");
    }

    /**
     * Two adjacent two-tall fence columns, asserted through a real level: they cross.
     * <p>
     * <strong>Stated to the owner before it was written, and wanted.</strong> It is the most
     * conspicuous consequence of dropping precedence and the thing most likely to be mistaken for a
     * bug later, so it is pinned at both ends rather than left to be rediscovered.
     */
    @Test
    void twoAdjacentTwoTallColumnsCross() {
        BlockPos nearTop = ORIGIN.above();
        BlockState nearBottomState = fence().setValue(CrossCollisionBlock.EAST, Boolean.TRUE);
        FakeLevel level = new FakeLevel().withBlock(ORIGIN, nearBottomState)
                .withBlock(nearTop, fence().setValue(CrossCollisionBlock.EAST, Boolean.TRUE))
                .withBlock(ORIGIN.offset(1, 0, 0), fence().setValue(CrossCollisionBlock.WEST, Boolean.TRUE))
                .withBlock(ORIGIN.offset(1, 1, 0), fence().setValue(CrossCollisionBlock.WEST, Boolean.TRUE));

        assertSame(expectedShape(fenceBlock.collisionShapes(),
                        nearBottomState,
                        pitchMask(EightWayDirection.EAST, ElevatedConnections.PITCH_UP)),
                nearBottomState.getCollisionShape(level, ORIGIN, CollisionContext.empty()),
                "the lower fence of the near column rises to the top of the far one");
        assertSame(expectedShape(fenceBlock.collisionShapes(),
                        fence().setValue(CrossCollisionBlock.EAST, Boolean.TRUE),
                        pitchMask(EightWayDirection.EAST, ElevatedConnections.PITCH_DOWN)),
                collisionShapeAt(level, nearTop),
                "the upper fence of the near column falls to the bottom of the far one -- the other half of the X");
    }

    /**
     * {@link LevelFenceView} is built for one {@link DiagonalBlockType} and must answer only for that
     * type. Driven directly rather than through a shape query, because upstream's own
     * {@code attachesDiagonallyTo} independently refuses a mismatched pair from either side -- so
     * every whole-block route through here passes with the view's own type check deleted, and a
     * guard no test can kill is a guard that gets refactored away.
     */
    @Test
    void aViewAnswersOnlyForTheTypeItWasBuiltFor() {
        BlockPos upper = ORIGIN.offset(1, 1, 0);
        FakeLevel level = new FakeLevel().withBlock(ORIGIN, fence()).withBlock(upper, fence());

        assertTrue(new LevelFenceView(level, ElevatedFenceBlockType.INSTANCE).attachable(ORIGIN,
                        upper,
                        EightWayDirection.EAST),
                "the control: these two fences do attach, for their own type");
        assertFalse(new LevelFenceView(level, DiagonalBlockTypes.FENCE).attachable(ORIGIN,
                        upper,
                        EightWayDirection.EAST),
                "a view built for another type must not report an arm between blocks that are not its own");
    }

    /**
     * Sloping into stairs is explicitly out of scope, and that it does not happen is not an accident:
     * {@link LevelFenceView} routes cardinal directions through {@code attachesDiagonallyTo} rather
     * than {@code attachesDirectlyTo}, precisely so that a sturdy face is not enough.
     */
    @Test
    void doesNotSlopeTowardABlockThatIsNotAFence() {
        FakeLevel level = new FakeLevel().withBlock(ORIGIN, fence())
                .withBlock(ORIGIN.offset(1, 1, 0), Blocks.OAK_STAIRS.defaultBlockState())
                .withBlock(ORIGIN.offset(-1, -1, 0), Blocks.STONE.defaultBlockState());
        assertSame(expectedShape(fenceBlock.collisionShapes(), ElevatedConnections.EMPTY_PITCH_MASK),
                collisionShapeAt(level, ORIGIN),
                "stairs and full blocks are not fences and must not attract an arm");
    }

    /**
     * Two fences of different diagonal block types do not connect flat and must not connect sloped
     * either. This is the case that catches {@code ElevatedDiagonalFenceBlock#getType()} being
     * dropped, which would otherwise fail silently and in the opposite direction -- every connection
     * gone rather than one too many.
     */
    @Test
    void doesNotSlopeTowardAFenceOfAnotherType() {
        FakeLevel level = new FakeLevel().withBlock(ORIGIN, fence())
                .withBlock(ORIGIN.offset(1, 1, 0), foreignFenceBlock.defaultBlockState());
        assertSame(expectedShape(fenceBlock.collisionShapes(), ElevatedConnections.EMPTY_PITCH_MASK),
                collisionShapeAt(level, ORIGIN),
                "a fence belonging to another diagonal block type is not ours to connect to");
    }

    // --- the other two shape paths ----------------------------------------------------------------

    /**
     * The outline is a second cache built from the shorter of vanilla's two shape functions, so it can
     * regress on its own. {@code getVisualShape} needs no test of its own: it delegates here.
     */
    @Test
    void theOutlineFollowsTheSlopeToo() {
        FakeLevel level = new FakeLevel().withBlock(ORIGIN, fence()).withBlock(ORIGIN.offset(1, 1, 0), fence());
        assertSame(expectedShape(fenceBlock.outlineShapes(),
                        pitchMask(EightWayDirection.EAST, ElevatedConnections.PITCH_UP)),
                fence().getShape(level, ORIGIN, CollisionContext.empty()),
                "the block highlight must follow the arm rather than stay flat");
    }

    /**
     * Waterlogging is on Phase 5's manual list because it is cheap to get wrong; it is cheaper still
     * to assert. It sits outside the eight arm properties and so must not reach the shape at all.
     */
    @Test
    void waterloggedFencesStillSlope() {
        BlockState waterlogged = fence().setValue(CrossCollisionBlock.WATERLOGGED, Boolean.TRUE);
        FakeLevel level = new FakeLevel().withBlock(ORIGIN, waterlogged)
                .withBlock(ORIGIN.offset(1, 1, 0), waterlogged);
        assertSame(expectedShape(fenceBlock.collisionShapes(),
                        pitchMask(EightWayDirection.EAST, ElevatedConnections.PITCH_UP)),
                waterlogged.getCollisionShape(level, ORIGIN, CollisionContext.empty()),
                "waterlogging is not one of the arm properties and must not change the shape");
    }

    // --- helpers ------------------------------------------------------------------------------------

    private static BlockState fence() {
        return fenceBlock.defaultBlockState();
    }

    private static BlockPos neighbor(EightWayDirection direction, int dy) {
        return ORIGIN.offset(direction.getX(), dy, direction.getZ());
    }

    private static int pitchMask(EightWayDirection direction, int pitch) {
        return ElevatedConnections.withPitch(ElevatedConnections.EMPTY_PITCH_MASK, direction, pitch);
    }

    private static VoxelShape collisionShapeAt(FakeLevel level, BlockPos blockPos) {
        return level.getBlockState(blockPos).getCollisionShape(level, blockPos, CollisionContext.empty());
    }

    private static VoxelShape expectedShape(ElevatedShapeCache shapeCache, int pitchMask) {
        return expectedShape(shapeCache, fence(), pitchMask);
    }

    private static VoxelShape expectedShape(ElevatedShapeCache shapeCache, BlockState blockState, int pitchMask) {
        return shapeCache.getShape(fenceBlock._getAABBIndex(blockState), pitchMask);
    }
}
