package fuzs.diagonalfences.client.resources.model;

import fuzs.diagonalblocks.api.v2.util.EightWayDirection;
import fuzs.diagonalblocks.impl.client.resources.model.RotatedVariant;
import fuzs.diagonalfences.world.level.block.ElevatedConnections;
import net.minecraft.client.renderer.block.model.BlockModelDefinition;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.block.model.multipart.CombinedCondition;
import net.minecraft.client.renderer.block.model.multipart.Condition;
import net.minecraft.client.renderer.block.model.multipart.KeyValueCondition;
import net.minecraft.client.renderer.block.model.multipart.Selector;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.core.Direction;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The half of the render path that can run without a renderer: reading a fence's side variants out
 * of its multipart model, and deciding where each sloped arm is stored.
 * <p>
 * The multipart definitions here are built by hand rather than loaded from the mod's assets, so the
 * tests state what shape of model is expected instead of agreeing with whatever the assets currently
 * contain.
 */
class FenceArmVariantsTest {

    /**
     * A stand-in for a {@code fence_side} variant. Never baked -- every test here stops before bake,
     * which is exactly the line between what is testable headless and what is not.
     */
    private record NamedVariant(String name) implements BlockStateModel.Unbaked {

        @Override
        public void resolveDependencies(Resolver resolver) {
            // no dependencies to resolve
        }

        @Override
        public BlockStateModel bake(ModelBaker modelBaker) {
            throw new UnsupportedOperationException("baking " + this.name + " needs a live model baker");
        }
    }

    private static Condition propertyIsTrue(String key) {
        return new KeyValueCondition(Map.of(key,
                new KeyValueCondition.Terms(List.of(new KeyValueCondition.Term(Boolean.TRUE.toString(), false)))));
    }

    private static Condition propertyIs(String key, String value, boolean negated) {
        return new KeyValueCondition(Map.of(key,
                new KeyValueCondition.Terms(List.of(new KeyValueCondition.Term(value, negated)))));
    }

    /** A fence multipart the way vanilla ships one: an unconditional post, then four sides. */
    private static BlockModelDefinition.MultiPartDefinition fenceMultiPart() {
        List<Selector> selectors = new ArrayList<>();
        selectors.add(new Selector(Optional.empty(), new NamedVariant("post")));
        for (EightWayDirection direction : EightWayDirection.getCardinalDirections()) {
            selectors.add(new Selector(Optional.of(propertyIsTrue(direction.getSerializedName())),
                    new NamedVariant("side_" + direction.getSerializedName())));
        }
        return new BlockModelDefinition.MultiPartDefinition(selectors);
    }

    @Test
    void everyCardinalSideIsFoundAndThePostIsNot() {
        Map<EightWayDirection, BlockStateModel.Unbaked> cardinalArms = FenceArmVariants.cardinalArms(fenceMultiPart());
        assertEquals(FenceArmVariants.EXPECTED_CARDINAL_ARMS, cardinalArms.size(), "wrong number of sides found");
        for (EightWayDirection direction : EightWayDirection.getCardinalDirections()) {
            assertEquals(new NamedVariant("side_" + direction.getSerializedName()),
                    cardinalArms.get(direction),
                    direction + " resolved to the wrong variant");
        }
    }

    /**
     * The unconditional centre post must not be mistaken for an arm. If it were, a fence would grow
     * a sloped post, and the count check that guards the whole feature would pass on a model it
     * should have rejected.
     */
    @Test
    void theUnconditionalPostIsNotAnArm() {
        BlockModelDefinition.MultiPartDefinition postOnly = new BlockModelDefinition.MultiPartDefinition(
                List.of(new Selector(Optional.empty(), new NamedVariant("post"))));
        assertTrue(FenceArmVariants.cardinalArms(postOnly).isEmpty(), "the post was read as an arm");
    }

    /**
     * Upstream's own intercardinal selectors, and the compound conditions it writes for glass pane
     * posts, name two or more directions. Reading one of those as a plain side would overwrite a real
     * arm with a diagonal one.
     */
    @Test
    void compoundAndIntercardinalConditionsAreNotMistakenForSides() {
        assertNull(FenceArmVariants.singleTrueCardinal(propertyIsTrue("north_east")),
                "an intercardinal condition was read as a cardinal side");
        assertNull(FenceArmVariants.singleTrueCardinal(new CombinedCondition(CombinedCondition.Operation.AND,
                        List.of(propertyIsTrue("north"), propertyIsTrue("south")))),
                "a two-direction condition was read as a single side");
    }

    /**
     * The wider question {@code singleTrueCardinal} is now a filter over. An intercardinal condition
     * is not a side of the base model, but it <em>is</em> an arm -- the one upstream appended -- and
     * a sloped side has to be able to find it in order to suppress it.
     * <p>
     * The two assertions on the same input are the whole point: the same condition must be an arm
     * here and not a side above, or Phase 12 either misses the diagonal rails or builds its sheared
     * arms out of already-rotated ones.
     */
    @Test
    void anIntercardinalConditionIsAnArmEvenThoughItIsNotASide() {
        assertEquals(EightWayDirection.NORTH_EAST,
                FenceArmVariants.singleTrueDirection(propertyIsTrue("north_east")),
                "an appended intercardinal arm could not be identified");
        assertNull(FenceArmVariants.singleTrueCardinal(propertyIsTrue("north_east")),
                "an intercardinal condition was read as a cardinal side");
    }

    /** A compound condition names no single arm either way, so nothing is suppressed on its account. */
    @Test
    void aTwoDirectionConditionNamesNoArm() {
        assertNull(FenceArmVariants.singleTrueDirection(new CombinedCondition(CombinedCondition.Operation.AND,
                List.of(propertyIsTrue("north"), propertyIsTrue("north_east")))));
    }

    /**
     * {@code "false"} and negated terms both appear in real fence and pane models and both mean
     * "no arm on this side". Treating either as an arm points the shear at a side that is not drawn.
     */
    @Test
    void falseAndNegatedTermsDoNotNameAnArm() {
        assertNull(FenceArmVariants.singleTrueCardinal(propertyIs("north", Boolean.FALSE.toString(), false)),
                "a false term was read as an arm");
        assertNull(FenceArmVariants.singleTrueCardinal(propertyIs("north", Boolean.TRUE.toString(), true)),
                "a negated true term was read as an arm");
    }

    @Test
    void aConditionInsideAnOrStillNamesItsDirection() {
        assertEquals(EightWayDirection.NORTH,
                FenceArmVariants.singleTrueCardinal(new CombinedCondition(CombinedCondition.Operation.OR,
                        List.of(propertyIsTrue("north")))),
                "a nested condition lost its direction");
    }

    /**
     * Properties that are not directions -- {@code waterlogged} is on every fence -- must be ignored
     * rather than throwing or blocking the direction beside them.
     */
    @Test
    void nonDirectionPropertiesAreIgnored() {
        assertEquals(EightWayDirection.SOUTH,
                FenceArmVariants.singleTrueCardinal(new CombinedCondition(CombinedCondition.Operation.AND,
                        List.of(propertyIsTrue("south"), propertyIsTrue("waterlogged")))),
                "waterlogged interfered with reading the side");
    }

    @Test
    void allArmsAddsFourIntercardinalsBuiltTheWayUpstreamBuildsThem() {
        Map<EightWayDirection, BlockStateModel.Unbaked> cardinalArms = FenceArmVariants.cardinalArms(fenceMultiPart());
        Map<EightWayDirection, BlockStateModel.Unbaked> allArms = FenceArmVariants.allArms(cardinalArms);

        assertEquals(EightWayDirection.values().length, allArms.size(), "not every direction got an arm");
        for (EightWayDirection cardinal : EightWayDirection.getCardinalDirections()) {
            // cardinals pass through untouched -- a shear is all they need
            assertSame(cardinalArms.get(cardinal), allArms.get(cardinal), cardinal + " was not left alone");

            // and the intercardinal clockwise of it is that same variant, rotated, exactly as
            // MultiPartAppender does it -- if this pairing is wrong the diagonal arms point the wrong way
            EightWayDirection intercardinal = cardinal.rotateClockWise();
            RotatedVariant rotated = assertInstanceOf(RotatedVariant.class,
                    allArms.get(intercardinal),
                    intercardinal + " is not a rotated variant");
            assertSame(cardinalArms.get(cardinal), rotated.variant(), intercardinal + " rotated the wrong side");
            assertEquals(cardinal.toDirection(), rotated.direction(), intercardinal + " rotated by the wrong angle");
        }
    }

    @Test
    void allArmsRefusesANonCardinalInput() {
        assertThrows(IllegalArgumentException.class,
                () -> FenceArmVariants.allArms(Map.of(EightWayDirection.NORTH_EAST, new NamedVariant("diagonal"))),
                "an intercardinal input should not be rotated again");
    }

    /**
     * Every direction and sloped pitch must land in its own slot, and every slot must be inside the
     * array the meshes are stored in. A collision here silently draws one arm in place of another.
     */
    @Test
    void everyArmGetsItsOwnSlot() {
        Set<Integer> seen = new HashSet<>();
        for (EightWayDirection direction : EightWayDirection.values()) {
            for (int pitch : new int[]{ElevatedConnections.PITCH_UP, ElevatedConnections.PITCH_DOWN}) {
                int index = FenceArmVariants.armIndex(direction, pitch);
                assertTrue(index >= 0 && index < FenceArmVariants.ARM_COUNT,
                        direction + " pitch " + pitch + " indexed out of range: " + index);
                assertTrue(seen.add(index), direction + " pitch " + pitch + " collided at index " + index);
            }
        }
        assertEquals(FenceArmVariants.ARM_COUNT, seen.size(), "arm slots are not fully used");
    }

    @ParameterizedTest
    @EnumSource(EightWayDirection.class)
    void thereIsNoSlotForAFlatArm(EightWayDirection direction) {
        assertThrows(IllegalArgumentException.class,
                () -> FenceArmVariants.armIndex(direction, ElevatedConnections.PITCH_NONE),
                direction + " allocated a slot for a flat arm");
    }

    /**
     * A guard against the sprite trap in {@code MultiPartModel.particleSprite}, which reads
     * {@code selectors.getFirst()}. Nothing in this class reorders selectors, and this asserts the
     * post is still what a fence model leads with -- the assumption the injection relies on.
     */
    @Test
    void theFirstSelectorOfAFenceModelIsTheUnconditionalPost() {
        Selector first = fenceMultiPart().selectors().getFirst();
        assertTrue(first.condition().isEmpty(), "the first selector is not the unconditional post");
        assertNotNull(first.variant(), "the post has no variant");
    }

    /**
     * The cull-face buckets a baked part's quads are collected from.
     * <p>
     * These exist because the collection <strong>must be able to hold {@code null}</strong>, and the
     * obvious way to build it cannot. Built with {@code List.copyOf}, this threw
     * {@code NullPointerException} out of a static initializer on every fence model ever
     * transformed, and no test could see it: the constant used to live in the Fabric module, which
     * has no test source set at all.
     */
    @Nested
    class QuadCullFaces {

        @Test
        void holdsTheNullBucketThatCarriesTheUnculledQuads() {
            // The reason this collection cannot be an immutable one. Reverting to List.of/copyOf
            // fails the whole class at <clinit>, which is precisely how it failed in the game.
            assertTrue(FenceArmVariants.QUAD_CULL_FACES.contains(null),
                    "the null bucket holds the quads that are never culled -- most of a fence arm");
        }

        @Test
        void holdsEverySixCullFacesExactlyOnce() {
            List<@Nullable Direction> faces = FenceArmVariants.QUAD_CULL_FACES;
            assertEquals(Direction.values().length + 1, faces.size(), "six cull faces plus the null bucket");
            assertEquals(faces.size(), new HashSet<>(faces).size(), "no bucket collected twice");
            for (Direction direction : Direction.values()) {
                assertTrue(faces.contains(direction), "missing cull face " + direction);
            }
        }

        @Test
        void iteratesWithoutThrowingOnTheNullEntry() {
            // How the constant is actually consumed: iterated, never queried. An immutable copy
            // would survive neither this nor construction.
            int seen = 0;
            for (Direction ignored : FenceArmVariants.QUAD_CULL_FACES) {
                seen++;
            }
            assertEquals(Direction.values().length + 1, seen);
        }

        @Test
        void refusesMutation() {
            assertThrows(UnsupportedOperationException.class,
                    () -> FenceArmVariants.QUAD_CULL_FACES.add(Direction.UP),
                    "a shared static collection must not be writable by its callers");
        }
    }
}
