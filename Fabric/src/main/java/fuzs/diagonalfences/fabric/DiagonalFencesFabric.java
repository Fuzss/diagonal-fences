package fuzs.diagonalfences.fabric;

import fuzs.diagonalfences.DiagonalFences;
import fuzs.diagonalfences.world.level.block.ElevatedFenceBlockType;
import fuzs.puzzleslib.api.core.v1.ModConstructor;
import net.fabricmc.api.ModInitializer;

public class DiagonalFencesFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        // Fabric-only: fences here also grow sloped arms to neighbours one block up or down. The
        // level-and-position aware rendering that needs is a Fabric renderer API feature with no
        // NeoForge counterpart, so NeoForge stays on the stock type. Revert this one argument to
        // ElevatedFenceBlockType.INSTANCE -> DiagonalBlockTypes.FENCE and the feature is gone, with
        // no world migration: the pitch lives in no block state.
        ModConstructor.construct(DiagonalFences.MOD_ID,
                () -> new DiagonalFences(ElevatedFenceBlockType.INSTANCE));
    }
}
