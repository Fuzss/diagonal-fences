package fuzs.diagonalfences.neoforge.client;

import fuzs.diagonalfences.common.DiagonalFences;
import fuzs.diagonalfences.common.client.DiagonalFencesClient;
import fuzs.puzzleslib.common.api.client.core.v1.ClientModConstructor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

@Mod(value = DiagonalFences.MOD_ID, dist = Dist.CLIENT)
public class DiagonalFencesNeoForgeClient {

    public DiagonalFencesNeoForgeClient() {
        ClientModConstructor.construct(DiagonalFences.MOD_ID, DiagonalFencesClient::new);
    }
}
