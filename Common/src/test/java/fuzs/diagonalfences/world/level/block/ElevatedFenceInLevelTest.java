package fuzs.diagonalfences.world.level.block;

import fuzs.diagonalblocks.api.v2.block.type.DiagonalBlockType;
import fuzs.diagonalblocks.api.v2.block.type.DiagonalBlockTypes;
import fuzs.diagonalblocks.api.v2.util.EightWayDirection;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
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

    // --- what must NOT slope ---------------------------------------------------------------------

    /**
     * Flat wins. The fence already carries a flat east arm to the fence beside it, so the fence up and
     * to the east gets nothing -- that face is taken. This also exercises the fast path in
     * {@link ElevatedConnections#computePitchMask(FenceView, BlockPos, int)}, which skips a direction
     * outright when the caller's flat index already claims it.
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
     * The other end's flat arm suppresses the slope too, and this is the only test that reaches
     * {@link LevelFenceView#hasFlatArm} at all.
     * <p>
     * {@link #aFlatArmSuppressesTheSlopeOnThatFace} does not: when the queried fence itself carries
     * the flat arm, {@code computePitchMask}'s flat-index fast path skips that direction before the
     * view is ever asked. It is the <em>neighbour's</em> flat arm that has to come out of the level,
     * and this is the shape it takes in a real build -- a rising run arriving at a landing that
     * already runs flat.
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
