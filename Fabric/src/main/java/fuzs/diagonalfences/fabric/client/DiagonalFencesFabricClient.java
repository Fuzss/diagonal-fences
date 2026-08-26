package fuzs.diagonalfences.fabric.client;

import fuzs.diagonalfences.DiagonalFences;
import fuzs.diagonalfences.client.DiagonalFencesClient;
import fuzs.puzzleslib.api.client.core.v1.ClientModConstructor;
import net.fabricmc.api.ClientModInitializer;

public class DiagonalFencesFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // The sloped arm translator is deliberately NOT registered here, even though this is where
        // client registration belongs. Upstream resolves and caches its translator per type during
        // diagonalblocks' own client entrypoint, which Fabric runs before ours, so a register call
        // from this method is silently ignored -- it writes to a map that has already been read.
        // It happens from DiagonalFencesFabric instead; see ElevatedFenceModels for the log that
        // proves the ordering.
        ClientModConstructor.construct(DiagonalFences.MOD_ID, DiagonalFencesClient::new);
    }
}
