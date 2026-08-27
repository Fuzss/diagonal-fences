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

    /**
     * A flat rail exists toward {@code direction} exactly when a fence was declared beside this one
     * at the same height.
     * <p>
     * The real view also has to check that the block state's side property is set, because vanilla
     * sets it against terrain too and that is the whole distinction the method draws. There are no
     * block states here, so the fake states the same thing the only way it can: a neighbouring fence
     * is a rail, and anything else -- including the terrain that made the property true in the first
     * place -- is not. {@link ElevatedFenceInLevelTest} is where the property half is exercised.
     */
    @Override
    public boolean hasFlatArmToRail(BlockPos blockPos, EightWayDirection direction) {
        return this.fences.contains(blockPos.offset(direction.getX(), 0, direction.getZ()));
    }
}
