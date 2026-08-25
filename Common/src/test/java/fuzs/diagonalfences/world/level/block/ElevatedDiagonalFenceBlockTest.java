package fuzs.diagonalfences.world.level.block;

import fuzs.diagonalblocks.api.v2.block.DiagonalBlock;
import fuzs.diagonalblocks.api.v2.block.type.DiagonalBlockType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers what can be reached without a running game. The sloped shapes themselves cannot be: they
 * need {@code attachesDiagonallyTo}, which reads {@link net.minecraft.tags.BlockTags#FENCES}, and
 * {@code Bootstrap.bootStrap()} loads no tags. That path's coverage is the Phase 5 gametest.
 */
class ElevatedDiagonalFenceBlockTest {
    /** Vanilla's 32 fence states times the four diagonal booleans upstream adds. */
    private static final int EXPECTED_STATE_COUNT = 512;

    @BeforeAll
    static void bootstrap() {
        BlockTestSupport.bootstrap();
    }

    private static ElevatedDiagonalFenceBlock block(DiagonalBlockType diagonalBlockType) {
        return BlockTestSupport.createBlock(properties -> new ElevatedDiagonalFenceBlock(properties,
                diagonalBlockType));
    }

    private static ElevatedDiagonalFenceBlock block(float collisionTop, float outlineTop) {
        return BlockTestSupport.createBlock(properties -> new ElevatedDiagonalFenceBlock(properties,
                ElevatedFenceBlockType.INSTANCE,
                collisionTop,
                outlineTop));
    }

    /**
     * The check the plan promised to automate rather than run by hand with {@code /data get block}.
     * This design adds no block state, so any movement here means pitch started being persisted --
     * at which point the "reverts with no world migration" claim is no longer true.
     */
    @Test
    void addsNoBlockStates() {
        assertEquals(EXPECTED_STATE_COUNT,
                block(ElevatedFenceBlockType.INSTANCE).getStateDefinition().getPossibleStates().size());
        assertEquals(Blocks.OAK_FENCE.getStateDefinition().getPossibleStates().size() * 16,
                block(ElevatedFenceBlockType.INSTANCE).getStateDefinition().getPossibleStates().size(),
                "exactly upstream's four diagonal booleans on top of vanilla, and nothing else");
    }

    /**
     * {@code StarCollisionBlock#attachesDiagonallyTo} compares types by identity, so a block that
     * inherited {@link fuzs.diagonalblocks.api.v2.block.DiagonalFenceBlock#getType()} -- which
     * hardcodes the stock type -- would fail every diagonal connection, silently.
     */
    @Test
    void reportsTheTypeItWasBuiltWith() {
        ElevatedDiagonalFenceBlock fenceBlock = block(ElevatedFenceBlockType.INSTANCE);
        assertSame(ElevatedFenceBlockType.INSTANCE, fenceBlock.getType());
        assertSame(ElevatedFenceBlockType.INSTANCE, ((DiagonalBlock) fenceBlock).getType());
    }

    @Test
    void rejectsANullType() {
        assertThrows(NullPointerException.class, () -> block((DiagonalBlockType) null));
    }

    /**
     * {@code BlockStateBase.Cache} builds its collision shape through {@link EmptyBlockGetter}. That
     * shape feeds {@code getBlockSupportShape}, {@code isUnobstructed} and
     * {@code StarCollisionBlock#isNotCollidingWithNeighbors}, which queries neighbours -- so a
     * sloped shape reaching the cache is how fence A ends up asking fence B which asks fence A.
     * Identity, not equality: it must be upstream's own flat instance, untouched.
     */
    @Test
    void staysFlatForTheBlockStateCache() {
        ElevatedDiagonalFenceBlock fenceBlock = block(ElevatedFenceBlockType.INSTANCE);
        for (BlockState blockState : fenceBlock.getStateDefinition().getPossibleStates()) {
            int flatIndex = fenceBlock._getAABBIndex(blockState);
            VoxelShape flatCollisionShape = fenceBlock.collisionShapes()
                    .getShape(flatIndex, ElevatedConnections.EMPTY_PITCH_MASK);
            assertSame(flatCollisionShape,
                    blockState.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty()),
                    "an empty level must yield the flat collision shape instance");
            assertSame(fenceBlock.outlineShapes().getShape(flatIndex, ElevatedConnections.EMPTY_PITCH_MASK),
                    blockState.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty()),
                    "an empty level must yield the flat outline shape instance");
        }
        assertEquals(0,
                fenceBlock.collisionShapes().cachedShapeCount(),
                "a flat query must not populate the sloped shape cache at all");
        assertEquals(0, fenceBlock.outlineShapes().cachedShapeCount());
    }

    // --- the constructor's self-check ---------------------------------------------------------

    /**
     * The guard exists because the five shape dimensions are hardcoded -- both of vanilla's shape
     * functions are {@code private final} on {@code CrossCollisionBlock} and cannot be read back.
     * A guard with no test that feeds it bad input is decoration, so here is the bad input.
     */
    @Test
    void rejectsShapeDimensionsThatDoNotMatchVanilla() {
        assertThrows(IllegalStateException.class,
                () -> block(ElevatedDiagonalFenceBlock.COLLISION_TOP + 1.0F,
                        ElevatedDiagonalFenceBlock.OUTLINE_TOP),
                "a wrong collision height must not get past the constructor");
        assertThrows(IllegalStateException.class,
                () -> block(ElevatedDiagonalFenceBlock.COLLISION_TOP,
                        ElevatedDiagonalFenceBlock.OUTLINE_TOP - 1.0F),
                "a wrong outline height must not get past the constructor");
    }

    @Test
    void acceptsVanillasActualShapeDimensions() {
        assertNotNull(block(ElevatedDiagonalFenceBlock.COLLISION_TOP, ElevatedDiagonalFenceBlock.OUTLINE_TOP));
    }
}
