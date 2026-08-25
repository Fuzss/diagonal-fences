package fuzs.diagonalfences.world.level.block;

import fuzs.diagonalblocks.api.v2.util.EightWayDirection;
import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * An in-memory {@link FenceView} over a set of fence positions and a set of flat arms.
 * <p>
 * {@link #attachable} is deliberately implemented as an unordered set membership test, so it is
 * symmetric by construction. That keeps the symmetry tests honest: any asymmetry they catch is in
 * {@link ElevatedConnections}, which is the code under test, not in this fake.
 */
final class FakeFenceView implements FenceView {
    private final Set<BlockPos> fences = new HashSet<>();
    private final Set<Map.Entry<BlockPos, EightWayDirection>> flatArms = new HashSet<>();

    FakeFenceView withFence(BlockPos blockPos) {
        this.fences.add(blockPos);
        return this;
    }

    FakeFenceView withFlatArm(BlockPos blockPos, EightWayDirection direction) {
        this.flatArms.add(Map.entry(blockPos, direction));
        return this;
    }

    @Override
    public boolean attachable(BlockPos from, BlockPos to, EightWayDirection dirFromTo) {
        return this.fences.contains(from) && this.fences.contains(to);
    }

    @Override
    public boolean hasFlatArm(BlockPos blockPos, EightWayDirection direction) {
        return this.flatArms.contains(Map.entry(blockPos, direction));
    }
}
