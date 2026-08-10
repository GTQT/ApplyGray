package applygray.mattermanipulator.uplink;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Future;

import applygray.mattermanipulator.building.BlockSpec;
import applygray.mattermanipulator.inventory.ResourceRequirement;
import applygray.mattermanipulator.inventory.ResourceRequirements;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

import ae2.api.networking.crafting.ICraftingLink;
import ae2.api.networking.crafting.ICraftingPlan;
import ae2.api.networking.crafting.ICraftingRequester;
import ae2.api.storage.StorageHelper;
import org.jetbrains.annotations.Nullable;

/**
 * Persisted, direct AE2 crafting work for one manipulator operation.
 *
 * <p>Only queued requirements and submitted links are serialized. In-flight calculations are restarted after a
 * world reload because AE2 calculation futures are intentionally not persistent.</p>
 */
final class UplinkCraftingRequest {

    static final int MAX_JOBS_PER_REQUEST = 1_024;
    private static final int MAX_REQUEST_NAME_LENGTH = 96;
    private static final String REQUESTER_MOST_KEY = "RequesterMost";
    private static final String REQUESTER_LEAST_KEY = "RequesterLeast";
    private static final String REQUEST_NAME_KEY = "RequestName";
    private static final String JOBS_KEY = "Jobs";
    private static final String SPECIFICATION_KEY = "Specification";
    private static final String AMOUNT_KEY = "Amount";
    private static final String STATE_KEY = "State";
    private static final String LINK_KEY = "Link";

    private final UUID requesterId;
    private final String requestName;
    private final List<Job> jobs;

    private UplinkCraftingRequest(UUID requesterId, String requestName, List<Job> jobs) {
        this.requesterId = Objects.requireNonNull(requesterId, "requesterId");
        this.requestName = boundedName(requestName);
        this.jobs = List.copyOf(jobs);
    }

    static UplinkCraftingRequest create(UUID requesterId, String requestName, ResourceRequirements requirements) {
        Objects.requireNonNull(requesterId, "requesterId");
        Objects.requireNonNull(requirements, "requirements");

        List<Job> jobs = new ArrayList<>(requirements.entries().size());
        for (ResourceRequirement requirement : requirements.entries()) {
            if (requirement.specification().isAir()) continue;
            if (jobs.size() >= MAX_JOBS_PER_REQUEST) {
                throw new IllegalArgumentException("The crafting request has too many distinct item requirements");
            }
            jobs.add(new Job(requirement.specification(), requirement.amount(), JobState.QUEUED, null));
        }
        if (jobs.isEmpty()) throw new IllegalArgumentException("The crafting request has no item requirements");
        return new UplinkCraftingRequest(requesterId, requestName, jobs);
    }

    @Nullable
    static UplinkCraftingRequest readFromNbt(NBTTagCompound data, ICraftingRequester requester) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(requester, "requester");
        if (!data.hasKey(REQUESTER_MOST_KEY, Constants.NBT.TAG_LONG) ||
                !data.hasKey(REQUESTER_LEAST_KEY, Constants.NBT.TAG_LONG) ||
                !data.hasKey(JOBS_KEY, Constants.NBT.TAG_LIST)) {
            return null;
        }

        UUID requesterId = new UUID(data.getLong(REQUESTER_MOST_KEY), data.getLong(REQUESTER_LEAST_KEY));
        NBTTagList serializedJobs = data.getTagList(JOBS_KEY, Constants.NBT.TAG_COMPOUND);
        List<Job> jobs = new ArrayList<>(Math.min(serializedJobs.tagCount(), MAX_JOBS_PER_REQUEST));
        for (int index = 0; index < serializedJobs.tagCount() && jobs.size() < MAX_JOBS_PER_REQUEST; index++) {
            Job job = Job.readFromNbt(serializedJobs.getCompoundTagAt(index), requester);
            if (job != null) jobs.add(job);
        }
        return jobs.isEmpty() ? null : new UplinkCraftingRequest(requesterId, data.getString(REQUEST_NAME_KEY), jobs);
    }

    UUID requesterId() {
        return requesterId;
    }

    String requestName() {
        return requestName;
    }

    List<Job> jobs() {
        return jobs;
    }

    boolean isTerminal() {
        return jobs.stream().allMatch(job -> job.state().terminal());
    }

    boolean completedSuccessfully() {
        return jobs.stream().allMatch(job -> job.state() == JobState.COMPLETE);
    }

    boolean owns(ICraftingLink link) {
        return jobs.stream().anyMatch(job -> job.matches(link));
    }

    void cancel() {
        for (Job job : jobs) {
            job.cancel();
        }
    }

    NBTTagCompound writeToNbt() {
        NBTTagCompound data = new NBTTagCompound();
        data.setLong(REQUESTER_MOST_KEY, requesterId.getMostSignificantBits());
        data.setLong(REQUESTER_LEAST_KEY, requesterId.getLeastSignificantBits());
        data.setString(REQUEST_NAME_KEY, requestName);
        NBTTagList serializedJobs = new NBTTagList();
        for (Job job : jobs) {
            serializedJobs.appendTag(job.writeToNbt());
        }
        data.setTag(JOBS_KEY, serializedJobs);
        return data;
    }

    private static String boundedName(String requestName) {
        String value = requestName == null ? "" : requestName;
        return value.length() <= MAX_REQUEST_NAME_LENGTH ? value : value.substring(0, MAX_REQUEST_NAME_LENGTH);
    }

    enum JobState {

        QUEUED,
        CALCULATING,
        SUBMITTED,
        COMPLETE,
        FAILED;

        boolean terminal() {
            return this == COMPLETE || this == FAILED;
        }

        static JobState fromOrdinal(int ordinal) {
            return ordinal >= 0 && ordinal < values().length ? values()[ordinal] : QUEUED;
        }
    }

    static final class Job {

        private final BlockSpec specification;
        private final long amount;
        private JobState state;
        @Nullable
        private Future<ICraftingPlan> calculation;
        @Nullable
        private ICraftingLink link;

        private Job(BlockSpec specification, long amount, JobState state, @Nullable ICraftingLink link) {
            this.specification = Objects.requireNonNull(specification, "specification");
            if (specification.isAir() || amount <= 0L) {
                throw new IllegalArgumentException("Crafting jobs require one positive non-air item requirement");
            }
            this.amount = amount;
            this.state = Objects.requireNonNull(state, "state");
            this.link = link;
        }

        BlockSpec specification() {
            return specification;
        }

        long amount() {
            return amount;
        }

        JobState state() {
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

        void beginCalculation(Future<ICraftingPlan> calculation) {
            this.calculation = Objects.requireNonNull(calculation, "calculation");
            state = JobState.CALCULATING;
        }

        void submit(ICraftingLink link) {
            this.link = Objects.requireNonNull(link, "link");
            calculation = null;
            state = JobState.SUBMITTED;
        }

        void complete() {
            calculation = null;
            link = null;
            state = JobState.COMPLETE;
        }

        void fail() {
            calculation = null;
            link = null;
            state = JobState.FAILED;
        }

        boolean matches(ICraftingLink candidate) {
            return link != null && candidate != null && link.getCraftingID().equals(candidate.getCraftingID());
        }

        void cancel() {
            if (calculation != null) calculation.cancel(true);
            if (link != null) link.cancel();
            calculation = null;
            link = null;
            state = JobState.FAILED;
        }

        NBTTagCompound writeToNbt() {
            NBTTagCompound data = new NBTTagCompound();
            data.setTag(SPECIFICATION_KEY, specification.writeToNbt());
            data.setLong(AMOUNT_KEY, amount);
            data.setByte(STATE_KEY, (byte) state.ordinal());
            if (state == JobState.SUBMITTED && link != null) {
                NBTTagCompound linkData = new NBTTagCompound();
                link.writeToNBT(linkData);
                data.setTag(LINK_KEY, linkData);
            }
            return data;
        }

        @Nullable
        static Job readFromNbt(NBTTagCompound data, ICraftingRequester requester) {
            if (!data.hasKey(SPECIFICATION_KEY, Constants.NBT.TAG_COMPOUND) ||
                    !data.hasKey(AMOUNT_KEY, Constants.NBT.TAG_LONG)) {
                return null;
            }
            BlockSpec specification = BlockSpec.readFromNbt(data.getCompoundTag(SPECIFICATION_KEY));
            long amount = data.getLong(AMOUNT_KEY);
            if (specification.isAir() || amount <= 0L) return null;

            JobState state = JobState.fromOrdinal(data.getByte(STATE_KEY));
            if (state == JobState.CALCULATING) state = JobState.QUEUED;
            if (state == JobState.SUBMITTED && data.hasKey(LINK_KEY, Constants.NBT.TAG_COMPOUND)) {
                try {
                    ICraftingLink link = StorageHelper.loadCraftingLink(data.getCompoundTag(LINK_KEY), requester);
                    return new Job(specification, amount, JobState.SUBMITTED, link);
                } catch (RuntimeException ignored) {
                    state = JobState.QUEUED;
                }
            }
            return new Job(specification, amount, state, null);
        }
    }
}
