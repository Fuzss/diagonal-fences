package fuzs.diagonalfences.world.level.block;

import fuzs.diagonalblocks.api.v2.block.DiagonalBlock;
import fuzs.diagonalblocks.api.v2.block.type.DiagonalBlockType;
import fuzs.diagonalblocks.api.v2.block.type.DiagonalBlockTypeImpl;
import fuzs.diagonalblocks.api.v2.util.EightWayDirection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The real {@link FenceView}, reading straight out of the level.
 *
 * @param blockGetter       the level being queried
 * @param diagonalBlockType the type sloped arms are being resolved for; a block of any other type
 *                          is not ours to connect
 */
public record LevelFenceView(BlockGetter blockGetter, DiagonalBlockType diagonalBlockType) implements FenceView {

    @Override
    public boolean attachable(BlockPos from, BlockPos to, EightWayDirection dirFromTo) {
        BlockState fromBlockState = this.blockStateAt(from);
        BlockState toBlockState = this.blockStateAt(to);
        // Evaluated at both ends and combined with &&, which is commutative and therefore symmetric
        // by construction -- the contract FenceView#attachable requires. This mirrors how upstream's
        // StarCollisionBlock#updateDiagonalProperties resolves a flat diagonal arm.
        return this.attaches(fromBlockState, toBlockState, dirFromTo.getOpposite())
                && this.attaches(toBlockState, fromBlockState, dirFromTo);
    }

    /**
     * Deliberately uses {@link DiagonalBlock#attachesDiagonallyTo} for cardinal directions too,
     * rather than {@link DiagonalBlock#attachesDirectlyTo} as upstream does for the flat case.
     * <p>
     * {@code attachesDirectlyTo} resolves to {@code FenceBlock#connectsTo}, which also attaches to
     * any sturdy face. That is right for a flat arm into an adjacent block, but here the neighbor is
     * offset both horizontally and vertically, and attaching to a sturdy face would make fences slope
     * into stairs and walls -- explicitly out of scope for this feature. Fence-to-fence identity is
     * the same test in both cardinal and intercardinal directions, so both go through this path.
     */
    private boolean attaches(BlockState blockState, BlockState neighborBlockState, EightWayDirection neighborSide) {
        return blockState.getBlock() instanceof DiagonalBlock diagonalBlock
                && diagonalBlock.getType() == this.diagonalBlockType
                && diagonalBlock.attachesDiagonallyTo(neighborBlockState, neighborSide);
    }

    /**
     * Routes through upstream's conversion map so a neighbor that was never converted to its diagonal
     * counterpart still resolves.
     * <p>
     * Note this map is empty for fences today: it is only populated when
     * {@code DiagonalBlockTypeImpl#supportsOriginalBlockState()} is true, which defaults to false and
     * is not overridden for fences. The lookup is kept for consistency with upstream, but nothing
     * here may depend on it actually resolving anything.
     */
    private BlockState blockStateAt(BlockPos blockPos) {
        BlockState blockState = this.blockGetter.getBlockState(blockPos);
        return DiagonalBlockTypeImpl.NON_DIAGONAL_TO_DIAGONAL_BLOCK_STATES.getOrDefault(blockState, blockState);
    }
}
