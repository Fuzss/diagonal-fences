package fuzs.diagonalfences.world.level.block;

import fuzs.diagonalblocks.api.v2.util.EightWayDirection;
import net.minecraft.core.BlockPos;

/**
 * The narrow view of the world that {@link ElevatedConnections} needs.
 * <p>
 * This seam exists so the pitch policy -- offset arithmetic, up/down pairing, flat precedence and
 * mask packing -- is plain logic that can be unit tested. The alternative is testing against a real
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
     * Whether the block at {@code blockPos} carries a flat (same height) arm toward
     * {@code direction} that reaches <strong>another rail</strong> -- a fence, or a fence gate
     * facing the right way. Such an arm already occupies that face, so it suppresses any pitch
     * there.
     * <p>
     * <strong>"To a rail" is the whole point, and it is not a refinement.</strong> A vanilla fence
     * sets its side property against <em>any sturdy full-block face</em>, not only against another
     * fence -- so the lower fence of every staircase carries a flat arm into the riser block it
     * climbs. Reading the raw property here suppressed the pitch on every staircase anyone has ever
     * built, which is the one situation this feature exists for. See {@code LevelFenceView} for the
     * predicate that draws the line, and {@code gotchas.md} (2026-08-26) for how it got shipped.
     * <p>
     * An arm buried in a solid block occupies nothing the sloped arm wants: it is invisible inside
     * the neighbour, and its collision box is already inside a full cube.
     *
     * @param blockPos  the position to inspect
     * @param direction the arm direction to inspect
     * @return whether a flat arm to another rail is present
     */
    boolean hasFlatArmToRail(BlockPos blockPos, EightWayDirection direction);
}
