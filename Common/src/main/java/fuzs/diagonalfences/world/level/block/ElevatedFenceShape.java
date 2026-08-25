package fuzs.diagonalfences.world.level.block;

import fuzs.diagonalblocks.impl.world.phys.shapes.VoxelCollection;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * A flat fence shape with sloped arms added: stepped boxes for collision, clean skewed lines for the
 * block highlight.
 * <p>
 * It extends {@link VoxelCollection} rather than wrapping one because
 * {@code DestroyEffectsHelper} tests {@code getShape(...) instanceof VoxelCollection} before it will
 * emit block-breaking particles. A plain shape here would silently drop back to vanilla particles
 * for exactly the fences this feature exists for.
 * <p>
 * <strong>Immutable after construction, and it enforces that.</strong> Instances are cached and read
 * concurrently -- the collision path runs on the server thread while chunk meshing runs on Sodium's
 * workers -- and they hold a reference to the flat shape, which is shared by every fence block in the
 * game. {@link VoxelCollection#optimize()} rewrites its own fields <em>and</em> those of its outline
 * base, so inheriting it would let any caller mutate that shared flat shape from whichever thread got
 * there first. Both mutators are therefore overridden shut.
 */
final class ElevatedFenceShape extends VoxelCollection {
    private final VoxelShape flatShape;
    private final List<Vec3[]> pitchedArmEdges;

    /**
     * @param collisionShape  the flat shape's collision geometry unioned with every sloped arm's
     *                        stepped boxes, <strong>already optimized</strong> -- this constructor
     *                        cannot optimize it later without mutating shared state
     * @param flatShape       the flat state shape this was derived from; its outline is delegated to,
     *                        never modified
     * @param pitchedArmEdges one entry per sloped arm, each holding that arm's edge endpoints in
     *                        pairs, in block space
     */
    ElevatedFenceShape(VoxelShape collisionShape, VoxelShape flatShape, List<Vec3[]> pitchedArmEdges) {
        // the outline base goes unused: forAllEdges is overridden to delegate to the flat shape,
        // which already knows how to draw its own straight arms and its own diagonal ones
        super(collisionShape, Shapes.empty());
        this.flatShape = flatShape;
        this.pitchedArmEdges = List.copyOf(pitchedArmEdges);
    }

    @Override
    public void forAllEdges(Shapes.DoubleLineConsumer doubleLineConsumer) {
        this.flatShape.forAllEdges(doubleLineConsumer);
        for (Vec3[] edges : this.pitchedArmEdges) {
            for (int i = 0; i < edges.length; i += 2) {
                Vec3 from = edges[i];
                Vec3 to = edges[i + 1];
                doubleLineConsumer.consume(from.x, from.y, from.z, to.x, to.y, to.z);
            }
        }
    }

    /**
     * A no-op. Everything handed to the constructor is optimized already, and the only thing left for
     * {@link VoxelCollection#optimize()} to do would be to rewrite the shared flat shape in place.
     */
    @Override
    public VoxelCollection optimize() {
        return this;
    }

    /**
     * @throws UnsupportedOperationException always -- this shape is cached and shared, so adding to it
     *                                       would change the collision of every fence that resolved to
     *                                       the same key, from whatever thread happened to call
     */
    @Override
    public void addVoxelShape(VoxelShape voxelShape, VoxelShape particleShape) {
        throw new UnsupportedOperationException("elevated fence shapes are cached and shared, and cannot be added to");
    }
}
