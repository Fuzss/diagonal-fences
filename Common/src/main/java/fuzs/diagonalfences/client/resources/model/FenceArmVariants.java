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
import net.minecraft.core.Direction;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turning a fence's multipart model into the eight arm variants a sloped arm is built from, and the
 * indexing those arms are stored under.
 * <p>
 * This lives in Common, away from the Fabric renderer code that consumes it, for one reason: it is
 * the part of the render path that can be tested. It touches only vanilla model classes and
 * upstream's {@code RotatedVariant}, so it runs headless -- while everything that reaches
 * {@code QuadEmitter} needs a live renderer and does not. Reaching for a Fabric test source set to
 * cover it would have bought a second test framework and a mapping problem for less coverage.
 * <p>
 * Nothing here is loader-specific, but nothing here runs on a dedicated server either; it is client
 * code that happens to be portable.
 *
 * @see fuzs.diagonalfences.world.level.block.ElevatedArmShear
 */
public final class FenceArmVariants {
    /** Eight directions, two sloped pitches. {@link ElevatedConnections#PITCH_NONE} needs no arm. */
    public static final int ARM_COUNT = 16;
    /** Cardinal sides a fence multipart is expected to declare, one per horizontal face. */
    public static final int EXPECTED_CARDINAL_ARMS = 4;
    /**
     * Every key {@code BlockModelPart#getQuads} buckets its quads under, <strong>including
     * {@code null}</strong>, which holds the quads that are never culled -- on a fence arm that is
     * most of them. Iterate this to collect a part's whole geometry; miss the {@code null} bucket and
     * an arm comes out nearly empty.
     * <p>
     * <strong>It must be a null-tolerant collection, and that is not a detail.</strong> Built with
     * {@link java.util.List#copyOf} instead, this threw {@code NullPointerException} out of a static
     * initializer on every fence model ever transformed -- see {@code gotchas.md} (2026-08-26). The
     * immutable collections reject a null element on construction <em>and</em> throw from
     * {@code contains(null)}, so neither {@code List.of} nor {@code List.copyOf} can ever hold this.
     * <p>
     * Lives in Common rather than beside its only caller for the reason this class exists: it touches
     * nothing but {@link Direction}, so it is the rare piece of the render path a headless test can
     * load.
     */
    public static final List<@Nullable Direction> QUAD_CULL_FACES = quadCullFaces();

    private FenceArmVariants() {
        // static utility
    }

    private static List<@Nullable Direction> quadCullFaces() {
        List<@Nullable Direction> faces = new ArrayList<>(Arrays.asList(Direction.values()));
        faces.add(null);
        return Collections.unmodifiableList(faces);
    }

    /**
     * Where the arm for one direction and pitch is stored.
     * <p>
     * {@link ElevatedConnections#PITCH_UP} is {@code 1} and {@link ElevatedConnections#PITCH_DOWN} is
     * {@code 2}, so {@code pitch - 1} is the sub-index, and
     * {@link ElevatedConnections#directionIndex} already numbers the directions {@code 0..7}.
     *
     * @throws IllegalArgumentException if {@code pitch} is not a sloped pitch. A flat arm is drawn by
     *                                  the multipart's own selectors; silently mapping it into a slot
     *                                  here would draw a second arm on top of one that already exists.
     */
    public static int armIndex(EightWayDirection direction, int pitch) {
        if (pitch != ElevatedConnections.PITCH_UP && pitch != ElevatedConnections.PITCH_DOWN) {
            throw new IllegalArgumentException("no sloped arm exists for pitch " + pitch);
        }
        return ElevatedConnections.directionIndex(direction) * 2 + (pitch - 1);
    }

    /**
     * The fence's four {@code fence_side} variants, keyed by the direction whose property switches
     * them on.
     * <p>
     * Read this from the <strong>original</strong> model, before upstream appends its intercardinal
     * selectors. Reading afterwards would work, but it would mean telling upstream's additions apart
     * from the base model's, and the condition tree does not draw that distinction.
     *
     * @return a map that is empty, or short of {@link #EXPECTED_CARDINAL_ARMS}, when the model is not
     *         shaped like a fence -- the caller is expected to check and fail soft, not to assume
     */
    public static Map<EightWayDirection, BlockStateModel.Unbaked> cardinalArms(BlockModelDefinition.MultiPartDefinition multiPart) {
        Map<EightWayDirection, BlockStateModel.Unbaked> armVariants = new EnumMap<>(EightWayDirection.class);
        for (Selector selector : multiPart.selectors()) {
            Condition condition = selector.condition().orElse(null);
            if (condition == null) {
                // the centre post, which is unconditional and is not an arm
                continue;
            }
            EightWayDirection direction = singleTrueCardinal(condition);
            if (direction != null) {
                armVariants.putIfAbsent(direction, selector.variant());
            }
        }
        return armVariants;
    }

    /**
     * Adds the four intercardinal arms to the four cardinal ones.
     * <p>
     * They are built exactly the way upstream's {@code MultiPartAppender} builds its own, so the two
     * cannot disagree about what a diagonal arm looks like: for a cardinal {@code D},
     * {@code D.rotateClockWise()} gets {@code new RotatedVariant(sideVariant(D), D.toDirection())}.
     * That wrapper already carries the 45 degree turn about Y and the {@code sqrt(2)} cross-section
     * stretch, so the pitch shear composes on top of it rather than re-deriving it.
     * <p>
     * Constructing them here rather than fishing upstream's appended selectors back out of the list
     * keeps this independent of the order it appends them in.
     */
    public static Map<EightWayDirection, BlockStateModel.Unbaked> allArms(Map<EightWayDirection, BlockStateModel.Unbaked> cardinalArms) {
        Map<EightWayDirection, BlockStateModel.Unbaked> armVariants = new EnumMap<>(EightWayDirection.class);
        armVariants.putAll(cardinalArms);
        for (Map.Entry<EightWayDirection, BlockStateModel.Unbaked> entry : cardinalArms.entrySet()) {
            EightWayDirection cardinal = entry.getKey();
            if (!cardinal.isCardinal()) {
                throw new IllegalArgumentException(cardinal + " is not a cardinal direction");
            }
            armVariants.put(cardinal.rotateClockWise(),
                    new RotatedVariant(entry.getValue(), cardinal.toDirection()));
        }
        return armVariants;
    }

    /**
     * The single direction a condition requires to be {@code true}, cardinal or intercardinal, or
     * {@code null} if it does not name exactly one.
     * <p>
     * Requiring <em>exactly one</em> is what keeps this from matching the compound conditions
     * upstream introduces for glass pane centre posts -- those name two or more directions, so they
     * fall out here instead of being mistaken for a plain side.
     * <p>
     * The intercardinal half of this is what identifies the arms upstream <em>appends</em>: its
     * {@code MultiPartAppender} copies a side selector's condition with the key rotated clockwise,
     * so a {@code north} arm becomes a {@code north_east} one and still names exactly one direction.
     * That is the hook a sloped side needs to find the flat arm it must hide.
     */
    @Nullable
    public static EightWayDirection singleTrueDirection(Condition condition) {
        Set<EightWayDirection> directions = EnumSet.noneOf(EightWayDirection.class);
        collectTrueDirections(condition, directions);
        return directions.size() == 1 ? directions.iterator().next() : null;
    }

    /**
     * {@link #singleTrueDirection} narrowed to a plain side of the base model, which is what
     * {@link #cardinalArms} is reading for -- an intercardinal here would mean upstream's selectors
     * were already appended, and taking one as a side would build the sloped arms out of arms that
     * are already rotated.
     */
    @Nullable
    static EightWayDirection singleTrueCardinal(Condition condition) {
        EightWayDirection direction = singleTrueDirection(condition);
        return direction != null && direction.isCardinal() ? direction : null;
    }

    /**
     * Walks a condition tree collecting every direction it requires to be {@code true}. A negated
     * term does not count, and neither does {@code "false"} -- both appear in real fence and pane
     * models and both mean "no arm here".
     */
    private static void collectTrueDirections(Condition condition, Set<EightWayDirection> directions) {
        if (condition instanceof KeyValueCondition(Map<String, KeyValueCondition.Terms> tests)) {
            for (Map.Entry<String, KeyValueCondition.Terms> entry : tests.entrySet()) {
                EightWayDirection direction = EightWayDirection.byName(entry.getKey());
                if (direction == null) {
                    continue;
                }
                for (KeyValueCondition.Term term : entry.getValue().entries()) {
                    if (!term.negated() && Boolean.TRUE.toString().equals(term.value())) {
                        directions.add(direction);
                    }
                }
            }
        } else if (condition instanceof CombinedCondition(
                CombinedCondition.Operation ignored, List<Condition> terms
        )) {
            for (Condition term : terms) {
                collectTrueDirections(term, directions);
            }
        }
        // Any other Condition implementation names no directions we can act on. Upstream's
        // ConditionHelper hits the same wall and logs it; treating it as "no arm here" is the answer
        // that fails soft, leaving the fence with its stock arms.
    }
}
