package fuzs.diagonalfences.fabric.client.resources.model;

import fuzs.diagonalblocks.api.v2.block.type.DiagonalBlockType;
import fuzs.diagonalblocks.api.v2.util.EightWayDirection;
import fuzs.diagonalfences.world.level.block.ElevatedConnections;
import fuzs.diagonalfences.world.level.block.LevelFenceView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBaker;
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
 * One flat arm of a fence, wrapped so it stops drawing when its own side grows a sloped arm --
 * unless it reaches another rail, in which case it is a rail somebody built and it stays. The rule
 * is {@link ElevatedConnections#suppressesFlatArm}; nothing about it is decided here.
 * <p>
 * This is the render half of {@link ElevatedConnections#suppressFlatArms}, which is the collision
 * and outline half. The two must agree, and the only reason they can is that both ask
 * {@link ElevatedConnections} the same question -- there is no second copy of the rule here, just
 * the place it has to be applied because quads and voxel shapes are produced by different code.
 * <p>
 * <strong>Why a wrapper rather than a condition.</strong> A multipart selector's condition is
 * evaluated against the block state, and the pitch deliberately lives in no block state -- the same
 * constraint that makes {@link ElevatedArmsVariant} unconditional. Suppression therefore has to
 * happen at emit time, where the level and the position are in hand, which means intercepting the
 * flat arm's own model rather than the selector that chooses it.
 * <p>
 * <strong>It asks about one direction, not sixteen.</strong> {@code MultiPartModel} calls every
 * selected submodel's {@code emitQuads} separately with no shared scratch, so a mask computed here
 * could not be reused by the next arm anyway; the single-direction question costs two probes where a
 * mask costs sixteen.
 *
 * @param variant           the flat arm to wrap -- a vanilla {@code fence_side} variant for a
 *                          cardinal side, upstream's {@code RotatedVariant} (already inside a
 *                          {@link ConstantGeometryVariant}) for an intercardinal one
 * @param direction         the side this arm leaves through, read off the selector's condition by
 *                          {@link fuzs.diagonalfences.client.resources.model.FenceArmVariants#singleTrueDirection}
 * @param diagonalBlockType our fence type, needed to resolve neighbours
 */
public record PitchSuppressedVariant(BlockStateModel.Unbaked variant, EightWayDirection direction,
                                     DiagonalBlockType diagonalBlockType) implements BlockStateModel.Unbaked {

    public PitchSuppressedVariant {
        Objects.requireNonNull(variant, "variant is null");
        Objects.requireNonNull(direction, "direction is null");
        Objects.requireNonNull(diagonalBlockType, "diagonal block type is null");
    }

    @Override
    public void resolveDependencies(Resolver resolver) {
        this.variant.resolveDependencies(resolver);
    }

    @Override
    public BlockStateModel bake(ModelBaker modelBaker) {
        return new PitchSuppressedModel(this.variant.bake(modelBaker), this.direction, this.diagonalBlockType);
    }

    /**
     * Forwards everything to the wrapped model except the two calls that depend on a position, and
     * answers those with "nothing at all" when this side slopes.
     * <p>
     * {@code collectParts} and {@code particleIcon} are position-less and are forwarded unchanged.
     * That is deliberate rather than an oversight: an inventory fence, an item frame and the
     * particle lookup have no neighbours to slope toward, so the honest answer there is the flat arm
     * -- the same call {@link ElevatedArmsModel#collectParts} makes from the other side.
     */
    private static final class PitchSuppressedModel implements BlockStateModel {
        private final BlockStateModel delegate;
        private final EightWayDirection direction;
        private final DiagonalBlockType diagonalBlockType;
        /**
         * Allocated once per baked model. A suppressed arm emits no quads at all, so its geometry is
         * the same everywhere and the key only has to be distinct from the delegate's own.
         */
        private final Object suppressedGeometryKey;

        PitchSuppressedModel(BlockStateModel delegate, EightWayDirection direction, DiagonalBlockType diagonalBlockType) {
            this.delegate = Objects.requireNonNull(delegate, "delegate model is null");
            this.direction = direction;
            this.diagonalBlockType = diagonalBlockType;
            this.suppressedGeometryKey = new SuppressedGeometryKey(delegate, direction);
        }

        @Override
        public void collectParts(RandomSource randomSource, List<BlockModelPart> list) {
            this.delegate.collectParts(randomSource, list);
        }

        @Override
        public TextureAtlasSprite particleIcon() {
            return this.delegate.particleIcon();
        }

        @Override
        public TextureAtlasSprite particleSprite(BlockAndTintGetter blockView, BlockPos pos, BlockState state) {
            return this.delegate.particleSprite(blockView, pos, state);
        }

        @Override
        public void emitQuads(QuadEmitter emitter, BlockAndTintGetter blockView, BlockPos pos, BlockState state, RandomSource random, Predicate<@Nullable Direction> cullTest) {
            if (this.suppressed(blockView, pos)) {
                return;
            }
            this.delegate.emitQuads(emitter, blockView, pos, state, random, cullTest);
        }

        /**
         * The delegate's own key while this arm is drawing, and a sentinel while it is not, so a
         * suppressed fence can never be handed a cached mesh built for an unsuppressed one.
         * <p>
         * A null delegate key still propagates null and the multipart goes uncached, exactly as it
         * did before this wrapper existed -- inventing a key here would claim the delegate's
         * geometry is position-independent when the delegate has just said it is not.
         */
        @Override
        public Object createGeometryKey(BlockAndTintGetter blockView, BlockPos pos, BlockState state, RandomSource random) {
            if (this.suppressed(blockView, pos)) {
                return this.suppressedGeometryKey;
            }
            return this.delegate.createGeometryKey(blockView, pos, state, random);
        }

        private boolean suppressed(BlockAndTintGetter blockView, BlockPos pos) {
            return ElevatedConnections.suppressesFlatArm(new LevelFenceView(blockView, this.diagonalBlockType),
                    pos,
                    this.direction);
        }
    }

    /**
     * Identity-keyed by the arm it replaces. A record rather than a bare {@link Object} so a key
     * turning up in a debugger says which side went missing and why.
     */
    private record SuppressedGeometryKey(BlockStateModel model, EightWayDirection direction) {

    }
}
