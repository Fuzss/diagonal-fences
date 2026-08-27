package fuzs.diagonalfences.world.level.block;

import fuzs.diagonalblocks.api.v2.util.EightWayDirection;
import fuzs.diagonalfences.DiagonalFences;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Shapes for a fence that has sloped arms, keyed on
 * {@code flatIndex (8 bits) | pitchMask << 8} and built on first use.
 * <p>
 * <strong>Lazy is the whole point.</strong> Upstream's
 * {@link fuzs.diagonalblocks.api.v2.block.StarShapeProvider#constructStateShapes} is eager over its
 * 2^8 flat combinations, and eagerly enumerating this key space instead is exactly the objection that
 * got this feature rejected upstream in the first place. Sloped arms are derived, never persisted, so
 * there is no state count to enumerate -- only the combinations a world actually contains.
 * <p>
 * A fence with no sloped arm at all -- which is nearly all of them -- never touches the cache and gets
 * the upstream flat shape instance straight back.
 */
public final class ElevatedShapeCache {
    /** Flat state shapes, one per combination of the eight flat arm properties. */
    public static final int FLAT_INDEX_COUNT = 1 << ElevatedConnections.FLAT_INDEX_WIDTH;
    /**
     * Entries kept before the cache stops growing.
     * <p>
     * The reachable key space is 4^8 = 65,536: every direction independently flat, up, down or
     * absent. Nothing in a real world approaches that -- a fence needs a neighbour placed just so for
     * every arm it carries -- but "nothing realistic does" is not a bound, and an unbounded cache on
     * the collision path is a memory leak with extra steps. Past the cap shapes are still returned,
     * just rebuilt each time: the failure mode is slow, never wrong.
     */
    public static final int DEFAULT_MAX_CACHED_SHAPES = 4096;

    private static final Logger LOGGER = LogManager.getLogger(DiagonalFences.MOD_NAME);
    private static final int PITCH_MASK_SHIFT = ElevatedConnections.FLAT_INDEX_WIDTH;
    private static final int FLAT_INDEX_MASK = FLAT_INDEX_COUNT - 1;
    private static final int PITCH_MASK_LIMIT = 1 << ElevatedConnections.PITCH_MASK_WIDTH;
    /** One up arm and one down arm per direction. */
    private static final int ARM_COUNT = 2 * 8;

    private final VoxelShape[] flatShapes;
    /** Indexed by {@link #armIndex}. */
    private final VoxelShape[] pitchedArmShapes = new VoxelShape[ARM_COUNT];
    /** Indexed by {@link #armIndex}. */
    private final Vec3[][] pitchedArmEdges = new Vec3[ARM_COUNT][];
    private final int maxCachedShapes;

    private final Object writeLock = new Object();
    /**
     * Copy-on-write: replaced wholesale under {@link #writeLock}, never mutated after publication, so
     * readers on the collision and meshing paths need no lock of their own.
     */
    private volatile Int2ObjectMap<VoxelShape> cache = new Int2ObjectOpenHashMap<>();
    /** Guarded by {@link #writeLock}. */
    private boolean overflowLogged;

    /**
     * @param flatShapes      upstream's flat state shapes, exactly {@link #FLAT_INDEX_COUNT} of them,
     *                        indexed the way {@code StarShapeProvider#makeIndex} indexes them
     * @param extensionWidth  half-width of an arm's cross-section, in pixels
     * @param extensionBottom bottom of an arm at the block centre, in pixels
     * @param extensionTop    top of an arm at the block centre, in pixels
     * @param stretchWidth    see {@link ElevatedShapes#pitchedArmEdges}
     */
    public ElevatedShapeCache(VoxelShape[] flatShapes, float extensionWidth, float extensionBottom, float extensionTop, boolean stretchWidth) {
        this(flatShapes, extensionWidth, extensionBottom, extensionTop, stretchWidth, DEFAULT_MAX_CACHED_SHAPES);
    }

    ElevatedShapeCache(VoxelShape[] flatShapes, float extensionWidth, float extensionBottom, float extensionTop, boolean stretchWidth, int maxCachedShapes) {
        if (flatShapes.length != FLAT_INDEX_COUNT) {
            throw new IllegalArgumentException(
                    "expected " + FLAT_INDEX_COUNT + " flat shapes, got " + flatShapes.length);
        }
        if (maxCachedShapes < 1) {
            throw new IllegalArgumentException("cache must hold at least one shape, got " + maxCachedShapes);
        }
        this.flatShapes = flatShapes.clone();
        this.maxCachedShapes = maxCachedShapes;
        // 16 arms, built once. Only their combinations are lazy.
        for (EightWayDirection direction : EightWayDirection.values()) {
            for (int pitch : new int[]{ElevatedConnections.PITCH_UP, ElevatedConnections.PITCH_DOWN}) {
                int armIndex = armIndex(direction, pitch);
                this.pitchedArmShapes[armIndex] = ElevatedShapes.pitchedArmCollisionShape(extensionWidth,
                        extensionBottom,
                        extensionTop,
                        direction,
                        pitch);
                this.pitchedArmEdges[armIndex] = ElevatedShapes.pitchedArmEdges(extensionWidth,
                        extensionBottom,
                        extensionTop,
                        direction,
                        pitch,
                        stretchWidth);
            }
        }
    }

    /**
     * @param flatIndex upstream's flat arm index, {@code 0..255}, <strong>already filtered through
     *                  {@link ElevatedConnections#suppressFlatArms}</strong> by the caller. This
     *                  class deliberately stays a pure {@code int -> shape} function with no level
     *                  access, so the one question suppression needs -- does that flat arm reach a
     *                  rail? -- has to be answered before the key is built. Two states that differ
     *                  only in a suppressed arm therefore arrive here identical and share one entry.
     * @param pitchMask a mask from {@link ElevatedConnections#computePitchMask}
     * @return the shape for that combination, built on first use
     * @throws IllegalArgumentException if either argument is out of range. Bounds are checked on the
     *                                  collision path deliberately: a bad index would otherwise read a
     *                                  neighbouring shape and produce silently wrong collision, which
     *                                  is far harder to find than a thrown exception.
     */
    public VoxelShape getShape(int flatIndex, int pitchMask) {
        if (flatIndex < 0 || flatIndex >= FLAT_INDEX_COUNT) {
            throw new IllegalArgumentException("flat index " + flatIndex + " outside 0.." + (FLAT_INDEX_COUNT - 1));
        }
        if (pitchMask < 0 || pitchMask >= PITCH_MASK_LIMIT) {
            throw new IllegalArgumentException("pitch mask " + pitchMask + " outside 0.." + (PITCH_MASK_LIMIT - 1));
        }
        if (pitchMask == ElevatedConnections.EMPTY_PITCH_MASK) {
            return this.flatShapes[flatIndex];
        }
        int key = flatIndex | (pitchMask << PITCH_MASK_SHIFT);
        VoxelShape voxelShape = this.cache.get(key);
        return voxelShape != null ? voxelShape : this.computeAndCache(key);
    }

    /** How many combined shapes are currently held. Zero until a sloped fence is actually queried. */
    public int cachedShapeCount() {
        return this.cache.size();
    }

    private VoxelShape computeAndCache(int key) {
        synchronized (this.writeLock) {
            VoxelShape voxelShape = this.cache.get(key);
            if (voxelShape != null) {
                return voxelShape;
            }
            voxelShape = this.buildShape(key & FLAT_INDEX_MASK, key >>> PITCH_MASK_SHIFT);
            if (this.cache.size() >= this.maxCachedShapes) {
                if (!this.overflowLogged) {
                    this.overflowLogged = true;
                    LOGGER.warn(
                            "Elevated fence shape cache is full at {} entries; shapes beyond it are rebuilt on every query. Collision and outlines stay correct, but this costs performance -- please report the build that reached this.",
                            this.maxCachedShapes);
                }
                return voxelShape;
            }
            Int2ObjectMap<VoxelShape> newCache = new Int2ObjectOpenHashMap<>(this.cache);
            newCache.put(key, voxelShape);
            this.cache = newCache;
            return voxelShape;
        }
    }

    private VoxelShape buildShape(int flatIndex, int pitchMask) {
        VoxelShape flatShape = this.flatShapes[flatIndex];
        VoxelShape collisionShape = flatShape;
        List<Vec3[]> edges = new ArrayList<>();
        for (EightWayDirection direction : EightWayDirection.values()) {
            int pitch = ElevatedConnections.pitchFor(pitchMask, direction);
            if (pitch == ElevatedConnections.PITCH_NONE) {
                continue;
            }
            int armIndex = armIndex(direction, pitch);
            collisionShape = Shapes.joinUnoptimized(collisionShape, this.pitchedArmShapes[armIndex], BooleanOp.OR);
            edges.add(this.pitchedArmEdges[armIndex]);
        }
        return new ElevatedFenceShape(collisionShape.optimize(), flatShape, edges);
    }

    /**
     * Slot for one arm in {@link #pitchedArmShapes} and {@link #pitchedArmEdges}: two slots per
     * direction, up first.
     *
     * @throws IllegalArgumentException via {@link ElevatedShapes#riseSign} if {@code pitch} is not a
     *                                  sloped pitch, so a stray {@link ElevatedConnections#PITCH_NONE}
     *                                  cannot quietly index the previous direction's down arm
     */
    private static int armIndex(EightWayDirection direction, int pitch) {
        int riseSign = ElevatedShapes.riseSign(pitch);
        return 2 * ElevatedConnections.directionIndex(direction) + (riseSign > 0 ? 0 : 1);
    }
}
