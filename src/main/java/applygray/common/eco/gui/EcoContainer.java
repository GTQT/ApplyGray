package applygray.common.eco.gui;

import ae2.api.storage.cells.StorageCellStatistics;
import ae2.container.AEBaseContainer;
import ae2.container.SlotSemantics;
import ae2.container.guisync.GuiSync;
import ae2.container.slot.AppEngSlot;
import applygray.common.eco.*;
import applygray.integration.ae2.ItemHandlerInternalInventory;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntityHolder;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.text.TextComponentTranslation;
import java.util.*;

/** AE2 native menu; all mutations are server-authoritative and bound to this exact holder/host pair. */
public final class EcoContainer extends AEBaseContainer {
    public static final int ROWS = 7;
    public final MetaTileEntity host;
    private final MetaTileEntityHolder holder;
    private String search = "";
    private int tick;
    @GuiSync(0) public String rows = "";
    @GuiSync(1) public String rowIds = "";
    @GuiSync(2) public int page;
    @GuiSync(3) public int pageCount = 1;
    @GuiSync(4) public int tab;
    @GuiSync(5) public boolean online;
    @GuiSync(6) public boolean enabled = true;
    @GuiSync(7) public long used;
    @GuiSync(8) public long capacity;
    @GuiSync(9) public long secondaryUsed;
    @GuiSync(10) public long secondaryCapacity;
    @GuiSync(11) public int active;
    @GuiSync(12) public int total;
    @GuiSync(13) public long accelerators;
    @GuiSync(14) public long energyStored;
    @GuiSync(15) public long energyCapacity;

    public EcoContainer(InventoryPlayer inventory, MetaTileEntityHolder holder, MetaTileEntity host) {
        super(inventory, holder);
        this.holder = holder; this.host = host;
        setGuiTitle(new TextComponentTranslation(host.getMetaFullName()));
        registerClientAction("eco.page", Integer.class, value -> {
            if (canInteractWith(getPlayer())) page = Math.clamp(value, 0, pageCount - 1);
        });
        registerClientAction("eco.tab", Integer.class, value -> {
            if (canInteractWith(getPlayer())) { tab = Math.clamp(value, 0, 2); page = 0; }
        });
        registerClientAction("eco.search", String.class, 128, value -> {
            if (canInteractWith(getPlayer())) { search = value.substring(0, Math.min(96, value.length())).toLowerCase(Locale.ROOT); page = 0; }
        });
        registerClientAction("eco.toggle", () -> {
            EcoController controller = controller();
            if (canInteractWith(getPlayer()) && controller != null) controller.setEnabled(!controller.enabled());
        });
        registerClientAction("eco.cancel", String.class, 80, id -> {
            EcoController controller = controller();
            if (!canInteractWith(getPlayer()) || controller == null) return;
            for (EcoCpu cpu : controller.cpus()) {
                if (cpu.identity().toString().equals(id)) { cpu.cancelJob(); break; }
            }
        });
        if (host instanceof EcoPart part && part.inventory.getSlots() > 0) {
            var internal = new ItemHandlerInternalInventory(part.inventory, part::markDirty);
            for (int i = 0; i < internal.size(); i++) {
                AppEngSlot slot = new AppEngSlot(internal, i);
                slot.xPos = 38 + (i % 9) * 18;
                slot.yPos = (part.role == EcoPart.Role.PATTERN_BUS ? 42 : 66) + (i / 9) * 18;
                addSlot(slot, SlotSemantics.ENCODED_PATTERN);
            }
        }
        addPlayerInventorySlots(38, 180);
    }
    public EcoController controller() {
        if (host instanceof EcoController controller) return controller;
        if (host instanceof EcoPart part && part.getController() instanceof EcoController controller) return controller;
        if (host instanceof EcoNetWork port) return port.controller();
        return null;
    }
    @Override public boolean canInteractWith(EntityPlayer player) {
        if (!super.canInteractWith(player) || holder.getMetaTileEntity() != host) return false;
        // Supergiant has no security-grid service. Use the world's and player's native edit permissions.
        return holder.getWorld().isBlockModifiable(player, holder.getPos())
                && player.canPlayerEdit(holder.getPos(), net.minecraft.util.EnumFacing.UP, player.getHeldItemMainhand());
    }
    public void changePage(int value) { sendClientAction("eco.page", value); }
    public void changeTab(int value) { sendClientAction("eco.tab", value); }
    public void search(String value) { sendClientAction("eco.search", value); }
    public void toggle() { sendClientAction("eco.toggle"); }
    public void cancel(String id) { sendClientAction("eco.cancel", id); }
    @Override public void broadcastChanges() {
        if (isServerSide() && ++tick % 5 == 0) refresh();
        super.broadcastChanges();
    }
    private void refresh() {
        EcoController controller = controller();
        List<String> lines = new ArrayList<>(), ids = new ArrayList<>();
        used = capacity = secondaryUsed = secondaryCapacity = accelerators = 0;
        active = total = 0; energyStored = energyCapacity = 0;
        online = controller != null && controller.online();
        if (controller != null) {
            enabled = controller.enabled();
            if (controller.kind == EcoController.Kind.COMPUTER) {
                used = controller.memory.used(); capacity = controller.memory.capacity();
                accelerators = controller.accelerators(); total = controller.cpus().size();
                for (EcoCpu cpu : controller.cpus()) {
                    if (cpu.isBusy()) active++;
                    String line = cpu.getName().getUnformattedText() + "  " + cpu.getAvailableStorage() + " B";
                    var job = cpu.getJobStatus();
                    if (job != null) line += "  " + job.crafting().what().getDisplayName().getUnformattedText()
                            + "  " + job.progress() + "/" + job.totalItems();
                    if (tab != 2 && (tab != 1 || cpu.isBusy())) { lines.add(line); ids.add(cpu.identity().toString()); }
                }
            } else if (controller.kind == EcoController.Kind.STORAGE) {
                int index = 0;
                for (EcoPart drive : controller.parts(EcoPart.Role.DRIVE)) {
                    var cell = drive.cell();
                    String line = "#" + (++index) + "  " + drive.inventory.getStackInSlot(0).getDisplayName();
                    if (cell instanceof StorageCellStatistics stats) {
                        active++;
                        used += stats.getUsedBytes(); capacity += stats.getTotalBytes();
                        secondaryUsed += stats.getStoredTypes(); secondaryCapacity += stats.getTotalTypes();
                        line += "  " + stats.getUsedBytes() + "/" + stats.getTotalBytes() + " B";
                    }
                    if (tab != 2) { lines.add(line); ids.add(""); }
                }
                total = index;
                if (controller.port() != null) {
                    energyStored = (long) controller.port().getAECurrentPower();
                    energyCapacity = (long) controller.port().getAEMaxPower();
                }
            } else {
                for (EcoPart worker : controller.parts(EcoPart.Role.WORKER)) {
                    total++;
                    if (!worker.lane.canAccept()) active++;
                    capacity += EcoConfig.batch(worker.hardwareTier);
                    used += worker.lane.remaining();
                    if (tab == 0) {
                        lines.add("L" + worker.hardwareTier + "  {applygray.eco.state." + worker.lane.state()
                                + "}  " + worker.lane.remaining()); ids.add("");
                    }
                }
                if (tab == 1) for (var pattern : controller.patterns()) {
                    StringBuilder text = new StringBuilder(pattern.getDefinition().toStack().getDisplayName());
                    for (var output : pattern.getOutputs()) text.append("  ").append(output.what().getDisplayName().getUnformattedText());
                    for (var input : pattern.getInputs()) for (var option : input.possibleInputs())
                        text.append("  ").append(option.what().getDisplayName().getUnformattedText());
                    lines.add(text.toString()); ids.add("");
                }
            }
            if (tab == 2) {
                for (EcoPart.Role role : EcoPart.Role.values()) for (EcoPart part : controller.parts(role)) {
                    String line = part.getStackForm().getDisplayName();
                    if (role == EcoPart.Role.MEMORY || role == EcoPart.Role.DRIVE)
                        line += "  " + part.inventory.getStackInSlot(0).getDisplayName();
                    if (role == EcoPart.Role.ENERGY)
                        line += "  " + (long) part.storedEnergy() + "/" + EcoTiers.energy(part.hardwareTier) + " AE";
                    if (role == EcoPart.Role.WORKER)
                        line += "  " + EcoConfig.batch(part.hardwareTier) + "/" + EcoConfig.assemblerCycleTicks + "t"
                                + "  {applygray.eco.state." + part.lane.state() + "}  " + part.lane.bufferedOutputs();
                    lines.add(line); ids.add("");
                }
            }
        }
        List<String> matches = new ArrayList<>(), matchingIds = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i).replace('\n', ' ');
            if (Arrays.stream(search.split("\\s+")).allMatch(word -> text.toLowerCase(Locale.ROOT).contains(word))) {
                matches.add(text.substring(0, Math.min(300, text.length()))); matchingIds.add(ids.get(i));
            }
        }
        pageCount = Math.max(1, (matches.size() + ROWS - 1) / ROWS);
        page = Math.clamp(page, 0, pageCount - 1);
        int start = page * ROWS, end = Math.min(matches.size(), start + ROWS);
        rows = String.join("\n", matches.subList(start, end));
        rowIds = String.join("\n", matchingIds.subList(start, end));
    }
}
