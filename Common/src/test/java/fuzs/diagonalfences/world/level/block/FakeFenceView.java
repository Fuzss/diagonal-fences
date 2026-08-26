package fuzs.diagonalfences.world.level.block;

import fuzs.diagonalblocks.api.v2.util.EightWayDirection;
import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * An in-memory {@link FenceView} over a set of fence positions and a set of flat arms to rails.
 * <p>
 * Note what this fake cannot catch, and what {@code ElevatedFenceInLevelTest} exists to catch
 * instead: the arms recorded here are already "to a rail" by fiat. Whether a real fence's side
 * property means a rail or merely a sturdy block face is {@code LevelFenceView}'s judgement, and
 * getting it wrong there is what suppressed every staircase railing in Phases 1-5.
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

    FakeFenceView withFlatArmToRail(BlockPos blockPos, EightWayDirection direction) {
        this.flatArms.add(Map.entry(blockPos, direction));
        return this;
    }

    @Override
    public boolean attachable(BlockPos from, BlockPos to, EightWayDirection dirFromTo) {
        return this.fences.contains(from) && this.fences.contains(to);
    }

    @Override
    public boolean hasFlatArmToRail(BlockPos blockPos, EightWayDirection direction) {
        return this.flatArms.contains(Map.entry(blockPos, direction));
    }
}
