package fuzs.diagonalfences.fabric.client.resources.model;

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
 * Gives a position-independent model a geometry key it was missing.
 * <p>
 * <strong>Why this exists.</strong> {@code MultiPartModel.createGeometryKey} asks every selected
 * submodel for a subkey and returns {@code null} the moment one of them returns {@code null}.
 * Upstream's {@code RotatedVariant} bakes an anonymous {@link BlockStateModel} that overrides only
 * {@code collectParts} and {@code particleIcon}, so it inherits the interface default -- {@code null}
 * -- and <em>every diagonal fence in the game therefore has no geometry key at all</em>, today,
 * before this mod changes anything. Implementing a key on our own sloped arms would have been dead
 * code without also fixing that; the two together are what make the key mean something.
 * <p>
 * The key is a constant per baked model, which is exactly right: the wrapped model's geometry does
 * not depend on level or position, so one baked instance always emits the same quads.
 * <p>
 * <strong>Scope, honestly.</strong> Sodium does not read geometry keys -- it caches the whole chunk
 * section mesh instead, and {@code createGeometryKey} has zero references in it. This is a win for
 * the vanilla/Indigo renderer only. Do not describe it as a Sodium optimisation.
 *
 * @param variant the position-independent variant to wrap
 */
public record ConstantGeometryVariant(BlockStateModel.Unbaked variant) implements BlockStateModel.Unbaked {

    public ConstantGeometryVariant {
        Objects.requireNonNull(variant, "variant is null");
    }

    @Override
    public void resolveDependencies(Resolver resolver) {
        this.variant.resolveDependencies(resolver);
    }

    @Override
    public BlockStateModel bake(ModelBaker modelBaker) {
        return new ConstantGeometryModel(this.variant.bake(modelBaker));
    }

    /**
     * Forwards everything to the wrapped model and adds the one method it was missing.
     * <p>
     * {@code emitQuads} and {@code particleSprite} are forwarded explicitly rather than left to the
     * interface defaults because Fabric's documentation requires a delegating model to pass those
     * through -- inheriting the defaults would quietly cut the wrapped model off from any level
     * aware behaviour it might grow later.
     */
    private static final class ConstantGeometryModel implements BlockStateModel {
        private final BlockStateModel delegate;
        /**
         * Allocated once per baked model and never again, so it is stable across every call for
         * every position -- which is the whole contract of a geometry key for a static model.
         */
        private final Object geometryKey;

        ConstantGeometryModel(BlockStateModel delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate model is null");
            this.geometryKey = new ConstantGeometryKey(delegate);
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
        public void emitQuads(QuadEmitter emitter, BlockAndTintGetter blockView, BlockPos pos, BlockState state, RandomSource random, Predicate<@Nullable Direction> cullTest) {
            this.delegate.emitQuads(emitter, blockView, pos, state, random, cullTest);
        }

        @Override
        public TextureAtlasSprite particleSprite(BlockAndTintGetter blockView, BlockPos pos, BlockState state) {
            return this.delegate.particleSprite(blockView, pos, state);
        }

        @Override
        public Object createGeometryKey(BlockAndTintGetter blockView, BlockPos pos, BlockState state, RandomSource random) {
            return this.geometryKey;
        }
    }

    /**
     * Identity-keyed by the baked model it describes. A record rather than a bare {@link Object} so
     * that a key turning up in a debugger says what it belongs to.
     */
    private record ConstantGeometryKey(BlockStateModel model) {

    }
}
