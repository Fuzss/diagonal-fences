package fuzs.diagonalfences.fabric.client.resources.model;

import fuzs.diagonalblocks.api.v2.block.type.DiagonalBlockType;
import fuzs.diagonalblocks.api.v2.client.MultiPartTranslator;
import fuzs.diagonalblocks.api.v2.util.EightWayDirection;
import fuzs.diagonalblocks.impl.client.resources.model.RotatedVariant;
import fuzs.diagonalfences.DiagonalFences;
import fuzs.diagonalfences.client.resources.model.FenceArmVariants;
import net.minecraft.client.renderer.block.model.BlockModelDefinition;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.block.model.multipart.Selector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Injects the sloped arm selector into every fence's multipart model, and repairs the geometry key
 * upstream leaves null on its way past.
 * <p>
 * Registered for our fence type only, from the Fabric client entry point. NeoForge never constructs
 * this class.
 * <p>
 * The parsing this used to do lives in {@link FenceArmVariants}, in Common, where it is under test.
 * What is left here is selector plumbing that needs a live model bake to mean anything.
 * <p>
 * <strong>Depends on {@code RotatedVariant}, which is upstream implementation, not API.</strong>
 * That is accepted for the same reason as the other internal dependencies this feature already
 * carries: {@code dependencies.common.diagonalblocks=embedded} pins the version into our own jar, so
 * a change upstream is a compile failure at version-bump time rather than a crash in somebody's
 * world. Re-check it on every Minecraft update.
 */
public class ElevatedMultiPartTranslator extends MultiPartTranslator {

    public ElevatedMultiPartTranslator(DiagonalBlockType diagonalBlockType) {
        super(diagonalBlockType);
    }

    @Override
    protected BlockModelDefinition.MultiPartDefinition applyAdditionalSelectors(BlockModelDefinition.MultiPartDefinition multiPart) {
        // read the cardinal sides from the ORIGINAL model, before upstream appends its intercardinals
        Map<EightWayDirection, BlockStateModel.Unbaked> cardinalArms = FenceArmVariants.cardinalArms(multiPart);
        BlockModelDefinition.MultiPartDefinition withDiagonals = super.applyAdditionalSelectors(multiPart);
        List<Selector> selectors = withDiagonals.selectors();

        if (alreadyApplied(selectors)) {
            // upstream's appender bails out the same way -- the model bake event can run more than
            // once, and a second sloped arm selector would draw every arm twice
            return withDiagonals;
        }
        if (selectors.isEmpty()) {
            // ours would become selectors.getFirst(), which MultiPartModel.particleSprite delegates
            // to, and it has no sprite of its own worth handing out
            DiagonalFences.LOGGER.warn("Fence multipart model has no selectors at all; skipping sloped arms for it.");
            return withDiagonals;
        }
        if (cardinalArms.size() != FenceArmVariants.EXPECTED_CARDINAL_ARMS) {
            // a resource pack whose fence model is not shaped like a fence model. Fail soft: the
            // block keeps its stock flat and diagonal arms, it just never slopes
            DiagonalFences.LOGGER.warn(
                    "Fence multipart model declares {} cardinal side selectors, expected {} ({}); skipping sloped arms for it.",
                    cardinalArms.size(),
                    FenceArmVariants.EXPECTED_CARDINAL_ARMS,
                    cardinalArms.keySet());
            return withDiagonals;
        }

        List<Selector> newSelectors = withGeometryKeys(selectors);
        // Unconditional, and it has to be: MultiPartModel picks its submodels once per block state
        // and caches them, while the pitch is derived from neighbours and lives in no block state.
        // Appended last, so it never becomes the selector particleSprite reads.
        newSelectors.add(new Selector(Optional.empty(),
                new ElevatedArmsVariant(FenceArmVariants.allArms(cardinalArms), this.diagonalBlockType)));
        return new BlockModelDefinition.MultiPartDefinition(newSelectors);
    }

    /**
     * Wraps upstream's rotated arms so the multipart's geometry key stops coming back null. Every
     * other selector is passed through untouched -- vanilla's own variants already implement the key.
     *
     * @see ConstantGeometryVariant
     */
    private static List<Selector> withGeometryKeys(List<Selector> selectors) {
        List<Selector> newSelectors = new ArrayList<>(selectors.size() + 1);
        for (Selector selector : selectors) {
            if (selector.variant() instanceof RotatedVariant) {
                newSelectors.add(new Selector(selector.condition(),
                        new ConstantGeometryVariant(selector.variant())));
            } else {
                newSelectors.add(selector);
            }
        }
        return newSelectors;
    }

    private static boolean alreadyApplied(List<Selector> selectors) {
        for (Selector selector : selectors) {
            if (selector.variant() instanceof ElevatedArmsVariant) {
                return true;
            }
        }
        return false;
    }
}
