package fuzs.diagonalfences;

import fuzs.diagonalblocks.api.v2.block.type.DiagonalBlockType;
import fuzs.diagonalfences.world.level.block.ElevatedFenceBlockType;
import fuzs.puzzleslib.api.core.v1.ModConstructor;
import net.minecraft.resources.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;

public class DiagonalFences implements ModConstructor {
    public static final String MOD_ID = "diagonalfences";
    public static final String MOD_NAME = "Diagonal Fences";
    public static final Logger LOGGER = LogManager.getLogger(DiagonalFences.MOD_NAME);

    private final DiagonalBlockType fenceType;

    /**
     * @param fenceType the fence type to register. This one parameter is the whole loader gate:
     *                  Fabric passes {@link ElevatedFenceBlockType#INSTANCE} and gets sloped arms,
     *                  NeoForge passes {@code DiagonalBlockTypes.FENCE} and keeps stock behaviour.
     *                  Reverting the feature is reverting the Fabric call site -- nothing is written
     *                  to a block state, so no saved world can be left in a bad state either way.
     */
    public DiagonalFences(DiagonalBlockType fenceType) {
        this.fenceType = Objects.requireNonNull(fenceType, "fence type is null");
    }

    @Override
    public void onConstructMod() {
        // must happen before registration: the type's factory overrides decide which blocks get a
        // diagonal variant created at all, and blocks are created as the registry is populated
        ElevatedFenceBlockType.copyBuiltInFenceBlacklist(this.fenceType);
        DiagonalBlockType.register(this.fenceType);
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
