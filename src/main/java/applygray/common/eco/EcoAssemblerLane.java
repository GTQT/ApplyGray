package applygray.common.eco;

import applygray.ApplyGrayMod;
import net.minecraft.util.NonNullList;
import ae2.api.config.Actionable;
import ae2.api.config.PowerMultiplier;
import ae2.api.crafting.IAssemblerPattern;
import ae2.api.crafting.IPatternDetails;
import ae2.api.crafting.PatternDetailsHelper;
import ae2.api.stacks.*;
import ae2.crafting.pattern.AECraftingPattern;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import java.util.ArrayList;
import java.util.List;

/** Persistent input escrow and output buffer belonging to one physical worker core. */
public final class EcoAssemblerLane {
    private final EcoPart owner;
    private ItemStack patternStack = ItemStack.EMPTY;
    private IAssemblerPattern pattern;
    private KeyCounter[] inputs = new KeyCounter[0];
    private KeyCounter outputs = new KeyCounter();
    private int remaining;
    private int delay;
    private String state = "idle";

    EcoAssemblerLane(EcoPart owner) { this.owner = owner; }
    public boolean canAccept() { return remaining == 0 && outputs.isEmpty() && inputs.length == 0; }
    public int remaining() { return remaining; }
    public String state() { return state; }
    public long bufferedOutputs() {
        long total = 0;
        for (var entry : outputs) total = Math.addExact(total, entry.getLongValue());
        return total;
    }
    public static boolean isAssemblerPattern(ItemStack stack, World world) {
        return world != null && PatternDetailsHelper.decodePattern(stack, world) instanceof IAssemblerPattern;
    }
    /** Only vanilla, fixed recipes may use one validated grid as the result of a homogeneous batch. */
    private static boolean isFixedRecipe(IPatternDetails details) {
        if (!(details instanceof AECraftingPattern nativePattern) || nativePattern.getRecipe().isDynamic()) return false;
        Class<?> recipe = nativePattern.getRecipe().getClass();
        return recipe == ShapedRecipes.class || recipe == ShapelessRecipes.class
                || recipe == net.minecraftforge.oredict.ShapedOreRecipe.class
                || recipe == net.minecraftforge.oredict.ShapelessOreRecipe.class;
    }
    public boolean accept(IPatternDetails details, KeyCounter[] offered, int multiplier) {
        if (!canAccept() || !(details instanceof IAssemblerPattern assembler) || multiplier <= 0
                || multiplier > EcoConfig.batch(owner.hardwareTier)
                || offered.length != details.getInputs().length) return false;
        KeyCounter[] owned = new KeyCounter[offered.length];
        for (int i = 0; i < offered.length; i++) {
            if (offered[i] == null || offered[i].isEmpty()) return false;
            owned[i] = copy(offered[i]);
            for (var entry : owned[i]) if (entry.getLongValue() <= 0) return false;
        }
        // All refusal paths above leave the caller's inventory untouched.
        inputs = owned;
        pattern = assembler;
        patternStack = details.getDefinition().toStack();
        remaining = multiplier;
        delay = Math.max(1, EcoConfig.assemblerCycleTicks);
        state = "working";
        for (KeyCounter counter : offered) counter.clear();
        owner.markDirty();
        return true;
    }
    public void tick(EcoController controller) {
        var grid = controller.port().getMainNode().getGrid();
        if (grid == null) return;
        var source = controller.port().source();
        var storage = grid.getStorageService().getInventory();
        boolean changed = false;
        for (var entry : copy(outputs)) {
            long requested = entry.getLongValue();
            long moved = storage.insert(entry.getKey(), requested, Actionable.MODULATE, source);
            if (moved < 0 || moved > requested) throw new IllegalStateException("Invalid ME insertion result");
            if (moved > 0) { outputs.remove(entry.getKey(), moved); changed = true; }
        }
        outputs.removeZeros();
        if (changed) owner.markDirty();
        if (!outputs.isEmpty()) { state = "output_blocked"; return; }
        if (remaining <= 0) { inputs = new KeyCounter[0]; if (!state.equals("invalid_pattern")) state = "idle"; return; }
        if (delay > 0) { delay--; owner.markDirty(); if (delay > 0) return; }
        if (pattern == null) {
            IPatternDetails decoded = PatternDetailsHelper.decodePattern(patternStack, owner.getWorld());
            if (decoded instanceof IAssemblerPattern assembler) pattern = assembler;
            else { returnInputs(); state = "invalid_pattern"; return; }
        }
        for (int evaluation = 0; remaining > 0 && evaluation < EcoConfig.evaluationsPerTick; evaluation++) {
            var energy = grid.getEnergyService();
            double available = energy.extractAEPower((double) EcoConfig.aePerCraft * remaining,
                    Actionable.SIMULATE, PowerMultiplier.CONFIG);
            int affordable = (int) Math.min(remaining, Math.floor(available / EcoConfig.aePerCraft));
            if (affordable <= 0) { state = "no_power"; return; }

            KeyCounter[] after = copyInputs(inputs);
            InventoryCrafting frame = new InventoryCrafting(new Container() {
                @Override public boolean canInteractWith(EntityPlayer player) { return false; }
            }, 3, 3);
            final ItemStack result;
            final NonNullList<ItemStack> returnedItems;
            try {
                pattern.fillCraftingGrid(after, frame::setInventorySlotContents);
                result = pattern.assemble(frame, owner.getWorld());
                returnedItems = pattern.getRemainingItems(frame);
            } catch (IllegalArgumentException | IllegalStateException invalidPattern) {
                ApplyGrayMod.LOGGER.warn("Invalid ECO assembly pattern at {}", owner.getPos(), invalidPattern);
                returnInputs(); state = "invalid_pattern"; return;
            }
            if (result.isEmpty()) { returnInputs(); state = "invalid_pattern"; return; }
            KeyCounter produced = new KeyCounter();
            GenericStack actual = GenericStack.fromItemStack(result);
            if (actual == null) { returnInputs(); state = "invalid_pattern"; return; }
            produced.add(actual.what(), actual.amount());
            for (ItemStack stack : returnedItems) {
                GenericStack returned = GenericStack.fromItemStack(stack);
                if (returned != null) produced.add(returned.what(), returned.amount());
            }
            boolean homogeneous = isFixedRecipe(pattern);
            for (int slot = 0; slot < frame.getSizeInventory(); slot++) {
                ItemStack stack = frame.getStackInSlot(slot);
                if (!stack.isEmpty() && (stack.hasTagCompound() || stack.isItemStackDamageable()
                        || stack.getItem().hasContainerItem(stack))) homogeneous = false;
            }
            int batch = homogeneous ? affordable : 1;
            for (int i = 0; i < inputs.length; i++) {
                for (var entry : inputs[i]) {
                    long consumed = entry.getLongValue() - after[i].get(entry.getKey());
                    if (consumed < 0) throw new IllegalStateException("Pattern generated inputs");
                    if (consumed > 0) batch = (int) Math.min(batch, entry.getLongValue() / consumed);
                }
            }
            if (batch <= 0) { returnInputs(); state = "invalid_pattern"; return; }
            KeyCounter[] committedInputs = copyInputs(inputs);
            KeyCounter committedOutputs = copy(outputs);
            for (int i = 0; i < inputs.length; i++) {
                for (var entry : inputs[i]) {
                    long consumed = entry.getLongValue() - after[i].get(entry.getKey());
                    committedInputs[i].remove(entry.getKey(), Math.multiplyExact(consumed, batch));
                }
                committedInputs[i].removeZeros();
            }
            for (var entry : produced) {
                long amount = Math.multiplyExact(entry.getLongValue(), batch);
                committedOutputs.set(entry.getKey(), Math.addExact(committedOutputs.get(entry.getKey()), amount));
            }
            double cost = (double) EcoConfig.aePerCraft * batch;
            if (energy.extractAEPower(cost, Actionable.MODULATE, PowerMultiplier.CONFIG) + 0.0001 < cost) {
                state = "no_power"; return;
            }
            inputs = committedInputs;
            outputs = committedOutputs;
            remaining -= batch;
            state = "working";
            if (remaining == 0) {
                // A custom pattern may not consume every offered key. Return every remainder exactly once.
                for (KeyCounter counter : inputs) outputs.addAll(counter);
                inputs = new KeyCounter[0];
                pattern = null;
                patternStack = ItemStack.EMPTY;
            }
            owner.markDirty();
            // Evaluate changing recipes individually within the tick budget. Outputs stay in escrow
            // until the next tick, including every container or changed tool from each evaluation.
        }
    }
    private void returnInputs() {
        for (KeyCounter counter : inputs) outputs.addAll(counter);
        inputs = new KeyCounter[0]; remaining = 0; pattern = null; patternStack = ItemStack.EMPTY;
        owner.markDirty();
    }
    private static KeyCounter copy(KeyCounter source) { KeyCounter result = new KeyCounter(); result.addAll(source); return result; }
    private static KeyCounter[] copyInputs(KeyCounter[] source) {
        KeyCounter[] result = new KeyCounter[source.length];
        for (int i = 0; i < source.length; i++) result[i] = copy(source[i]);
        return result;
    }
    private static NBTTagList saveCounter(KeyCounter counter) {
        List<GenericStack> stacks = new ArrayList<>();
        for (var entry : counter) if (entry.getLongValue() > 0) stacks.add(new GenericStack(entry.getKey(), entry.getLongValue()));
        return GenericStack.writeList(stacks);
    }
    private static KeyCounter loadCounter(NBTTagList tag) {
        KeyCounter counter = new KeyCounter();
        for (GenericStack stack : GenericStack.readList(tag)) if (stack.amount() > 0) counter.add(stack.what(), stack.amount());
        return counter;
    }
    public NBTTagCompound save() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("pattern", patternStack.writeToNBT(new NBTTagCompound()));
        tag.setInteger("remaining", remaining); tag.setInteger("delay", delay);
        tag.setString("state", state);
        NBTTagList table = new NBTTagList();
        for (KeyCounter counter : inputs) table.appendTag(saveCounter(counter));
        tag.setTag("inputs", table); tag.setTag("outputs", saveCounter(outputs));
        return tag;
    }
    public void load(NBTTagCompound tag) {
        pattern = null;
        patternStack = new ItemStack(tag.getCompoundTag("pattern"));
        remaining = Math.max(0, tag.getInteger("remaining"));
        delay = Math.max(0, tag.getInteger("delay"));
        NBTTagList table = tag.getTagList("inputs", 9);
        inputs = new KeyCounter[table.tagCount()];
        for (int i = 0; i < table.tagCount(); i++) inputs[i] = loadCounter((NBTTagList) table.get(i));
        outputs = loadCounter(tag.getTagList("outputs", 10));
        state = tag.getString("state").equals("invalid_pattern") ? "invalid_pattern"
                : remaining > 0 ? "working" : outputs.isEmpty() ? "idle" : "output_blocked";
    }
}
