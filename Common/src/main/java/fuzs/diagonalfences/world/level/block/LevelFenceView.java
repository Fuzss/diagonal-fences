package fuzs.diagonalfences.world.level.block;

import fuzs.diagonalblocks.api.v2.block.DiagonalBlock;
import fuzs.diagonalblocks.api.v2.block.StarCollisionBlock;
import fuzs.diagonalblocks.api.v2.block.type.DiagonalBlockType;
import fuzs.diagonalblocks.api.v2.block.type.DiagonalBlockTypeImpl;
import fuzs.diagonalblocks.api.v2.util.EightWayDirection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

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
     * The property being set is necessary but <strong>not sufficient</strong>.
     * {@link net.minecraft.world.level.block.FenceBlock#connectsTo} sets a cardinal side property
     * against any sturdy full-block face, so a fence standing beside the riser of a staircase --
     * every staircase -- reports a flat arm it has no rail for. Asking upstream's own predicate with
     * {@code isSideSolid = false} is exactly vanilla's test with that one branch switched off, so a
     * neighbouring fence or a connecting fence gate still counts and terrain does not.
     * <p>
     * Intercardinals short-circuit: upstream only ever sets a diagonal property fence-to-fence
     * ({@code StarCollisionBlock#updateDiagonalProperties} runs {@code attachesDiagonallyTo} at both
     * ends), so there is no sturdy-face case to exclude and no {@link Direction} to pass anyway.
     * <p>
     * The direction handed to {@code attachesDirectlyTo} is the <em>neighbour's</em> facing back
     * toward this block, matching what {@code FenceBlock#getStateForPlacement} passes when it builds
     * the property in the first place. It only reaches the fence gate check, where the two readings
     * agree, but disagreeing with vanilla here would be a trap for whoever reads it next.
     */
    @Override
    public boolean hasFlatArmToRail(BlockPos blockPos, EightWayDirection direction) {
        BlockState blockState = this.blockStateAt(blockPos);
        BooleanProperty booleanProperty = StarCollisionBlock.PROPERTY_BY_DIRECTION.get(direction);
        if (!blockState.hasProperty(booleanProperty) || !blockState.getValue(booleanProperty)) {
            return false;
        }
        if (direction.isIntercardinal()) {
            return true;
        }
        if (!(blockState.getBlock() instanceof DiagonalBlock diagonalBlock)) {
            // Not ours to interpret, and the property is set: leave the face claimed. Unreachable
            // from connectsElevated, which establishes both ends through attachable() first.
            return true;
        }
        BlockState neighborBlockState = this.blockStateAt(blockPos.offset(direction.getX(),
                0,
                direction.getZ()));
        return diagonalBlock.attachesDirectlyTo(neighborBlockState, false, direction.getOpposite().toDirection());
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
