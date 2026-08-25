package fuzs.diagonalfences;

import fuzs.diagonalblocks.api.v2.util.EightWayDirection;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the Common test source set can actually run before any feature logic is built on it.
 * <p>
 * Two things are unproven until this passes: that the {@code diagonalblocks} jar resolves at test
 * runtime (it is {@code modCompileOnlyApi}, which never reaches a runtime classpath on its own),
 * and that Minecraft can bootstrap far enough to hand out real {@link BlockState} instances.
 */
class ToolchainSmokeTest {

    @Test
    void diagonalBlocksResolvesAtTestRuntime() {
        assertEquals(EightWayDirection.NORTH, EightWayDirection.SOUTH.getOpposite());
        assertEquals(EightWayDirection.SOUTH_WEST, EightWayDirection.NORTH_EAST.getOpposite());
        assertTrue(EightWayDirection.NORTH_EAST.isIntercardinal());
        assertFalse(EightWayDirection.NORTH.isIntercardinal());
    }

    @Test
    void minecraftBootstrapsAndYieldsRealBlockStates() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BlockState blockState = Blocks.OAK_FENCE.defaultBlockState();
        // Tags are datapack-loaded and are NOT available after bootstrap -- only properties are.
        assertFalse(blockState.getValue(FenceBlock.NORTH));
        assertFalse(blockState.getValue(FenceBlock.EAST));
    }
}
