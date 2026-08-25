package fuzs.diagonalfences.world.level.block;

import fuzs.diagonalblocks.api.v2.util.EightWayDirection;
import fuzs.diagonalblocks.impl.world.phys.shapes.VoxelCollection;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The lazy shape cache: what it stores, what it deliberately does not store, and that it stays bounded.
 * <p>
 * Flat shapes here are plain post boxes rather than upstream's real state shapes. The cache treats the
 * flat shape as an opaque input it unions and delegates to, so a stand-in exercises every path; what
 * the real flat shapes look like is {@link ElevatedShapesTest}'s and Phase 5's problem, not this one's.
 */
class ElevatedShapeCacheTest {
    private static final float EXTENSION_WIDTH = 2.0F;
    private static final float EXTENSION_BOTTOM = 0.0F;
    private static final float EXTENSION_TOP = 16.0F;

    /** A pitch mask carrying a single up arm to the north. */
    private static final int NORTH_UP = ElevatedConnections.withPitch(0,
            EightWayDirection.NORTH,
            ElevatedConnections.PITCH_UP);

    private static VoxelShape[] flatShapes() {
        VoxelShape[] flatShapes = new VoxelShape[ElevatedShapeCache.FLAT_INDEX_COUNT];
        for (int i = 0; i < flatShapes.length; i++) {
            // distinct instances, so an indexing mistake cannot pass by returning an equal shape
            flatShapes[i] = ShapeProbe.box(6.0, 0.0, 6.0, 10.0, 16.0, 10.0);
        }
        return flatShapes;
    }

    private static ElevatedShapeCache cache() {
        return new ElevatedShapeCache(flatShapes(), EXTENSION_WIDTH, EXTENSION_BOTTOM, EXTENSION_TOP, true);
    }

    private static ElevatedShapeCache cache(VoxelShape[] flatShapes, int maxCachedShapes) {
        return new ElevatedShapeCache(flatShapes,
                EXTENSION_WIDTH,
                EXTENSION_BOTTOM,
                EXTENSION_TOP,
                true,
                maxCachedShapes);
    }

    @Test
    void aFenceWithNoSlopeGetsTheFlatShapeItselfAndCostsNothing() {
        VoxelShape[] flatShapes = flatShapes();
        ElevatedShapeCache cache = cache(flatShapes, ElevatedShapeCache.DEFAULT_MAX_CACHED_SHAPES);
        for (int flatIndex = 0; flatIndex < ElevatedShapeCache.FLAT_INDEX_COUNT; flatIndex++) {
            assertSame(flatShapes[flatIndex],
                    cache.getShape(flatIndex, 0),
                    "flat index " + flatIndex + " should come straight back");
        }
        assertEquals(0, cache.cachedShapeCount(), "an unsloped fence must not populate the cache at all");
    }

    @Test
    void aRepeatedKeyReturnsTheIdenticalInstance() {
        ElevatedShapeCache cache = cache();
        VoxelShape first = cache.getShape(0, NORTH_UP);
        VoxelShape second = cache.getShape(0, NORTH_UP);
        assertSame(first, second, "the second query should hit the cache, not rebuild");
        assertEquals(1, cache.cachedShapeCount());
    }

    @Test
    void distinctKeysGetDistinctShapes() {
        ElevatedShapeCache cache = cache();
        int southDown = ElevatedConnections.withPitch(0, EightWayDirection.SOUTH, ElevatedConnections.PITCH_DOWN);
        VoxelShape northUpAtZero = cache.getShape(0, NORTH_UP);
        VoxelShape southDownAtZero = cache.getShape(0, southDown);
        VoxelShape northUpAtOne = cache.getShape(1, NORTH_UP);
        assertNotSame(northUpAtZero, southDownAtZero, "a different pitch mask is a different shape");
        assertNotSame(northUpAtZero, northUpAtOne, "a different flat index is a different shape");
        assertEquals(3, cache.cachedShapeCount());
    }

    /**
     * The behavioural claim of the whole feature, at unit level: a sloped fence collides where a flat
     * one does not. This is the assertion that goes red if the arm is ever dropped from the union.
     */
    @Test
    void aSlopedShapeOccupiesSpaceTheFlatShapeLeavesEmpty() {
        VoxelShape[] flatShapes = flatShapes();
        ElevatedShapeCache cache = cache(flatShapes, ElevatedShapeCache.DEFAULT_MAX_CACHED_SHAPES);
        VoxelShape sloped = cache.getShape(0, NORTH_UP);

        // the north arm's outermost step, half a block above the flat rail level
        double z = ElevatedShapes.edgeCoordinate(EightWayDirection.NORTH.getZ());
        double y = EXTENSION_BOTTOM + ElevatedShapes.HALF_RISE + 0.5;
        ShapeProbe.assertTouches(sloped, ElevatedShapes.CENTRE, y, z, "sloped shape");
        ShapeProbe.assertDoesNotTouch(flatShapes[0], ElevatedShapes.CENTRE, y, z, "flat shape");
    }

    @Test
    void aSlopedOutlineDrawsTheFlatEdgesPlusTwelvePerArm() {
        VoxelShape[] flatShapes = flatShapes();
        ElevatedShapeCache cache = cache(flatShapes, ElevatedShapeCache.DEFAULT_MAX_CACHED_SHAPES);
        int flatEdges = ShapeProbe.countEdges(flatShapes[0]);

        assertEquals(flatEdges + 12, ShapeProbe.countEdges(cache.getShape(0, NORTH_UP)), "one sloped arm");

        int twoArms = ElevatedConnections.withPitch(NORTH_UP,
                EightWayDirection.SOUTH_EAST,
                ElevatedConnections.PITCH_DOWN);
        assertEquals(flatEdges + 24, ShapeProbe.countEdges(cache.getShape(0, twoArms)), "two sloped arms");
    }

    /**
     * Upstream's {@code DestroyEffectsHelper} only emits block-breaking particles when the shape is a
     * {@link VoxelCollection}. Losing that would silently downgrade particles for exactly the fences
     * this feature creates, and nothing else in the game would complain.
     */
    @Test
    void aSlopedShapeIsStillAVoxelCollection() {
        assertInstanceOf(VoxelCollection.class, cache().getShape(0, NORTH_UP));
    }

    @Test
    void aCachedShapeRefusesToBeMutated() {
        VoxelShape sloped = cache().getShape(0, NORTH_UP);
        VoxelCollection voxelCollection = assertInstanceOf(VoxelCollection.class, sloped);
        assertThrows(UnsupportedOperationException.class,
                () -> voxelCollection.addVoxelShape(ShapeProbe.box(0.0, 0.0, 0.0, 16.0, 16.0, 16.0), Shapes.empty()),
                "a shared cached shape must not be extendable");
        assertSame(sloped, voxelCollection.optimize(), "optimize must not rewrite a shared shape");
    }

    /**
     * The cap guard: past it the cache stops growing but keeps handing back correct shapes. A cache
     * that silently returned null or a stale shape once full would be far worse than a slow one.
     */
    @Test
    void theCacheStopsGrowingAtItsCapAndStillReturnsCorrectShapes() {
        ElevatedShapeCache cache = cache(flatShapes(), 2);
        double z = ElevatedShapes.edgeCoordinate(EightWayDirection.NORTH.getZ());
        double y = EXTENSION_BOTTOM + ElevatedShapes.HALF_RISE + 0.5;
        for (int flatIndex = 0; flatIndex < 5; flatIndex++) {
            VoxelShape sloped = cache.getShape(flatIndex, NORTH_UP);
            ShapeProbe.assertTouches(sloped, ElevatedShapes.CENTRE, y, z, "shape past the cap, flat index " + flatIndex);
        }
        assertEquals(2, cache.cachedShapeCount(), "the cache must stop at its cap");
    }

    @Test
    void keysOutsideTheirRangeAreRejectedRatherThanReadingTheNeighbouringShape() {
        ElevatedShapeCache cache = cache();
        assertThrows(IllegalArgumentException.class, () -> cache.getShape(-1, NORTH_UP));
        assertThrows(IllegalArgumentException.class,
                () -> cache.getShape(ElevatedShapeCache.FLAT_INDEX_COUNT, NORTH_UP));
        assertThrows(IllegalArgumentException.class, () -> cache.getShape(0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> cache.getShape(0, 1 << ElevatedConnections.PITCH_MASK_WIDTH));
    }

    @Test
    void aCacheCannotBeBuiltOnTheWrongNumberOfFlatShapes() {
        VoxelShape[] tooFew = new VoxelShape[ElevatedShapeCache.FLAT_INDEX_COUNT - 1];
        assertThrows(IllegalArgumentException.class, () -> cache(tooFew, 16));
        assertThrows(IllegalArgumentException.class, () -> cache(flatShapes(), 0));
    }
}
