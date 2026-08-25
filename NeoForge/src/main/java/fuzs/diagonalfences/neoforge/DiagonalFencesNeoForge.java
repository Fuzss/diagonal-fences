package fuzs.diagonalfences.neoforge;

import fuzs.diagonalblocks.api.v2.block.type.DiagonalBlockTypes;
import fuzs.diagonalfences.DiagonalFences;
import fuzs.puzzleslib.api.core.v1.ModConstructor;
import net.neoforged.fml.common.Mod;

@Mod(DiagonalFences.MOD_ID)
public class DiagonalFencesNeoForge {

    public DiagonalFencesNeoForge() {
        // stock diagonal fences, unchanged. Sloped arms are Fabric-only -- see DiagonalFencesFabric.
        ModConstructor.construct(DiagonalFences.MOD_ID, () -> new DiagonalFences(DiagonalBlockTypes.FENCE));
    }
}
