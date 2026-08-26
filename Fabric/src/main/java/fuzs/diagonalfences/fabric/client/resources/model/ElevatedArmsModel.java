package fuzs.diagonalfences.fabric.client.resources.model;

import fuzs.diagonalblocks.api.v2.block.type.DiagonalBlockType;
import fuzs.diagonalblocks.api.v2.util.EightWayDirection;
import fuzs.diagonalfences.client.resources.model.FenceArmVariants;
import fuzs.diagonalfences.world.level.block.ElevatedConnections;
import fuzs.diagonalfences.world.level.block.LevelFenceView;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * The baked half of {@link ElevatedArmsVariant}: sixteen frozen arm meshes and the level lookup that
 * decides which of them to draw at a given position.
 * <p>
 * This is the class the whole feature was waiting on. Fabric's {@code MultiPartModelMixin} forwards
 * the real {@code blockView} and {@code pos} to every selected submodel's {@code emitQuads}, so a
 * submodel can compute geometry from its neighbours -- which is what lets the pitch live in no block
 * state at all.
 */
final class ElevatedArmsModel implements BlockStateModel {
    /**
     * Indexed by {@link FenceArmVariants#armIndex}; an entry is null only if the fence model omitted
     * that side.
     */
    private final Mesh[] arms;
    private final TextureAtlasSprite particleIcon;
    private final DiagonalBlockType diagonalBlockType;

    ElevatedArmsModel(Mesh[] arms, TextureAtlasSprite particleIcon, DiagonalBlockType diagonalBlockType) {
        if (arms.length != FenceArmVariants.ARM_COUNT) {
            throw new IllegalArgumentException(
                    "expected " + FenceArmVariants.ARM_COUNT + " arm slots, got " + arms.length);
        }
        this.arms = arms;
        this.particleIcon = Objects.requireNonNull(particleIcon, "particle icon is null");
        this.diagonalBlockType = Objects.requireNonNull(diagonalBlockType, "diagonal block type is null");
    }

    /**
     * The level-less path draws nothing, which is the only honest answer: without a position there
     * are no neighbours, so there is no pitch to draw. Callers that land here still get the fence's
     * flat arms and post from the multipart's other selectors, so a fence in an inventory or an
     * item frame renders as a normal fence rather than as nothing.
     * <p>
     * Fabric's own documentation calls the vanilla {@code collectParts} methods deprecated in favour
     * of {@code emitQuads} for exactly this reason. Do not "fix" this by guessing a mask.
     */
    @Override
    public void collectParts(RandomSource randomSource, List<BlockModelPart> list) {
        // intentionally empty -- see above
    }

    @Override
    public TextureAtlasSprite particleIcon() {
        return this.particleIcon;
    }

    @Override
    public void emitQuads(QuadEmitter emitter, BlockAndTintGetter blockView, BlockPos pos, BlockState state, RandomSource random, Predicate<@Nullable Direction> cullTest) {
        int pitchMask = this.pitchMask(blockView, pos);
        if (pitchMask == ElevatedConnections.EMPTY_PITCH_MASK) {
            // by far the common case -- every fence that is not on a slope
            return;
        }
        for (EightWayDirection direction : EightWayDirection.values()) {
            int pitch = ElevatedConnections.pitchFor(pitchMask, direction);
            if (pitch == ElevatedConnections.PITCH_NONE) {
                continue;
            }
            Mesh arm = this.arms[FenceArmVariants.armIndex(direction, pitch)];
            if (arm != null) {
                arm.outputTo(emitter);
            }
        }
    }

    /**
     * The geometry key is {@code (this model, pitch mask)} and that pair is <em>exact</em>: our
     * output is the union of a fixed set of frozen meshes chosen by the mask, so the same model with
     * the same mask emits byte-identical geometry, and a different model can never collide because
     * the record holds the instance.
     * <p>
     * The plan originally specified {@code (BlockState, int)}. The block state is the wrong half of
     * that pair twice over -- Fabric documents that the state passed here "is not guaranteed to be
     * the state corresponding to this model", and two states of the same fence with the same mask do
     * produce identical geometry, so keying on it would miss cache hits it should get.
     * <p>
     * <strong>This only pays off because {@link ConstantGeometryVariant} exists.</strong>
     * {@code MultiPartModel.createGeometryKey} returns null if <em>any</em> submodel returns null,
     * and upstream's rotated arms did until we wrapped them. Delete that wrapper and this method
     * goes back to being dead code.
     */
    @Override
    public Object createGeometryKey(BlockAndTintGetter blockView, BlockPos pos, BlockState state, RandomSource random) {
        return new ElevatedArmsKey(this, this.pitchMask(blockView, pos));
    }

    /**
     * Reuses the Phase 1 connection rule verbatim -- {@link BlockAndTintGetter} is a
     * {@link net.minecraft.world.level.BlockGetter}, so the client gets the same mask from the same
     * code as collision does. A second implementation here is how render and collision would drift.
     * <p>
     * There is only one overload to call. The flat-index fast path that used to sit beside it was
     * removed in Phase 6 precisely so that render and collision could not take different routes to
     * the same mask -- which matters here, because the {@code state} handed to a model is not
     * guaranteed to be this block's and so cannot be used to shortcut anything.
     */
    private int pitchMask(BlockAndTintGetter blockView, BlockPos pos) {
        return ElevatedConnections.computePitchMask(new LevelFenceView(blockView, this.diagonalBlockType), pos);
    }

    /** @see #createGeometryKey */
    record ElevatedArmsKey(ElevatedArmsModel model, int pitchMask) {

    }
}
