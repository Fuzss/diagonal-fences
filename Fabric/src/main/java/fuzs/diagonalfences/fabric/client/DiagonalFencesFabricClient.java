package fuzs.diagonalfences.fabric.client;

import fuzs.diagonalblocks.api.v2.client.MultiPartTranslator;
import fuzs.diagonalfences.DiagonalFences;
import fuzs.diagonalfences.client.DiagonalFencesClient;
import fuzs.diagonalfences.fabric.client.resources.model.ElevatedMultiPartTranslator;
import fuzs.diagonalfences.world.level.block.ElevatedFenceBlockType;
import fuzs.puzzleslib.api.client.core.v1.ClientModConstructor;
import net.fabricmc.api.ClientModInitializer;

public class DiagonalFencesFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // The render half of the Fabric-only sloped arms. This has to be registered before models
        // are baked, and client init is well before the first resource reload finishes.
        //
        // Note the pairing with DiagonalFencesFabric: that registers ElevatedFenceBlockType.INSTANCE
        // as the fence type, and MultiPartTranslator dispatches on exactly that type. Register one
        // without the other and you get either sloped collision with flat-looking fences, or a
        // translator that is never consulted -- so revert both, or neither.
        MultiPartTranslator.register(ElevatedFenceBlockType.INSTANCE,
                new ElevatedMultiPartTranslator(ElevatedFenceBlockType.INSTANCE));
        ClientModConstructor.construct(DiagonalFences.MOD_ID, DiagonalFencesClient::new);
    }
}
