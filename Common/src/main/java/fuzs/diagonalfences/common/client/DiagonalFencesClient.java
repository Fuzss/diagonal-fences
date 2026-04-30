package fuzs.diagonalfences.common.client;

import fuzs.diagonalfences.common.DiagonalFences;
import fuzs.puzzleslib.common.api.client.core.v1.ClientModConstructor;
import fuzs.puzzleslib.common.api.config.v3.ConfigHolder;

public class DiagonalFencesClient implements ClientModConstructor {
    @Override
    public void onConstructMod() {
        ConfigHolder.registerConfigurationScreen(DiagonalFences.MOD_ID, "diagonalblocks");
    }
}
