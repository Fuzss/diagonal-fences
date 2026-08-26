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
     * The reason {@link LevelFenceView#hasFlatArmToRail} can short-circuit intercardinals to
     * {@code true} is the same reason no test can kill that branch: upstream sets a diagonal
     * property only after {@code attachesDiagonallyTo} passes at both ends, which requires
     * {@link BlockTags#FENCES}. An intercardinal property therefore already implies a rail, and
     * there is no sturdy-face case for it to exclude.
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
     * A fence gate is a rail, so it keeps its suppressing power. Without this the fix would read as
     * "only a fence counts", and a run that steps up beside a gate would grow an arm straight through
     * it.
     */
    @Test
    void aConnectingFenceGateStillSuppressesTheSlope() {
        BlockState lowerAgainstGate = fence().setValue(CrossCollisionBlock.EAST, Boolean.TRUE);
        BlockState gate = Blocks.OAK_FENCE_GATE.defaultBlockState()
                .setValue(FenceGateBlock.FACING, Direction.NORTH);
        FakeLevel level = new FakeLevel().withBlock(ORIGIN, lowerAgainstGate)
                .withBlock(ORIGIN.offset(1, 0, 0), gate)
                .withBlock(ORIGIN.offset(1, 1, 0), fence());

        assertSame(expectedShape(fenceBlock.collisionShapes(),
                        lowerAgainstGate,
                        ElevatedConnections.EMPTY_PITCH_MASK),
                lowerAgainstGate.getCollisionShape(level, ORIGIN, CollisionContext.empty()),
                "a gate this fence genuinely connects to occupies the face, exactly as a fence does");
    }

    // --- what must NOT slope ---------------------------------------------------------------------

    /**
     * Flat wins -- when the flat arm reaches a real rail. The fence already carries a flat east arm to
     * the fence beside it, so the fence up and to the east gets nothing: that face is taken.
     * <p>
     * Contrast {@link #slopesUpAlongsideAStairRunBuiltFromSolidBlocks}, which is the same block state
     * with a solid block where this one has a fence. Those two are the whole Phase 6 fix; if either
     * can be made to pass by reading the side property alone, the fix has been undone.
     */
    @Test
    void aFlatArmSuppressesTheSlopeOnThatFace() {
        BlockState withFlatEastArm = fence().setValue(CrossCollisionBlock.EAST, Boolean.TRUE);
        FakeLevel level = new FakeLevel().withBlock(ORIGIN, withFlatEastArm)
                .withBlock(ORIGIN.offset(1, 0, 0), fence())
                .withBlock(ORIGIN.offset(1, 1, 0), fence());

        assertSame(expectedShape(fenceBlock.collisionShapes(), withFlatEastArm, ElevatedConnections.EMPTY_PITCH_MASK),
                withFlatEastArm.getCollisionShape(level, ORIGIN, CollisionContext.empty()),
                "a face already carrying a flat arm must not also grow a sloped one");
    }

    /**
     * The other end's flat arm suppresses the slope too. In a real build this is a rising run
     * arriving at a landing that already runs flat, and it is the case that would break first if
     * {@code connectsElevated} were ever rewritten as "the lower block looks up, the upper block
     * looks down" -- the suppressing arm belongs to only one of the two ends.
     */
    @Test
    void aFlatArmOnTheNeighbourAlsoSuppressesTheSlope() {
        BlockPos upper = ORIGIN.offset(1, 1, 0);
        BlockState upperRunningFlatWest = fence().setValue(CrossCollisionBlock.WEST, Boolean.TRUE);
        FakeLevel level = new FakeLevel().withBlock(ORIGIN, fence())
                .withBlock(upper, upperRunningFlatWest)
                .withBlock(upper.offset(-1, 0, 0), fence());

        assertSame(expectedShape(fenceBlock.collisionShapes(), ElevatedConnections.EMPTY_PITCH_MASK),
                collisionShapeAt(level, ORIGIN),
                "the neighbour's face is already taken by a flat arm, so no sloped arm may reach it");
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
