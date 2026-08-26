package fuzs.diagonalfences.fabric.client.resources.model;

import fuzs.diagonalblocks.api.v2.block.type.DiagonalBlockType;
import fuzs.diagonalblocks.api.v2.util.EightWayDirection;
import fuzs.diagonalfences.client.resources.model.FenceArmVariants;
import fuzs.diagonalfences.world.level.block.ElevatedArmShear;
import fuzs.diagonalfences.world.level.block.ElevatedConnections;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableMesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The unbaked model for a fence's sloped arms: one flat {@code fence_side} variant per
 * {@link EightWayDirection}, sheared into a rising or falling arm at bake time.
 * <p>
 * This is injected as an <strong>unconditional</strong> multipart selector, which is not a stylistic
 * choice. {@code MultiPartModel} resolves its selector list once per <em>block state</em>
 * ({@code shared.selectModels(blockState)}, then cached), and the pitch is deliberately in no block
 * state -- so a conditional selector would be evaluated on the wrong thing entirely. Only emission
 * is per-position, which is why all the work happens in
 * {@link ElevatedArmsModel#emitQuads}.
 * <p>
 * The arms are <strong>transformed copies of the fence's own side model</strong> rather than quads
 * built from scratch, which is how they inherit textures, UVs, tint indices and resource pack
 * overrides for all thirteen fence types without a line of per-type code.
 *
 * @param armVariants       the flat side variant per direction -- cardinals lifted from the fence's
 *                          own selectors, intercardinals wrapped in upstream's {@code RotatedVariant}
 *                          so they already carry the 45 degree turn about Y and the sqrt(2)
 *                          cross-section stretch
 * @param diagonalBlockType our fence type, needed to resolve neighbours when the mask is computed
 */
public record ElevatedArmsVariant(Map<EightWayDirection, BlockStateModel.Unbaked> armVariants,
                                  DiagonalBlockType diagonalBlockType) implements BlockStateModel.Unbaked {
    /**
     * Seed for the one {@link RandomSource} used to collect parts at bake time.
     * <p>
     * <strong>Known limitation, and it is deliberate.</strong> Baking the arms into fixed meshes
     * collapses any <em>weighted</em> side variant to whichever entry this seed picks. Vanilla and
     * every resource pack we know of give {@code fence_side} a single model, and the Fabric renderer
     * API explicitly asks implementations to "rely on pre-baked meshes as much as possible", so a
     * fixed snapshot is the right trade -- but if a pack ever randomises fence sides, its sloped arms
     * will all pick the same one while its flat arms vary. Fix by moving to a per-emit
     * {@code collectParts} if that ever shows up in a report; do not fix it speculatively.
     */
    static final long BAKE_SEED = 42L;
    /**
     * {@link BlockModelPart#getQuads} is keyed by cull face, and {@code null} holds the quads that
     * are never culled. Missing the {@code null} bucket loses most of a fence arm.
     */
    static final Collection<Direction> VALID_QUAD_FACES = quadFaces();

    public ElevatedArmsVariant {
        Objects.requireNonNull(diagonalBlockType, "diagonal block type is null");
        armVariants = Map.copyOf(armVariants);
        if (armVariants.isEmpty()) {
            throw new IllegalArgumentException("no arm variants; there would be nothing to draw");
        }
    }

    private static Collection<Direction> quadFaces() {
        List<Direction> faces = new ArrayList<>(Arrays.asList(Direction.values()));
        faces.add(null);
        return List.copyOf(faces);
    }

    @Override
    public void resolveDependencies(Resolver resolver) {
        for (BlockStateModel.Unbaked unbaked : this.armVariants.values()) {
            unbaked.resolveDependencies(resolver);
        }
    }

    @Override
    public BlockStateModel bake(ModelBaker modelBaker) {
        // Eager, and on purpose. There are only eight directions times two pitches, the meshes are a
        // handful of quads each, and Mesh is documented thread-safe while MutableMesh is documented
        // not -- Sodium meshes chunk sections on many threads, so building these lazily on the emit
        // path would need synchronisation to buy nothing. This is the opposite call from
        // ElevatedShapeCache deliberately: that key space is 2^24, this one is 16.
        Renderer renderer = Renderer.get();
        Mesh[] arms = new Mesh[FenceArmVariants.ARM_COUNT];
        TextureAtlasSprite particleIcon = null;
        for (Map.Entry<EightWayDirection, BlockStateModel.Unbaked> entry : this.armVariants.entrySet()) {
            EightWayDirection direction = entry.getKey();
            BlockStateModel armModel = entry.getValue().bake(modelBaker);
            if (particleIcon == null) {
                particleIcon = armModel.particleIcon();
            }
            arms[FenceArmVariants.armIndex(direction, ElevatedConnections.PITCH_UP)] = shearedArm(renderer,
                    armModel,
                    direction,
                    ElevatedConnections.PITCH_UP);
            arms[FenceArmVariants.armIndex(direction, ElevatedConnections.PITCH_DOWN)] = shearedArm(renderer,
                    armModel,
                    direction,
                    ElevatedConnections.PITCH_DOWN);
        }
        return new ElevatedArmsModel(arms,
                Objects.requireNonNull(particleIcon, "no particle icon; arm variants cannot be empty"),
                this.diagonalBlockType);
    }

    /**
     * Shears one flat arm into a sloped one and freezes it into an immutable mesh.
     * <p>
     * Two details that look like omissions and are not:
     * <ul>
     * <li><strong>The cull face is cleared on every quad.</strong> {@code fromBakedQuad} copies the
     * baked quad's cull face, and after a shear those assignments are wrong -- a sloped arm's outer
     * end sits half a block up or down, straddling two blocks, and the horizontal neighbour it would
     * be culled against is usually air. Keeping them culls arms away at random. The cost is a little
     * overdraw on faces that were genuinely hidden.
     * <li><strong>Normals are not recomputed.</strong> Upstream's {@code RotatedVariant} has to do
     * that by hand because it rewrites {@code BakedQuad}s directly; we go through a
     * {@link QuadEmitter}, and {@link QuadEmitter#fromBakedQuad} resets vertex normals, so the
     * renderer derives the face normal from the sheared positions itself.
     * </ul>
     */
    private static Mesh shearedArm(Renderer renderer, BlockStateModel armModel, EightWayDirection direction, int pitch) {
        MutableMesh mutableMesh = renderer.mutableMesh();
        QuadEmitter emitter = mutableMesh.emitter();
        for (BlockModelPart blockModelPart : armModel.collectParts(RandomSource.create(BAKE_SEED))) {
            for (Direction face : VALID_QUAD_FACES) {
                for (BakedQuad bakedQuad : blockModelPart.getQuads(face)) {
                    emitter.fromBakedQuad(bakedQuad);
                    for (int vertexIndex = 0; vertexIndex < 4; vertexIndex++) {
                        float x = emitter.x(vertexIndex);
                        float y = emitter.y(vertexIndex);
                        float z = emitter.z(vertexIndex);
                        emitter.pos(vertexIndex, x, y + ElevatedArmShear.riseAt(direction, pitch, x, z), z);
                    }
                    emitter.cullFace(null);
                    emitter.emit();
                }
            }
        }
        return mutableMesh.immutableCopy();
    }
}
