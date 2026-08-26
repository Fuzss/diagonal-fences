package fuzs.diagonalfences.world.level.block;

import fuzs.diagonalblocks.api.v2.util.EightWayDirection;
import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.Set;

/**
 * An in-memory {@link FenceView} over a set of fence positions.
 * <p>
 * {@link #attachable} is deliberately implemented as an unordered set membership test, so it is
 * symmetric by construction. That keeps the symmetry tests honest: any asymmetry they catch is in
 * {@link ElevatedConnections}, which is the code under test, not in this fake.
 */
final class FakeFenceView implements FenceView {
    private final Set<BlockPos> fences = new HashSet<>();

    FakeFenceView withFence(BlockPos blockPos) {
        this.fences.add(blockPos);
        return this;
    }

    @Override
    public boolean attachable(BlockPos from, BlockPos to, EightWayDirection dirFromTo) {
        return this.fences.contains(from) && this.fences.contains(to);
    }

    @Override
    public boolean fenceAbove(BlockPos blockPos) {
        return this.fences.contains(blockPos.above());
    }
}
