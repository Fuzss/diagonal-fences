package fuzs.diagonalfences.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * A hand-populated {@link BlockGetter}, so a shape query can be driven through the real
 * {@link LevelFenceView} rather than through {@link FakeFenceView}.
 * <p>
 * {@code FakeFenceView} stops at the {@link FenceView} seam and therefore proves nothing about the
 * adapter that reads the world -- whether it looks at the right positions, whether it applies
 * upstream's type identity and tag checks, whether it refuses a neighbour that is not a fence. That
 * is what this exists for. It is a map of positions, not a level: no chunks, no sections, no
 * lighting, and deliberately no {@code BlockEntity}, since a fence has none.
 * <p>
 * Positions are copied on write, because {@link ElevatedConnections#computePitchMask} probes with one
 * reused {@link BlockPos.MutableBlockPos} -- storing what it hands us would alias every entry onto
 * the last probe. Lookups still resolve by value, {@code MutableBlockPos} inheriting
 * {@link net.minecraft.core.Vec3i} equality.
 */
final class FakeLevel implements BlockGetter {
    /** Enough room for the vertical span the tests use; nothing here depends on the real values. */
    private static final int MIN_Y = 0;
    private static final int HEIGHT = 256;

    private final Map<BlockPos, BlockState> blockStates = new HashMap<>();

    FakeLevel withBlock(BlockPos blockPos, BlockState blockState) {
        this.blockStates.put(blockPos.immutable(), blockState);
        return this;
    }

    @Override
    public BlockState getBlockState(BlockPos blockPos) {
        return this.blockStates.getOrDefault(blockPos, Blocks.AIR.defaultBlockState());
    }

    @Override
    public FluidState getFluidState(BlockPos blockPos) {
        return this.getBlockState(blockPos).getFluidState();
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos blockPos) {
        return null;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    @Override
    public int getMinY() {
        return MIN_Y;
    }
}
