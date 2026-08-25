package fuzs.diagonalfences.world.level.block;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Lets a test construct a {@link Block}, which after {@code Bootstrap.bootStrap()} the game
 * otherwise refuses outright.
 * <p>
 * Every {@code Block} constructor calls {@code BuiltInRegistries.BLOCK.createIntrusiveHolder}, and
 * {@code MappedRegistry#freeze} -- which bootstrap reaches -- shuts that down twice over: it sets
 * {@code frozen} and nulls the {@code unregisteredIntrusiveHolders} map. Production never meets this
 * wall, because diagonal blocks are built from a registry-entry-added callback while the registry is
 * still open. {@link #createBlock} reopens exactly that window, builds one block, and closes it
 * again, so no test can leave a writable block registry behind for the next one.
 * <p>
 * Reflection, because 1.21.11 has no unfreeze. Deliberately confined to test support rather than
 * added to {@code diagonalfences.accesswidener}: a widener line there would put a second entry into
 * the access transformer that ships inside the NeoForge jar, and this is worth nothing outside the
 * test JVM.
 */
final class BlockTestSupport {
    private static final String FROZEN_FIELD = "frozen";
    private static final String INTRUSIVE_HOLDERS_FIELD = "unregisteredIntrusiveHolders";

    private static final AtomicInteger BLOCK_ID_COUNTER = new AtomicInteger();

    private static boolean bootstrapped;

    private BlockTestSupport() {
        // static utility
    }

    static void bootstrap() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
    }

    /**
     * Builds one block through {@code blockFactory}, with a fresh id, while the block registry is
     * temporarily open.
     * <p>
     * The registry is closed again in a {@code finally}, so a factory that throws -- which is exactly
     * what the constructor guard tests want -- still leaves it as it found it.
     *
     * @throws IllegalStateException if either registry field has moved, rather than letting it
     *                               resurface later as a confusing failure from a block constructor
     */
    static <T extends Block> T createBlock(Function<BlockBehaviour.Properties, T> blockFactory) {
        bootstrap();
        MappedRegistry<?> blockRegistry = (MappedRegistry<?>) BuiltInRegistries.BLOCK;
        setBlockRegistryOpen(blockRegistry, true);
        try {
            return blockFactory.apply(fenceProperties());
        } finally {
            setBlockRegistryOpen(blockRegistry, false);
        }
    }

    /** Fence properties carrying an id no other test block has used. */
    private static BlockBehaviour.Properties fenceProperties() {
        Identifier identifier = Identifier.fromNamespaceAndPath("diagonalfences",
                "test/fence_" + BLOCK_ID_COUNTER.incrementAndGet());
        return BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_FENCE)
                .setId(ResourceKey.create(Registries.BLOCK, identifier));
    }

    private static void setBlockRegistryOpen(MappedRegistry<?> blockRegistry, boolean open) {
        try {
            field(FROZEN_FIELD).setBoolean(blockRegistry, !open);
            // freeze() nulls this map; restoring it to null is what "closed" means, and the holders
            // handed to test blocks stay valid because each block keeps its own reference
            field(INTRUSIVE_HOLDERS_FIELD).set(blockRegistry,
                    open ? new IdentityHashMap<Object, Holder.Reference<?>>() : null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "cannot reopen the block registry for test block construction; MappedRegistry's fields have moved",
                    exception);
        }
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field field = MappedRegistry.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
