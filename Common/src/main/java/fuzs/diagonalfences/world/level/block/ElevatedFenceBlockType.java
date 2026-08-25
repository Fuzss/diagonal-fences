package fuzs.diagonalfences.world.level.block;

import fuzs.diagonalblocks.api.v2.block.type.DiagonalBlockType;
import fuzs.diagonalblocks.api.v2.block.type.DiagonalBlockTypeImpl;
import fuzs.diagonalblocks.api.v2.block.type.DiagonalBlockTypes;
import fuzs.diagonalblocks.impl.data.ModBlockTagsProvider;
import fuzs.diagonalfences.DiagonalFences;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The fence type that produces {@link ElevatedDiagonalFenceBlock} instead of the stock diagonal
 * fence. Registered on Fabric only; NeoForge keeps {@link DiagonalBlockTypes#FENCE}.
 * <p>
 * <strong>Everything user-visible about this type is identical to the stock one.</strong>
 * {@link DiagonalBlockTypeImpl} derives its namespace, its block ids and its blacklist tag from the
 * name alone, so {@code "fences"} yields {@code diagonalfences:<namespace>/<path>} block ids and the
 * {@code diagonalfences:non_diagonal_fences} tag either way. Existing worlds load unchanged, the
 * shipped blacklist tag still applies, and reverting the feature needs no world migration. A test
 * asserts that rather than trusting it.
 * <p>
 * The property list must stay byte-identical to upstream's: {@code DiagonalBlockTypeImpl#isTarget}
 * matches candidate blocks on their property <em>count</em>, and the vanilla-to-diagonal state
 * conversion maps are built by copying properties across.
 */
public final class ElevatedFenceBlockType {
    /**
     * The block factory reads this field by qualified name at block-creation time, which happens
     * during registry events -- long after this class is initialised. Qualifying it also keeps it
     * clear of the illegal-forward-reference rule that applies to simple names in an initializer.
     */
    public static final DiagonalBlockType INSTANCE = new DiagonalBlockTypeImpl("fences",
            FenceBlock.class,
            (Block block) -> (BlockBehaviour.Properties properties) -> {
                return new ElevatedDiagonalFenceBlock(properties, ElevatedFenceBlockType.INSTANCE);
            },
            CrossCollisionBlock.NORTH,
            CrossCollisionBlock.EAST,
            CrossCollisionBlock.WEST,
            CrossCollisionBlock.SOUTH,
            CrossCollisionBlock.WATERLOGGED);

    private ElevatedFenceBlockType() {
        // static holder
    }

    /**
     * Copies the built-in fence blacklist onto {@code diagonalBlockType}.
     * <p>
     * <strong>This is not optional.</strong> {@code DiagonalBlockTypes}' static initializer applies
     * {@code BUILT_IN_BLACKLISTED_TYPES} to the three stock type instances only. A type created
     * anywhere else starts with empty factory overrides, so without this the twenty hard-coded
     * fences -- BetterNether and BetterEnd among them, both Fabric mods, which is exactly where this
     * feature ships -- would wrongly get diagonal variants.
     * <p>
     * Applying it to the stock type as well is a harmless no-op -- {@code disableBlockFactory} just
     * rewrites the same entries -- so the caller does not have to branch on which type it holds. The
     * only cost is that NeoForge, calling this with the stock type, initialises this class and so
     * allocates an {@link #INSTANCE} it never registers: a handful of fields and a tag key, never
     * added to {@code DiagonalBlockType.TYPES} and never iterated. {@link ElevatedDiagonalFenceBlock}
     * itself is never loaded there, because the factory lambda's body does not link until it runs.
     * <p>
     * Call this from mod construction and never from a static
     * initializer: {@link DiagonalBlockTypes} and {@link ModBlockTagsProvider} already reference each
     * other's statics, and adding a third entry point into that cycle at class-load time invites a
     * half-initialised map.
     */
    public static void copyBuiltInFenceBlacklist(DiagonalBlockType diagonalBlockType) {
        // ORDER MATTERS, AND IT IS NOT COSMETIC. DiagonalBlockTypes and ModBlockTagsProvider
        // initialise each other: DiagonalBlockTypes' static block reads BUILT_IN_BLACKLISTED_TYPES,
        // and that map is built as Map.of(DiagonalBlockTypes.FENCE, ...). A cycle only survives if it
        // is entered from the side that assigns its fields first -- DiagonalBlockTypes assigns FENCE
        // before its static block runs, so entering there works and re-entering it is a no-op.
        // Entering through ModBlockTagsProvider instead leaves the map null when DiagonalBlockTypes
        // reads it back, and the mod dies during construction with an NPE inside a class initializer.
        //
        // Upstream never trips this because everything it does starts from DiagonalBlockTypes. We are
        // the first code that touches the pair while deliberately NOT using the stock type, which is
        // exactly what makes us the one caller able to enter from the wrong side. Reading FENCE into
        // a local first is the fix; do not inline it back into the getOrDefault argument, where Java
        // would evaluate the map reference first.
        DiagonalBlockType stockFenceType = DiagonalBlockTypes.FENCE;
        List<String> blacklistedBlockIds = ModBlockTagsProvider.BUILT_IN_BLACKLISTED_TYPES.getOrDefault(
                stockFenceType,
                Collections.emptyList());
        if (blacklistedBlockIds.isEmpty()) {
            // not fatal -- the worst case is that a handful of third party fences get diagonal
            // variants they should not have -- but it always means something upstream moved
            DiagonalFences.LOGGER.warn(
                    "Upstream's built-in fence blacklist came back empty; {} fences that should be excluded may get diagonal variants.",
                    stockFenceType);
        }
        applyBlacklist(diagonalBlockType, blacklistedBlockIds);
    }

    /**
     * The replay itself, separated from where the list comes from so it can be tested without
     * standing up upstream's data providers.
     *
     * @throws IllegalArgumentException via {@link Identifier#parse} if an id is malformed. Failing
     *                                  loudly beats silently leaving a block un-blacklisted, which
     *                                  would show up as a stranger's fence quietly turning diagonal.
     */
    static void applyBlacklist(DiagonalBlockType diagonalBlockType, Collection<String> blacklistedBlockIds) {
        Objects.requireNonNull(diagonalBlockType, "diagonal block type is null");
        for (String blacklistedBlockId : blacklistedBlockIds) {
            diagonalBlockType.disableBlockFactory(Identifier.parse(blacklistedBlockId));
        }
    }
}
