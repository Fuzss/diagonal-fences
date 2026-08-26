package fuzs.diagonalfences.world.level.block;

import fuzs.diagonalblocks.api.v2.util.EightWayDirection;
import net.minecraft.core.BlockPos;

/**
 * The narrow view of the world that {@link ElevatedConnections} needs.
 * <p>
 * This seam exists so the pitch policy -- offset arithmetic, up/down pairing and mask packing -- is
 * plain logic that can be unit tested. The alternative is testing against a real
 * level, which is not viable here: the diagonal fence blocks are created at mod-construct time, and
 * {@code attachesDiagonallyTo} consults {@link net.minecraft.tags.BlockTags#FENCES}, which is
 * datapack-loaded and absent after {@code Bootstrap.bootStrap()}.
 * <p>
 * <strong>Positions handed to these methods are valid for the duration of the call only.</strong>
 * {@link ElevatedConnections#computePitchMask(FenceView, BlockPos)} runs on the collision path
 * and reuses one {@link net.minecraft.core.BlockPos.MutableBlockPos} for all sixteen neighbour
 * probes rather than allocating sixteen positions per query. An implementation may read a position
 * and may look one up in a map -- {@code MutableBlockPos} inherits {@link net.minecraft.core.Vec3i}
 * equality, so that resolves by value -- but must never store the reference or use it as a map key.
 */
public interface FenceView {

    /**
     * Whether the blocks at the two positions may form an arm of this feature's kind.
     * <p>
     * <strong>Implementations must be symmetric</strong>: {@code attachable(a, b, d)} must equal
     * {@code attachable(b, a, d.getOpposite())}. The straightforward way to guarantee that is to
     * evaluate both ends and combine with {@code &&}, which is how upstream's
     * {@link fuzs.diagonalblocks.api.v2.block.StarCollisionBlock#updateDiagonalProperties} does it.
     *
     * @param from     one end of the candidate arm
     * @param to       the other end
     * @param dirFromTo the horizontal direction pointing from {@code from} to {@code to}
     * @return whether the two blocks may attach
     */
    boolean attachable(BlockPos from, BlockPos to, EightWayDirection dirFromTo);

    /**
     * Whether a fence of this feature's kind stands directly above {@code blockPos} -- that is,
     * whether the block asked about is buried in a column rather than being the top of one.
     * <p>
     * Only a fence counts. A slab, a lantern or a torch resting on a fence is not another fence and
     * must not suppress a rail; the question here is "is one of my own posts continuing upward", not
     * "is that space occupied".
     * <p>
     * There is no side to attach to when stacking vertically, so this is an identity test on the
     * block above and nothing more -- deliberately not a call into {@code attachesDiagonallyTo}.
     *
     * @param blockPos the position whose ceiling is being probed
     * @return whether the position one block up holds a fence of this kind
     */
    boolean fenceAbove(BlockPos blockPos);
}
