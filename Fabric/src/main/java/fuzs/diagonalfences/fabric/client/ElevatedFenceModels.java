package fuzs.diagonalfences.fabric.client;

import fuzs.diagonalblocks.api.v2.client.MultiPartTranslator;
import fuzs.diagonalfences.fabric.client.resources.model.ElevatedMultiPartTranslator;
import fuzs.diagonalfences.world.level.block.ElevatedFenceBlockType;

/**
 * The one client-side registration the sloped arms need, kept in a class of its own so it can be
 * called from the <strong>main</strong> mod initializer without a dedicated server ever loading a
 * client class.
 * <p>
 * <strong>Why not the client initializer, which is where this obviously belongs.</strong>
 * {@code MultiPartTranslator.get} is a {@code computeIfAbsent}, and
 * {@code DiagonalBlocksClient#onRegisterBlockStateResolver} calls it once per type and
 * <em>captures the result in the block state resolver's closure</em>. Puzzleslib runs that callback
 * synchronously inside {@code ClientModConstructor.construct}, and Fabric runs a dependency's client
 * entrypoint before its dependents' -- so upstream resolved our fence type to a plain
 * {@code MultiPartTranslator}, cached it, closed over it, and every later {@code register} call was
 * writing to a map nobody read again. Registering from a client entrypoint is therefore not late by
 * a hair, it is <strong>unconditionally too late</strong>:
 * <pre>
 * 02:16:48  Constructing components for diagonalblocks:client   &lt;- get(ELEVATED), captured
 * 02:16:48  Constructing components for diagonalfences:client   &lt;- register(), ignored
 * </pre>
 * Every {@code main} entrypoint runs before any {@code client} entrypoint, which turns that race
 * into a six second margin in the same log.
 * <p>
 * <strong>Loading this class on a server would crash.</strong> It names client-only types, so the
 * caller must check the environment first; the check cannot live in here, because reaching the check
 * would already have loaded the class.
 *
 * @see fuzs.diagonalfences.fabric.DiagonalFencesFabric
 */
public final class ElevatedFenceModels {

    private ElevatedFenceModels() {
        // static utility
    }

    /**
     * Pairs with the {@code ElevatedFenceBlockType.INSTANCE} argument in the main initializer:
     * {@link MultiPartTranslator} dispatches on exactly that type. Register one without the other
     * and you get either sloped collision on flat-looking fences, or a translator nothing consults
     * -- so revert both, or neither.
     */
    public static void registerMultiPartTranslator() {
        MultiPartTranslator.register(ElevatedFenceBlockType.INSTANCE,
                new ElevatedMultiPartTranslator(ElevatedFenceBlockType.INSTANCE));
    }
}
