package applygray.mattermanipulator.uplink;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Future;

import applygray.mattermanipulator.inventory.ResourceRequirements;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;

import ae2.api.crafting.IPatternDetails;
import ae2.api.crafting.PatternDetailsHelper;
import ae2.api.networking.crafting.ICraftingLink;
import ae2.api.networking.crafting.ICraftingPlan;
import ae2.api.networking.crafting.ICraftingRequester;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import ae2.api.storage.StorageHelper;
import org.jetbrains.annotations.Nullable;

/**
 * One persisted Quantum Uplink material plan, expressed to AE2 as advertised processing patterns.
 *
 * <p>Every plan is submitted automatically exactly once. A plan whose materials cannot be gathered right away stays
 * advertised instead of failing, so the player can top the network up and craft its order token by hand later. Only
 * the encoded patterns, their state and submitted links are serialized; in-flight calculations are restarted after a
 * world reload because AE2 calculation futures are intentionally not persistent.</p>
 */
final class UplinkCraftingRequest {

    /** Each pattern carries up to {@link UplinkPlanToken#MAX_PATTERN_INPUTS} distinct materials. */
    static final int MAX_PATTERNS_PER_REQUEST = 16;
    private static final int MAX_REQUEST_NAME_LENGTH = 96;
    private static final String REQUESTER_MOST_KEY = "RequesterMost";
    private static final String REQUESTER_LEAST_KEY = "RequesterLeast";
    private static final String REQUEST_NAME_KEY = "RequestName";
    private static final String DISCRIMINATOR_KEY = "Discriminator";
    private static final String PLANS_KEY = "Plans";
    private static final String TOKEN_KEY = "Token";
    private static final String PATTERN_KEY = "Pattern";
    private static final String STATE_KEY = "State";
    private static final String LINK_KEY = "Link";

    private final UUID requesterId;
    private final String requestName;
    private final long discriminator;
    private final List<Plan> plans;

    private UplinkCraftingRequest(UUID requesterId, String requestName, long discriminator, List<Plan> plans) {
        this.requesterId = Objects.requireNonNull(requesterId, "requesterId");
        this.requestName = boundedName(requestName);
        this.discriminator = discriminator;
        this.plans = List.copyOf(plans);
    }

    /** Builds the advertised patterns for one operation's material list. */
    static UplinkCraftingRequest create(UUID requesterId, String requestName, long discriminator,
                                        ResourceRequirements requirements) {
        Objects.requireNonNull(requesterId, "requesterId");
        Objects.requireNonNull(requirements, "requirements");

        List<List<GenericStack>> chunks = UplinkPlanToken.split(requirements);
        if (chunks.size() > MAX_PATTERNS_PER_REQUEST) {
            throw new IllegalArgumentException("The plan needs more distinct materials than one request can express");
        }

        String planName = boundedName(requestName);
        List<Plan> plans = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            ItemStack token = UplinkPlanToken.createToken(planName, discriminator, index + 1, chunks.size());
            plans.add(new Plan(token, UplinkPlanToken.encodePattern(chunks.get(index), token), PlanState.PENDING,
                    null));
        }
        return new UplinkCraftingRequest(requesterId, planName, discriminator, plans);
    }

    @Nullable
    static UplinkCraftingRequest readFromNbt(NBTTagCompound data, ICraftingRequester requester) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(requester, "requester");
        if (!data.hasKey(REQUESTER_MOST_KEY, Constants.NBT.TAG_LONG) ||
                !data.hasKey(REQUESTER_LEAST_KEY, Constants.NBT.TAG_LONG) ||
                !data.hasKey(PLANS_KEY, Constants.NBT.TAG_LIST)) {
            return null;
        }

        UUID requesterId = new UUID(data.getLong(REQUESTER_MOST_KEY), data.getLong(REQUESTER_LEAST_KEY));
        NBTTagList serializedPlans = data.getTagList(PLANS_KEY, Constants.NBT.TAG_COMPOUND);
        List<Plan> plans = new ArrayList<>(Math.min(serializedPlans.tagCount(), MAX_PATTERNS_PER_REQUEST));
        for (int index = 0; index < serializedPlans.tagCount() && plans.size() < MAX_PATTERNS_PER_REQUEST; index++) {
            Plan plan = Plan.readFromNbt(serializedPlans.getCompoundTagAt(index), requester);
            if (plan != null) plans.add(plan);
        }
        return plans.isEmpty() ? null : new UplinkCraftingRequest(requesterId, data.getString(REQUEST_NAME_KEY),
                data.getLong(DISCRIMINATOR_KEY), plans);
    }

    UUID requesterId() {
        return requesterId;
    }

    String requestName() {
        return requestName;
    }

    long discriminator() {
        return discriminator;
    }

    List<Plan> plans() {
        return plans;
    }

    /** A request is finished once every one of its patterns has delivered its materials. */
    boolean isTerminal() {
        return plans.stream().allMatch(plan -> plan.state() == PlanState.COMPLETE);
    }

    boolean owns(ICraftingLink link) {
        return plans.stream().anyMatch(plan -> plan.matches(link));
    }

    @Nullable
    Plan findPlan(IPatternDetails patternDetails) {
        for (Plan plan : plans) {
            if (plan.matches(patternDetails)) return plan;
        }
        return null;
    }

    void cancel() {
        for (Plan plan : plans) {
            plan.cancel();
        }
    }

    NBTTagCompound writeToNbt() {
        NBTTagCompound data = new NBTTagCompound();
        data.setLong(REQUESTER_MOST_KEY, requesterId.getMostSignificantBits());
        data.setLong(REQUESTER_LEAST_KEY, requesterId.getLeastSignificantBits());
        data.setString(REQUEST_NAME_KEY, requestName);
        data.setLong(DISCRIMINATOR_KEY, discriminator);
        NBTTagList serializedPlans = new NBTTagList();
        for (Plan plan : plans) {
            serializedPlans.appendTag(plan.writeToNbt());
        }
        data.setTag(PLANS_KEY, serializedPlans);
        return data;
    }

    private static String boundedName(String requestName) {
        String value = requestName == null ? "" : requestName;
        return value.length() <= MAX_REQUEST_NAME_LENGTH ? value : value.substring(0, MAX_REQUEST_NAME_LENGTH);
    }

    enum PlanState {

        /** Advertised, waiting for its single automatic submission. */
        PENDING,
        /** Advertised, automatic calculation in flight. */
        CALCULATING,
        /** Advertised, an automatic job is running. */
        SUBMITTED,
        /** Advertised, the automatic attempt is over; the player may craft the token by hand. */
        AWAITING_MANUAL,
        /** The pattern was pushed and its materials are on their way back into network storage. */
        DELIVERED,
        /** The pattern was pushed and its materials were delivered. */
        COMPLETE;

        static PlanState fromOrdinal(int ordinal) {
            return ordinal >= 0 && ordinal < values().length ? values()[ordinal] : PENDING;
        }
    }

    /** One advertised pattern of a plan, together with the order token it produces. */
    static final class Plan {

        private final ItemStack token;
        private final ItemStack encodedPattern;
        private PlanState state;
        @Nullable
        private IPatternDetails details;
        @Nullable
        private Future<ICraftingPlan> calculation;
        @Nullable
        private ICraftingLink link;

        private Plan(ItemStack token, ItemStack encodedPattern, PlanState state, @Nullable ICraftingLink link) {
            this.token = Objects.requireNonNull(token, "token");
            this.encodedPattern = Objects.requireNonNull(encodedPattern, "encodedPattern");
            if (token.isEmpty() || encodedPattern.isEmpty()) {
                throw new IllegalArgumentException("A plan needs both an order token and an encoded pattern");
            }
            this.state = Objects.requireNonNull(state, "state");
            this.link = link;
        }

        ItemStack token() {
            return token.copy();
        }

        PlanState state() {
            return state;
        }

        @Nullable
        Future<ICraftingPlan> calculation() {
            return calculation;
        }

        @Nullable
        ICraftingLink link() {
            return link;
        }

        /** Decodes and caches the advertised pattern; returns null when AE2 cannot read it back. */
        @Nullable
        IPatternDetails details(World world) {
            if (details == null) details = PatternDetailsHelper.decodePattern(encodedPattern, world);
            return details;
        }

        boolean matches(IPatternDetails candidate) {
            AEItemKey definition = AEItemKey.of(encodedPattern);
            return candidate != null && definition != null && definition.equals(candidate.getDefinition());
        }

        boolean matches(@Nullable ICraftingLink candidate) {
            return link != null && candidate != null && link.getCraftingID().equals(candidate.getCraftingID());
        }

        void beginCalculation(Future<ICraftingPlan> calculation) {
            this.calculation = Objects.requireNonNull(calculation, "calculation");
            state = PlanState.CALCULATING;
        }

        void submit(ICraftingLink link) {
            this.link = Objects.requireNonNull(link, "link");
            calculation = null;
            state = PlanState.SUBMITTED;
        }

        /** Ends the automatic attempt while leaving the pattern advertised for manual crafting. */
        void awaitManual() {
            if (calculation != null) calculation.cancel(true);
            calculation = null;
            link = null;
            state = PlanState.AWAITING_MANUAL;
        }

        /**
         * Records that a pattern push handed the plan's materials over. The submitted job is wound down separately, so
         * AE2 is never re-entered from inside its own push.
         */
        void deliver() {
            if (calculation != null) calculation.cancel(true);
            calculation = null;
            state = PlanState.DELIVERED;
        }

        void complete() {
            if (link != null) link.cancel();
            calculation = null;
            link = null;
            state = PlanState.COMPLETE;
        }

        void cancel() {
            if (calculation != null) calculation.cancel(true);
            if (link != null) link.cancel();
            calculation = null;
            link = null;
            state = PlanState.COMPLETE;
        }

        NBTTagCompound writeToNbt() {
            NBTTagCompound data = new NBTTagCompound();
            data.setTag(TOKEN_KEY, token.writeToNBT(new NBTTagCompound()));
            data.setTag(PATTERN_KEY, encodedPattern.writeToNBT(new NBTTagCompound()));
            data.setByte(STATE_KEY, (byte) state.ordinal());
            if (state == PlanState.SUBMITTED && link != null) {
                NBTTagCompound serializedLink = new NBTTagCompound();
                link.writeToNBT(serializedLink);
                data.setTag(LINK_KEY, serializedLink);
            }
            return data;
        }

        /**
         * Restores one plan. In-flight calculations are not persistent, so a plan that was still calculating falls back
         * to {@link PlanState#PENDING} and gets its single automatic attempt after the reload; a submitted plan whose
         * link cannot be restored keeps its pattern advertised for manual crafting instead.
         */
        @Nullable
        static Plan readFromNbt(NBTTagCompound data, ICraftingRequester requester) {
            ItemStack token = new ItemStack(data.getCompoundTag(TOKEN_KEY));
            ItemStack encodedPattern = new ItemStack(data.getCompoundTag(PATTERN_KEY));
            if (token.isEmpty() || encodedPattern.isEmpty()) return null;

            PlanState state = PlanState.fromOrdinal(data.getByte(STATE_KEY));
            if (state == PlanState.CALCULATING) state = PlanState.PENDING;
            if (state == PlanState.DELIVERED) state = PlanState.COMPLETE;
            if (state == PlanState.SUBMITTED && data.hasKey(LINK_KEY, Constants.NBT.TAG_COMPOUND)) {
                try {
                    ICraftingLink link = StorageHelper.loadCraftingLink(data.getCompoundTag(LINK_KEY), requester);
                    return new Plan(token, encodedPattern, PlanState.SUBMITTED, link);
                } catch (RuntimeException ignored) {
                    state = PlanState.AWAITING_MANUAL;
                }
            } else if (state == PlanState.SUBMITTED) {
                state = PlanState.AWAITING_MANUAL;
            }
            return new Plan(token, encodedPattern, state, null);
        }
    }
}
