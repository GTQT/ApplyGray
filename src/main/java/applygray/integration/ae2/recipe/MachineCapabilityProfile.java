package applygray.integration.ae2.recipe;

import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import gregtech.api.capability.IHeatMachine;
import gregtech.api.capability.IMultipleTankHandler;
import gregtech.api.capability.IHeatingCoil;
import gregtech.api.capability.IOpticalComputationProvider;
import gregtech.api.capability.IOpticalComputationReceiver;
import gregtech.api.capability.IRecipeMapHolder;
import gregtech.api.capability.impl.AbstractRecipeLogic;
import gregtech.api.metatileentity.multiblock.CleanroomType;
import gregtech.api.metatileentity.multiblock.ICleanroomProvider;
import gregtech.api.metatileentity.multiblock.ICleanroomReceiver;
import gregtech.api.metatileentity.multiblock.MultiblockControllerBase;
import gregtech.api.recipes.RecipeMap;
import gregtech.common.metatileentities.multi.electric.godforge.module.MTEBaseModule;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.IFluidTank;
import net.minecraftforge.items.IItemHandlerModifiable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Main-thread snapshot of the provider's execution environment. Planning code only consumes this immutable object.
 */
public final class MachineCapabilityProfile {

    private final String providerId;
    private final String controllerType;
    private final boolean structureFormed;
    private final List<String> recipeMaps;
    private final long maxVoltage;
    private final int parallelLimit;
    private final int itemOutputSlots;
    private final int fluidOutputTanks;
    private final int availableItemOutputSlots;
    private final int availableFluidOutputTanks;
    private final int bufferCount;
    private final int dimension;
    private final Set<String> cleanroomTypes;
    private final Set<String> capabilities;
    private final Map<String, Object> adapterFacts;
    private final String version;

    private MachineCapabilityProfile(String providerId, String controllerType, boolean structureFormed,
                                      List<String> recipeMaps, long maxVoltage, int parallelLimit,
                                      int itemOutputSlots, int fluidOutputTanks,
                                      int availableItemOutputSlots, int availableFluidOutputTanks, int bufferCount,
                                     int dimension, Set<String> cleanroomTypes,
                                     Set<String> capabilities, Map<String, Object> adapterFacts) {
        this.providerId = providerId;
        this.controllerType = controllerType;
        this.structureFormed = structureFormed;
        this.recipeMaps = Collections.unmodifiableList(new ArrayList<>(recipeMaps));
        this.maxVoltage = maxVoltage;
        this.parallelLimit = parallelLimit;
        this.itemOutputSlots = itemOutputSlots;
        this.fluidOutputTanks = fluidOutputTanks;
        this.availableItemOutputSlots = availableItemOutputSlots;
        this.availableFluidOutputTanks = availableFluidOutputTanks;
        this.bufferCount = bufferCount;
        this.dimension = dimension;
        this.cleanroomTypes = Collections.unmodifiableSet(new LinkedHashSet<>(cleanroomTypes));
        this.capabilities = Collections.unmodifiableSet(new LinkedHashSet<>(capabilities));
        this.adapterFacts = Collections.unmodifiableMap(sanitizeFacts(adapterFacts));
        this.version = RecipeFingerprint.sha256(providerId + '\n' + controllerType + '\n' + structureFormed + '\n' +
                String.join("|", recipeMaps) + '\n' + maxVoltage + '\n' + parallelLimit + '\n' +
                itemOutputSlots + '\n' + fluidOutputTanks + '\n' + availableItemOutputSlots + '\n' +
                availableFluidOutputTanks + '\n' + bufferCount + '\n' +
                dimension + '\n' + String.join("|", this.cleanroomTypes) + '\n' +
                String.join("|", this.capabilities) + '\n' + this.adapterFacts);
    }

    public static MachineCapabilityProfile capture(String providerId, MultiblockControllerBase controller,
                                                   RecipeMap<?>[] exposedRecipeMaps, int bufferCount) {
        return capture(providerId, controller, exposedRecipeMaps, bufferCount, Collections.emptyMap());
    }

    public static MachineCapabilityProfile capture(String providerId, MultiblockControllerBase controller,
                                                   RecipeMap<?>[] exposedRecipeMaps, int bufferCount,
                                                   Map<String, Object> adapterFacts) {
        List<String> recipeMaps = new ArrayList<>();
        if (exposedRecipeMaps != null) {
            for (RecipeMap<?> recipeMap : exposedRecipeMaps) {
                if (recipeMap != null) recipeMaps.add(recipeMap.getUnlocalizedName());
            }
        }

        String controllerType = controller == null ? "<none>" : controller.getClass().getName();
        boolean formed = controller != null && controller.isStructureFormed();
        long maxVoltage = 0;
        int parallelLimit = 0;
        int itemOutputSlots = 0;
        int fluidOutputTanks = 0;
        int availableItemOutputSlots = 0;
        int availableFluidOutputTanks = 0;
        int dimension = Integer.MIN_VALUE;
        Set<String> cleanroomTypes = new LinkedHashSet<>();
        Set<String> capabilities = new LinkedHashSet<>();
        Map<String, Object> facts = new LinkedHashMap<>();
        if (adapterFacts != null) facts.putAll(adapterFacts);
        if (formed) capabilities.add("structure");

        if (controller != null) {
            if (controller.getWorld() != null && controller.getWorld().provider != null) {
                dimension = controller.getWorld().provider.getDimension();
                facts.putIfAbsent("dimension", dimension);
            }
            AbstractRecipeLogic recipeLogic = controller.getRecipeLogic();
            if (recipeLogic != null) {
                maxVoltage = recipeLogic.getMaxVoltage();
                parallelLimit = recipeLogic.getParallelLimit();
                if (maxVoltage > 0) capabilities.add("energy");
                if (parallelLimit > 0) capabilities.add("parallel");
                facts.putIfAbsent("maxVoltage", maxVoltage);
                facts.putIfAbsent("parallelLimit", parallelLimit);
            }
            if (controller instanceof IRecipeMapHolder recipeMapHolder) {
                IItemHandlerModifiable outputInventory = recipeMapHolder.getOutputInventory();
                IMultipleTankHandler outputTanks = recipeMapHolder.getOutputFluidInventory();
                itemOutputSlots = outputInventory == null ? 0 : outputInventory.getSlots();
                fluidOutputTanks = outputTanks == null ? 0 : outputTanks.getTanks();
                if (outputInventory != null) {
                    for (int slot = 0; slot < outputInventory.getSlots(); slot++) {
                        ItemStack output = outputInventory.getStackInSlot(slot);
                        if (output == null || output.isEmpty()) availableItemOutputSlots++;
                    }
                }
                if (outputTanks != null) {
                    for (int tankIndex = 0; tankIndex < outputTanks.getTanks(); tankIndex++) {
                        IFluidTank output = outputTanks.getTankAt(tankIndex);
                        if (output == null || output.getFluid() == null || output.getFluidAmount() <= 0) {
                            availableFluidOutputTanks++;
                        }
                    }
                }
                if (itemOutputSlots > 0) capabilities.add("item_output");
                if (fluidOutputTanks > 0) capabilities.add("fluid_output");
                if (recipeMapHolder.getEnergyContainer() != null) {
                    facts.putIfAbsent("energyCapacity", recipeMapHolder.getEnergyContainer().getEnergyCapacity());
                }
            }
            int temperature = getRecipeTemperature(controller);
            if (temperature > 0) {
                capabilities.add("temperature");
                capabilities.add("heat");
                facts.putIfAbsent("temperature", temperature);
                facts.putIfAbsent("heat", temperature);
            }
            if (controller instanceof IOpticalComputationReceiver computationReceiver) {
                IOpticalComputationProvider provider = computationReceiver.getComputationProvider();
                if (provider != null) {
                    int computationPerTick = provider.getMaxCWUt();
                    if (computationPerTick > 0) {
                        capabilities.add("computation");
                        facts.putIfAbsent("computationPerTick", computationPerTick);
                    }
                }
            }
            if (controller instanceof ICleanroomReceiver cleanroomReceiver) {
                ICleanroomProvider cleanroom = cleanroomReceiver.getCleanroom();
                if (cleanroom != null && cleanroom.isClean()) {
                    for (CleanroomType type : standardCleanroomTypes()) {
                        if (cleanroom.checkCleanroomType(type)) cleanroomTypes.add(type.getName());
                    }
                    if (!cleanroomTypes.isEmpty()) capabilities.add("cleanroom");
                }
            }
        }
        if (bufferCount > 0) capabilities.add("isolated_buffer");
        return new MachineCapabilityProfile(providerId, controllerType, formed, recipeMaps, maxVoltage,
                parallelLimit, itemOutputSlots, fluidOutputTanks, availableItemOutputSlots,
                availableFluidOutputTanks, bufferCount, dimension, cleanroomTypes,
                capabilities, facts);
    }

    /**
     * Returns the temperature that the physical recipe logic actually uses. Godforge modules carry their heat on the
     * module rather than implementing {@link IHeatingCoil}; treating them as temperature-less rejects every
     * blast-furnace route even after the module has been connected to the forge.
     */
    private static int getRecipeTemperature(MultiblockControllerBase controller) {
        int heatingCoilTemperature = controller instanceof IHeatingCoil heatingCoil ?
                heatingCoil.getCurrentTemperature() : 0;
        int genericHeatMachineTemperature = controller instanceof IHeatMachine heatMachine ?
                heatMachine.getTemperature() : 0;
        int godforgeModuleTemperature = controller instanceof MTEBaseModule module ? module.getHeat() : 0;
        return selectRecipeTemperature(heatingCoilTemperature, genericHeatMachineTemperature,
                godforgeModuleTemperature);
    }

    static int selectRecipeTemperature(int heatingCoilTemperature, int genericHeatMachineTemperature,
                                       int godforgeModuleTemperature) {
        if (heatingCoilTemperature > 0) return heatingCoilTemperature;
        if (godforgeModuleTemperature > 0) return godforgeModuleTemperature;
        return Math.max(0, genericHeatMachineTemperature);
    }

    public String getProviderId() {
        return providerId;
    }

    public String getControllerType() {
        return controllerType;
    }

    public boolean isStructureFormed() {
        return structureFormed;
    }

    public List<String> getRecipeMaps() {
        return recipeMaps;
    }

    public long getMaxVoltage() {
        return maxVoltage;
    }

    public int getParallelLimit() {
        return parallelLimit;
    }

    public int getItemOutputSlots() {
        return itemOutputSlots;
    }

    public int getFluidOutputTanks() {
        return fluidOutputTanks;
    }

    public int getAvailableItemOutputSlots() {
        return availableItemOutputSlots;
    }

    public int getAvailableFluidOutputTanks() {
        return availableFluidOutputTanks;
    }

    public int getBufferCount() {
        return bufferCount;
    }

    public int getDimension() {
        return dimension;
    }

    public boolean hasCleanroomType(String type) {
        return type != null && cleanroomTypes.contains(type);
    }

    public String getVersion() {
        return version;
    }

    public boolean hasCapability(String capability) {
        return capabilities.contains(capability);
    }

    public Map<String, Object> getAdapterFacts() {
        return adapterFacts;
    }

    public Long getNumericFact(String name) {
        Object value = adapterFacts.get(name);
        return value instanceof Number number ? number.longValue() : null;
    }

    /**
     * Proves that the machine has an output location for the one product represented by the virtual pattern.
     * Physical byproducts are intentionally not part of the AE2 pattern contract and therefore do not reserve
     * additional pattern output locations here.
     */
    public boolean canAcceptPatternOutput(AEKey target) {
        if (target instanceof AEItemKey) return availableItemOutputSlots > 0;
        if (target instanceof AEFluidKey) return availableFluidOutputTanks > 0;
        return false;
    }

    public String summarize() {
        return controllerType + " maps=" + recipeMaps + " voltage=" + maxVoltage +
                " parallel=" + parallelLimit + " dimension=" + dimension + " outputSlots=" +
                availableItemOutputSlots + '/' + itemOutputSlots + " fluid=" +
                availableFluidOutputTanks + '/' + fluidOutputTanks;
    }

    private static Map<String, Object> sanitizeFacts(Map<String, Object> source) {
        Map<String, Object> sanitized = new TreeMap<>();
        if (source == null) return sanitized;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            Object value = entry.getValue();
            if (value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
                sanitized.put(entry.getKey(), value);
            } else {
                sanitized.put(entry.getKey(), String.valueOf(value));
            }
        }
        return sanitized;
    }

    private static List<CleanroomType> standardCleanroomTypes() {
        return List.of(CleanroomType.CLEANROOM, CleanroomType.STERILE_CLEANROOM, CleanroomType.ISO3,
                CleanroomType.ISO2, CleanroomType.ISO1, CleanroomType.ISO0);
    }
}
