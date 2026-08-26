package fuzs.diagonalfences.world.level.block;

import fuzs.diagonalblocks.api.v2.block.DiagonalFenceBlock;
import fuzs.diagonalblocks.api.v2.block.type.DiagonalBlockType;
import fuzs.diagonalblocks.api.v2.block.type.DiagonalBlockTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Objects;

/**
 * A diagonal fence that also grows sloped arms to fences one block up or down.
 * <p>
 * The pitch is derived from neighbours inside the shape query and persisted nowhere, so this block
 * has the same 512 block states as the stock diagonal fence and the same ids. Reverting the feature
 * is reverting the one line that chooses the block type; no saved world can be left in a bad state.
 * <p>
 * Only {@code getShape} and {@code getCollisionShape} are overridden. {@code getOcclusionShape} and
 * {@code getBlockSupportShape} stay flat on purpose -- that is what keeps
 * {@link BlockBehaviour.BlockStateBase} {@code Cache} valid, and it is why a block cannot be stood on
 * a sloped arm. {@link FenceBlock#getVisualShape} is not overridden either, but it delegates to
 * {@code getShape}, so it does follow the slope; that is the intended reading of "visual".
 */
public class ElevatedDiagonalFenceBlock extends DiagonalFenceBlock {
    /**
     * The five shape dimensions vanilla builds a fence from, halved where
     * {@code StarShapeProvider#_makeShapes} halves them.
     * <p>
     * {@link FenceBlock} passes {@code CrossCollisionBlock(4, 16, 4, 16, 24)}, and
     * {@link CrossCollisionBlock}'s constructor turns that into {@code makeShapes(4, 24, 4, 0, 24)}
     * for collision and {@code makeShapes(4, 16, 4, 0, 16)} for the outline. These have to be named
     * here because both shape functions are {@code private final} on {@link CrossCollisionBlock} and
     * cannot be read back off the block -- so the constructor proves them instead, see
     * {@link #verifyFlatShapesMatchVanilla()}.
     */
    static final float NODE_HALF_WIDTH = 2.0F;
    static final float EXTENSION_HALF_WIDTH = 2.0F;
    static final float EXTENSION_BOTTOM = 0.0F;
    static final float COLLISION_TOP = 24.0F;
    static final float OUTLINE_TOP = 16.0F;

    private final DiagonalBlockType diagonalBlockType;
    private final ElevatedShapeCache collisionShapes;
    private final ElevatedShapeCache outlineShapes;

    /**
     * @param diagonalBlockType the type this block belongs to. Held in a field rather than hardcoded
     *                          the way {@link DiagonalFenceBlock#getType()} hardcodes
     *                          {@link DiagonalBlockTypes#FENCE}, because
     *                          {@code StarCollisionBlock#attachesDiagonallyTo} compares types by
     *                          identity -- inheriting that method would make every diagonal
     *                          connection on this block silently fail.
     */
    public ElevatedDiagonalFenceBlock(BlockBehaviour.Properties properties, DiagonalBlockType diagonalBlockType) {
        this(properties, diagonalBlockType, COLLISION_TOP, OUTLINE_TOP);
    }

    /**
     * Visible for testing the constructor's self-check: feed a wrong dimension and it must throw.
     */
    ElevatedDiagonalFenceBlock(BlockBehaviour.Properties properties, DiagonalBlockType diagonalBlockType, float collisionTop, float outlineTop) {
        super(properties);
        this.diagonalBlockType = Objects.requireNonNull(diagonalBlockType, "diagonal block type is null");
        this.collisionShapes = this.makeShapeCache(collisionTop);
        this.outlineShapes = this.makeShapeCache(outlineTop);
        this.verifyFlatShapesMatchVanilla();
    }

    /**
     * Rebuilds the flat state shapes for one of vanilla's two shape functions.
     * <p>
     * This does not duplicate any work: {@code StarShapeProvider#SHAPES_CACHE} is keyed on the five
     * dimensions rather than on the block, so the array handed back is the very same one the
     * superclass constructor is already using -- which is what makes the identity check in
     * {@link #verifyFlatShapesMatchVanilla()} meaningful.
     */
    private ElevatedShapeCache makeShapeCache(float top) {
        VoxelShape[] flatShapes = this._makeLegacyShapes(NODE_HALF_WIDTH,
                EXTENSION_HALF_WIDTH,
                top,
                EXTENSION_BOTTOM,
                top);
        // upstream stretches a rotated arm's cross-section to match the post when the two widths
        // agree, which for a fence they do; spelled out rather than passed as true so it tracks the
        // dimensions above if they ever change
        boolean stretchWidth = NODE_HALF_WIDTH == EXTENSION_HALF_WIDTH;
        return new ElevatedShapeCache(flatShapes, EXTENSION_HALF_WIDTH, EXTENSION_BOTTOM, top, stretchWidth);
    }

    /**
     * Proves the hardcoded dimensions above still describe the fence vanilla actually built.
     * <p>
     * For a state with no sloped arm the cache returns upstream's flat shape instance untouched, and
     * vanilla returns the element of the array {@code SHAPES_CACHE} holds under the hash of those
     * same five dimensions. The two are therefore <em>the same object</em> if and only if our
     * dimensions match vanilla's. Comparing by identity rather than by geometry is deliberate: it is
     * exact, it is free, and it makes a vanilla constant change fail loudly at startup instead of
     * shipping collision that is quietly the wrong size.
     *
     * @throws IllegalStateException if either shape function was rebuilt with the wrong dimensions
     */
    private void verifyFlatShapesMatchVanilla() {
        BlockState blockState = this.defaultBlockState();
        int flatIndex = this._getAABBIndex(blockState);
        verifyFlatShape("collision",
                this.collisionShapes,
                flatIndex,
                super.getCollisionShape(blockState,
                        EmptyBlockGetter.INSTANCE,
                        BlockPos.ZERO,
                        CollisionContext.empty()));
        verifyFlatShape("outline",
                this.outlineShapes,
                flatIndex,
                super.getShape(blockState, EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty()));
    }

    private static void verifyFlatShape(String name, ElevatedShapeCache shapeCache, int flatIndex, VoxelShape vanillaShape) {
        if (shapeCache.getShape(flatIndex, ElevatedConnections.EMPTY_PITCH_MASK) != vanillaShape) {
            throw new IllegalStateException(
                    "elevated fence " + name + " shapes were rebuilt with dimensions that do not match vanilla's; "
                            + "check FenceBlock against the constants in " + ElevatedDiagonalFenceBlock.class.getSimpleName());
        }
    }

    @Override
    public DiagonalBlockType getType() {
        return this.diagonalBlockType;
    }

    /** Visible for testing that a flat query returns upstream's instance and caches nothing. */
    ElevatedShapeCache collisionShapes() {
        return this.collisionShapes;
    }

    /** Visible for testing; see {@link #collisionShapes()}. */
    ElevatedShapeCache outlineShapes() {
        return this.outlineShapes;
    }

    @Override
    protected VoxelShape getShape(BlockState blockState, BlockGetter blockGetter, BlockPos blockPos, CollisionContext collisionContext) {
        return this.elevatedShape(this.outlineShapes, blockState, blockGetter, blockPos);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState blockState, BlockGetter blockGetter, BlockPos blockPos, CollisionContext collisionContext) {
        return this.elevatedShape(this.collisionShapes, blockState, blockGetter, blockPos);
    }

    private VoxelShape elevatedShape(ElevatedShapeCache shapeCache, BlockState blockState, BlockGetter blockGetter, BlockPos blockPos) {
        int flatIndex = this._getAABBIndex(blockState);
        return shapeCache.getShape(flatIndex, this.computePitchMask(blockGetter, blockPos));
    }

    /**
     * Short-circuits on {@link EmptyBlockGetter}, which is not an optimisation but the guarantee this
     * whole design rests on.
     * <p>
     * {@code BlockBehaviour.BlockStateBase.Cache} builds its collision shape by calling
     * {@code getCollisionShape(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO, empty())} once per
     * state at startup. That cached shape feeds {@code getBlockSupportShape}, {@code isUnobstructed}
     * and -- critically -- {@code StarCollisionBlock#isNotCollidingWithNeighbors}, which queries
     * <em>neighbours</em>. If a sloped shape could ever reach the cache, fence A would ask fence B
     * which would ask fence A. Returning early here makes "the state cache is always flat" a
     * property of the code rather than a consequence of {@code EmptyBlockGetter} happening to report
     * air in all sixteen probed positions.
     * <p>
     * <strong>No test kills this line</strong>, and none can: an empty level reports air, so the
     * mask comes out empty either way and the assertions still hold with the branch deleted. It
     * earns its place as the intent made explicit, plus the sixteen probes per state it saves across
     * the 6,656 fence states built at startup. Do not delete it because coverage says it is dead.
     */
    private int computePitchMask(BlockGetter blockGetter, BlockPos blockPos) {
        if (blockGetter == EmptyBlockGetter.INSTANCE) {
            return ElevatedConnections.EMPTY_PITCH_MASK;
        }
        FenceView fenceView = new LevelFenceView(blockGetter, this.diagonalBlockType);
        return ElevatedConnections.computePitchMask(fenceView, blockPos);
    }
}
