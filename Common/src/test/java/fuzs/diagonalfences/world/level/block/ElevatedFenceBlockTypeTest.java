package fuzs.diagonalfences.world.level.block;

import fuzs.diagonalblocks.api.v2.block.DiagonalFenceBlock;
import fuzs.diagonalblocks.api.v2.block.type.DiagonalBlockType;
import fuzs.diagonalblocks.api.v2.block.type.DiagonalBlockTypeImpl;
import fuzs.diagonalblocks.api.v2.block.type.DiagonalBlockTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.FenceBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <strong>Method order is load-bearing here, which is why it is pinned.</strong>
 * {@code DiagonalBlockTypes} and {@code ModBlockTagsProvider} initialise each other, and the cycle
 * only survives when it is entered through {@code DiagonalBlockTypes}. Class initialisation happens
 * once per JVM, so whichever test touches the pair first is the only one that can ever observe a
 * regression in {@code copyBuiltInFenceBlacklist}'s ordering -- every later test finds both classes
 * already initialised and passes regardless. The blacklist tests therefore run first, and this class
 * is the only one in the suite that touches {@code DiagonalBlockTypes} at all.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElevatedFenceBlockTypeTest {
    /** One of the twenty fences upstream disables outright, from a Fabric mod. */
    private static final String BLACKLISTED_FENCE_ID = "betternether:nether_reed_fence";

    @BeforeAll
    static void bootstrap() {
        BlockTestSupport.bootstrap();
    }

    /** A type that has had nothing done to it, so a replay can be observed changing its answers. */
    private static DiagonalBlockType freshFenceType() {
        return new DiagonalBlockTypeImpl("fences",
                FenceBlock.class,
                block -> DiagonalFenceBlock::new,
                CrossCollisionBlock.NORTH,
                CrossCollisionBlock.EAST,
                CrossCollisionBlock.WEST,
                CrossCollisionBlock.SOUTH,
                CrossCollisionBlock.WATERLOGGED);
    }

    // --- id compatibility with the stock type -------------------------------------------------

    /**
     * The rollback claim in one assertion. Our type derives its namespace, block ids and blacklist
     * tag from the name {@code "fences"} exactly as the stock one does, so swapping the two changes
     * no id anywhere -- existing worlds load unchanged and the shipped blacklist tag still applies.
     * If this ever fails, reverting the feature stops being free and the rollback note is wrong.
     */
    @Test
    void ourTypeProducesTheSameIdsAsTheStockOne() {
        assertEquals(DiagonalBlockTypes.FENCE.getBlacklistTagKey(),
                ElevatedFenceBlockType.INSTANCE.getBlacklistTagKey());
        for (String path : new String[]{"minecraft/oak_fence", "minecraft/nether_brick_fence", "somemod/some_fence"}) {
            assertEquals(DiagonalBlockTypes.FENCE.id(path), ElevatedFenceBlockType.INSTANCE.id(path));
        }
        assertEquals(DiagonalBlockTypes.FENCE.toString(), ElevatedFenceBlockType.INSTANCE.toString());
    }

    /**
     * {@code DiagonalBlockTypeImpl#isTarget} matches candidates on their block state property
     * <em>count</em>, so a property list that drifts from upstream's silently stops converting
     * fences rather than failing.
     */
    @Test
    void ourTypeTargetsTheSameBlocksAsTheStockOne() {
        Identifier oakFenceId = Identifier.parse("minecraft:oak_fence");
        assertTrue(DiagonalBlockTypes.FENCE.isTarget(oakFenceId, Blocks.OAK_FENCE),
                "the stock type should target a vanilla fence, or this test proves nothing");
        assertTrue(ElevatedFenceBlockType.INSTANCE.isTarget(oakFenceId, Blocks.OAK_FENCE));
        assertFalse(ElevatedFenceBlockType.INSTANCE.isTarget(Identifier.parse("minecraft:oak_planks"),
                Blocks.OAK_PLANKS));
    }

    // --- the built-in blacklist replay --------------------------------------------------------

    /**
     * The replay is the correction that stops twenty hard-coded fences -- BetterNether and BetterEnd
     * among them, both Fabric mods -- from wrongly getting diagonal variants on a freshly created
     * type. Asserting the "before" state is what keeps it non-vacuous: without it the test would
     * still pass if the blacklist were empty and {@code isTarget} simply always returned false.
     */
    @Test
    @Order(1)
    void copyingTheBuiltInBlacklistDisablesTheBlocksUpstreamDisables() {
        DiagonalBlockType fenceType = freshFenceType();
        Identifier blacklistedId = Identifier.parse(BLACKLISTED_FENCE_ID);
        assertTrue(fenceType.isTarget(blacklistedId, Blocks.OAK_FENCE),
                "a fresh type must start out targeting it, or the replay is being credited for nothing");

        ElevatedFenceBlockType.copyBuiltInFenceBlacklist(fenceType);

        assertFalse(fenceType.isTarget(blacklistedId, Blocks.OAK_FENCE),
                "the built-in blacklist did not carry over to a type created outside DiagonalBlockTypes");
        assertTrue(fenceType.isTarget(Identifier.parse("minecraft:oak_fence"), Blocks.OAK_FENCE),
                "the replay disabled a fence it should not have");
    }

    @Test
    @Order(2)
    void theShippedTypeCarriesTheBuiltInBlacklistOnceConstructed() {
        // mirrors what DiagonalFences#onConstructMod does; idempotent, so running it here is safe
        ElevatedFenceBlockType.copyBuiltInFenceBlacklist(ElevatedFenceBlockType.INSTANCE);
        assertFalse(ElevatedFenceBlockType.INSTANCE.isTarget(Identifier.parse(BLACKLISTED_FENCE_ID),
                Blocks.OAK_FENCE));
        assertTrue(ElevatedFenceBlockType.INSTANCE.isTarget(Identifier.parse("minecraft:oak_fence"),
                Blocks.OAK_FENCE));
    }

    @Test
    void anEmptyBlacklistDisablesNothing() {
        DiagonalBlockType fenceType = freshFenceType();
        ElevatedFenceBlockType.applyBlacklist(fenceType, List.of());
        assertTrue(fenceType.isTarget(Identifier.parse(BLACKLISTED_FENCE_ID), Blocks.OAK_FENCE));
        assertTrue(fenceType.isTarget(Identifier.parse("minecraft:oak_fence"), Blocks.OAK_FENCE));
    }

    /**
     * A malformed id must fail loudly. Swallowing it would leave a block un-blacklisted, which shows
     * up much later as a stranger's fence quietly turning diagonal.
     */
    @Test
    void aMalformedBlacklistIdIsRejected() {
        DiagonalBlockType fenceType = freshFenceType();
        assertThrows(RuntimeException.class,
                () -> ElevatedFenceBlockType.applyBlacklist(fenceType, List.of("NOT AN ID")));
    }
}
