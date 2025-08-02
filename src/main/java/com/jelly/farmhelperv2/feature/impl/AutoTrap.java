package com.jelly.farmhelperv2.feature.impl;

import com.jelly.farmhelperv2.config.FarmHelperConfig;
import com.jelly.farmhelperv2.failsafe.FailsafeManager;
import com.jelly.farmhelperv2.feature.FeatureManager;
import com.jelly.farmhelperv2.feature.IFeature;
import com.jelly.farmhelperv2.handler.BaritoneHandler;
import com.jelly.farmhelperv2.handler.GameStateHandler;
import com.jelly.farmhelperv2.handler.MacroHandler;
import com.jelly.farmhelperv2.handler.RotationHandler;
import com.jelly.farmhelperv2.pathfinder.FlyPathFinderExecutor;
import com.jelly.farmhelperv2.util.*;
import com.jelly.farmhelperv2.util.helper.Clock;
import com.jelly.farmhelperv2.util.helper.RotationConfiguration;
import com.jelly.farmhelperv2.util.helper.Target;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemFishingRod;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.StringUtils;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.*;
import java.util.List;


public class AutoTrap implements IFeature {

    private final Minecraft mc = Minecraft.getMinecraft();
    private static AutoTrap instance;

    public static AutoTrap getInstance() {
        if (instance == null) {
            instance = new AutoTrap();
        }
        return instance;
    }

    private boolean enabled = false;
    @Setter
    private boolean manuallyStarted = false;
    private long lastChecked = 0;
    @Getter
    private MainState mainState = MainState.NONE;
    @Getter
    private TravelState travelState = TravelState.NONE;
    @Getter
    private PetSwapState petSwapState = PetSwapState.NONE;
    @Getter
    private TrapState trapState = TrapState.PREPARE_FOR_NEXT_TRAP;
    @Getter
    private BuyState buyState = BuyState.NONE;
    private final int STUCK_DELAY = (int) (7_500 + FarmHelperConfig.macroGuiDelay + FarmHelperConfig.macroGuiDelayRandomness);
    @Getter
    private final Clock stuckClock = new Clock();
    @Getter
    private final Clock delayClock = new Clock();
    private BlockPos positionBeforeTp;
    private int currentTrapNumber = 1;
    private Entity nextTrap = null;
    private String currentTrapBait = "";
    private boolean checked = false;

    private boolean petSwapped = false;

    private final ArrayList<Integer> pestSlotsToClick = new ArrayList<>();

    private final ArrayList<Pair<String, Integer>> itemsToBuy = new ArrayList<>();

    private String getBaitItem() {
        return this.BAIT_ITEM[FarmHelperConfig.autoTrapRefillMaterial];
    }

    private final String[] BAIT_ITEM = {"Compost", "Honey Jar", "Dung", "Plant Matter", "Tasty Cheese"};

    private String getSavePestCrop() {
        return this.CROP_NAMES[FarmHelperConfig.autoTrapSelectiveEmptySavePest];
    }

    private final String[] CROP_NAMES = {"Melon", "Sugar Cane", "Cocoa Beans", "Carrot", "Nether Wart", "Wheat", "Potato", "Mushroom", "Pumpkin", "Cactus", "None"};

    private String getSavePest() {
        return this.PEST_NAMES[FarmHelperConfig.autoTrapSelectiveEmptySavePest];
    }

    private final String[] PEST_NAMES = {"Earthworm", "Mosquito", "Moth", "Cricket", "Beetle", "Fly", "Locust", "Slug", "Rat", "Mite", "Field Mouse"};

    public BlockPos trapPos() {
        return new BlockPos(FarmHelperConfig.autoTrapX, FarmHelperConfig.autoTrapY, FarmHelperConfig.autoTrapZ);
    }

    private boolean isTrapPosSet() {
        return FarmHelperConfig.autoTrapX != 0 || FarmHelperConfig.autoTrapY != 0 || FarmHelperConfig.autoTrapZ != 0;
    }

    @Override
    public String getName() {
        return "Auto Trap";
    }

    @Override
    public boolean isRunning() {
        return enabled;
    }

    @Override
    public boolean shouldPauseMacroExecution() {
        return true;
    }

    @Override
    public boolean shouldStartAtMacroStart() {
        return false;
    }

    @Override
    public void start() {
        if (enabled) return;
//        if (!canEnableMacro(manuallyStarted)) return;
        if (!GameStateHandler.getInstance().inGarden()) return;
        if (!isToggled()) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (MacroHandler.getInstance().isMacroToggled()) {
            MacroHandler.getInstance().pauseMacro();
            MacroHandler.getInstance().getCurrentMacro().ifPresent(am -> am.setSavedState(Optional.empty()));
            KeyBindUtils.stopMovement();
        }
        resetStatesAfterMacroDisabled();
        enabled = true;
        stuckClock.reset();
        setTrapState(TrapState.PREPARE_FOR_NEXT_TRAP);
        LogUtils.sendWarning("[Auto Trap] Starting...");
        IFeature.super.start();
    }

    @Override
    public void stop() {
        enabled = false;
        manuallyStarted = false;
        LogUtils.sendWarning("[Auto Trap] Stopping...");
        RotationHandler.getInstance().reset();
        AutoBazaar.getInstance().stop();
        PlayerUtils.closeScreen();
        KeyBindUtils.stopMovement();
        FlyPathFinderExecutor.getInstance().stop();
        BaritoneHandler.stopPathing();
        MacroHandler.getInstance().getCurrentMacro().ifPresent(cm -> cm.getCheckOnSpawnClock().schedule(5_000));
        resetStatesAfterMacroDisabled();
        IFeature.super.stop();
    }


    @Override
    public void resetStatesAfterMacroDisabled() {
        setMainState(MainState.NONE);
        setTravelState(TravelState.NONE);
        setBuyState(BuyState.NONE);
        setTrapState(TrapState.PREPARE_FOR_NEXT_TRAP);
        stuckClock.reset();
        delayClock.reset();
        checked = false;
        positionBeforeTp = null;
        itemsToBuy.clear();
        pestSlotsToClick.clear();
        manuallyStarted = false;
        currentTrapNumber = 1;
        nextTrap = null;
        currentTrapBait = "";
        petSwapped = false;
    }

    @Override
    public boolean isToggled() {
        return FarmHelperConfig.autoTrap;
    }

    @Override
    public boolean shouldCheckForFailsafes() {
        return travelState != TravelState.TELEPORT_TO_PLOT && travelState != TravelState.WAIT_FOR_TP;
    }


    public boolean canEnableMacro(boolean manual) {
        if (!isToggled()) return false;
        if (isRunning()) return false;
        if (!GameStateHandler.getInstance().inGarden()) return false;
        if (mc.thePlayer == null || mc.theWorld == null) return false;
        if (!MacroHandler.getInstance().isMacroToggled() && !manual) return false;
        if (FeatureManager.getInstance().isAnyOtherFeatureEnabled(this))
            return false;
        if (!FailsafeManager.getInstance().getEmergencyQueue().isEmpty()) return false;
        if (!isTrapPosSet()) {
            LogUtils.sendError("[Auto Trap] Trap position not set, disabling...");
            FarmHelperConfig.autoTrap = false;
            return false;
        }
        if (GameStateHandler.getInstance().getServerClosingSeconds().isPresent()) {
            LogUtils.sendError("[Auto Trap] Server is closing in " + GameStateHandler.getInstance().getServerClosingSeconds().get() + " seconds!");
            return false;
        }
        if (!manual && FarmHelperConfig.pauseAutoTrapDuringJacobsContest && GameStateHandler.getInstance().inJacobContest()) {
            LogUtils.sendError("[Auto Trap] Jacob's contest is active, skipping...");
            return false;
        }

        int vacuum = InventoryUtils.getSlotIdOfItemInHotbar("Vacuum");
        if (vacuum == -1) {
            LogUtils.sendError("[Auto Trap] Failed to find vacuum in hotbar, skipping...!");
            FarmHelperConfig.autoTrap = false;
            return false;
        }

        if (GameStateHandler.getInstance().getTrapsFull() == 0 &&
                (GameStateHandler.getInstance().getTrapsNoBait() == 0 ||
                        (GameStateHandler.getInstance().inJacobContest() && FarmHelperConfig.autoTrapDontRefillDuringContest))) {
            LogUtils.sendError("[Auto Trap] No trap full or missing bait...!");
            return false;
        }

        if (FarmHelperConfig.autoTrapSelectiveEmpty) {
            if (lastChecked > GameStateHandler.getInstance().getLastTrapChange() && !currentContestCropEqualsSavePestCrop()) {
                LogUtils.sendError("[Auto Trap] Already checked since last trap change, skipping...!");
                return false;
            }
        }

        return true;
    }

    @Nullable
    private Entity getNextTrap() {
        return mc.theWorld.getLoadedEntityList().
                stream().
                filter(entity -> StringUtils.stripControlCodes(entity.getName()).contains("TRAP #" + currentTrapNumber))
                .min(Comparator.comparingDouble(entity -> entity.getDistanceSqToCenter(mc.thePlayer.getPosition()))).orElse(null);
    }

    @SubscribeEvent
    public void onTickMain(TickEvent.ClientTickEvent event) {
        if (!isRunning()) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (!isToggled()) return;
        if (event.phase != TickEvent.Phase.END) return;
        if (!FailsafeManager.getInstance().getEmergencyQueue().isEmpty()) return;
        if (FailsafeManager.getInstance().triggeredFailsafe.isPresent()) {
            stop();
            return;
        }
        if (!GameStateHandler.getInstance().inGarden()) return;

        if (stuckClock.isScheduled() && stuckClock.passed()) {
            LogUtils.sendError("[Auto Trap] The player is stuck! Disabling feature");
            FarmHelperConfig.autoTrap = false;
            setMainState(MainState.END);
            return;
        }

        if (delayClock.isScheduled() && !delayClock.passed()) return;

        switch (mainState) {
            case NONE:
                setMainState(MainState.TRAVEL);
                delayClock.schedule(getRandomDelay());
                break;
            case TRAVEL:
                onTravelState();
                break;
            case AUTO_SELL:
                if (FarmHelperConfig.autoTrapAutosell) {
                    AutoSell.getInstance().start();
                    delayClock.schedule(getRandomDelay());
                }
                if (FarmHelperConfig.autoTrapPetSwapMode != 0){
                    setMainState(MainState.PET_SWAP);
                } else {
                    setMainState(MainState.TRAP);
                }
                break;
            case PET_SWAP:
                if (AutoSell.getInstance().isRunning()) {
                    stuckClock.schedule(STUCK_DELAY);
                    delayClock.schedule(getRandomDelay());
                    return;
                }
                onPetSwapState();
                break;
            case TRAP:
                onTrapState();
                break;
            case END:
                if (mc.currentScreen != null) {
                    PlayerUtils.closeScreen();
                    delayClock.schedule(1_000 + Math.random() * 500);
                    break;
                }
                lastChecked = System.currentTimeMillis();
                if (manuallyStarted) {
                    stop();
                } else {
                    stop();
                    MacroHandler.getInstance().triggerWarpGarden(true, true, false);
                    delayClock.schedule(getRandomDelay());
                }
                break;
        }

    }

    private void onTravelState() {
        if (mc.currentScreen != null) {
            KeyBindUtils.stopMovement();
            PlayerUtils.closeScreen();
            delayClock.schedule(getRandomDelay());
            return;
        }


        switch (travelState) {
            case NONE:
//                if (mc.thePlayer.getPosition().distanceSq(trapPos()) < 100 && !FarmHelperConfig.autoTrapTpOnly) {
//                    setTravelState(TravelState.GO_TO_TRAPS);
//                    stuckClock.schedule(30_000L);
//                } else {
                setTravelState(TravelState.TELEPORT_TO_PLOT);
//                }
                break;
            case TELEPORT_TO_PLOT:
                positionBeforeTp = mc.thePlayer.getPosition();
                setTravelState(TravelState.WAIT_FOR_TP);
                mc.thePlayer.sendChatMessage("/tptoplot " + FarmHelperConfig.autoTrapPlot);
                delayClock.schedule(getRandomDelay());
                break;
            case WAIT_FOR_TP:
                BlockPos pos = mc.thePlayer.getPosition();
                if (pos.equals(positionBeforeTp) && !pos.equals(trapPos())) {
                    LogUtils.sendDebug("[Auto Trap] Waiting for teleportation...");
                    break;
                }
                if (PlayerUtils.isPlayerSuffocating()) {
                    stuckClock.schedule(5_000L);
                    break;
                }
                if (FarmHelperConfig.autoTrapTpOnly) {
                    setTravelState(TravelState.END);
                } else {
                    // not for now
                    setTravelState(TravelState.GO_TO_TRAPS);
                    setTravelState(TravelState.END);
                }
                break;
            case GO_TO_TRAPS:
                // not for now
                setTravelState(TravelState.END);
                break;
            case END:
                setMainState(MainState.AUTO_SELL);
                setTravelState(TravelState.NONE);
                break;
        }
    }


    private void onPetSwapState() {
        switch (petSwapState) {
            case NONE:
                switch (FarmHelperConfig.autoTrapPetSwapMode) {
                    case 0:
                        setPetSwapState(PetSwapState.END);
                        break;
                    case 1:
                        setPetSwapState(PetSwapState.ARMOR_SWAP);
                        break;
                    case 2:
                        setPetSwapState(PetSwapState.ROD_SWAP);
                        break;
                    case 3:
                        setPetSwapState(PetSwapState.PET_SWAP);
                        break;
                }
                break;
            case ARMOR_SWAP:
                int armor;
                List<String> equip;
                if (!petSwapped) {
                    armor = FarmHelperConfig.autoTrapArmorBefore;
                    equip = Arrays.asList(FarmHelperConfig.autoTrapEqBefore.split("\\|"));
                } else {
                    armor = FarmHelperConfig.autoTrapArmorAfter;
                    equip = Arrays.asList(FarmHelperConfig.autoTrapEqAfter.split("\\|"));
                }

                AutoWardrobe.getInstance().setReequipIfEquipped(true);
                AutoWardrobe.getInstance().swapTo(armor, equip);
                setPetSwapState(PetSwapState.WAITING_FOR_SWAP);
                break;
            case WAITING_FOR_SWAP:
                if (AutoWardrobe.getInstance().isRunning()) {
                    return;
                }
                setPetSwapState(PetSwapState.END);
                break;
            case ROD_SWAP:
                if (getRod()) {
                    break;
                }
                KeyBindUtils.rightClick();
                delayClock.schedule(getRandomDelay());
                setPetSwapState(PetSwapState.END);
                break;
            case PET_SWAP:
                // not for now
                setPetSwapState(PetSwapState.END);
                break;
            case END:
                if (!petSwapped) {
                    setMainState(MainState.TRAP);
                    petSwapped = true;
                } else {
                    setMainState(MainState.END);
                }
                setPetSwapState(PetSwapState.NONE);
                break;
        }
    }


    private void onTrapState() {
        switch (trapState) {
            case PREPARE_FOR_NEXT_TRAP:
                if (getVacuum()) {
                    break;
                }
                if (mc.currentScreen != null) {
                    PlayerUtils.closeScreen();
                }
                if (currentTrapNumber > 3) {
                    setTrapState(TrapState.END);
                    break;
                }
                nextTrap = getNextTrap();
                currentTrapNumber++;
                checked = false;
                setTrapState(TrapState.ROTATE_TO_TRAP);
                delayClock.schedule(getRandomDelay());
                break;
            case ROTATE_TO_TRAP:
                if (mc.currentScreen != null) {
                    PlayerUtils.closeScreen();
                    delayClock.schedule(getRandomDelay());
                    break;
                }
                if (nextTrap == null) {
                    LogUtils.sendError("[Auto Trap] Could not find next trap! Skipping...");
                    setTrapState(TrapState.PREPARE_FOR_NEXT_TRAP);
                    break;
                } else {
                    RotationHandler.getInstance().easeTo(
                            new RotationConfiguration(
                                    new Target(nextTrap),
                                    FarmHelperConfig.getRandomRotationTime(),
                                    null
                            )
                    );
                }
                if (FlyPathFinderExecutor.getInstance().isRunning() || BaritoneHandler.isWalkingToGoalBlock(0.5)) {
                    break;
                }
                setTrapState(TrapState.OPEN_TRAP);
                break;
            case OPEN_TRAP:
                if (mc.currentScreen != null) {
                    PlayerUtils.closeScreen();
                    delayClock.schedule(getRandomDelay());
                    break;
                }
                MovingObjectPosition mop = mc.objectMouseOver;
                if (mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY) {
                    Entity entity = mop.entityHit;
                    if (entity.equals(nextTrap) || entity.getDistanceToEntity(nextTrap) < 1.5) {
                        KeyBindUtils.rightClick();
                        if (!checked) {
                            setTrapState(TrapState.CHECK_TRAP);
                        } else {
                            setTrapState(TrapState.FILL_TRAP_PICKUP);
                        }
                        RotationHandler.getInstance().reset();
                        stuckClock.schedule(STUCK_DELAY);
                        delayClock.schedule(getRandomDelay());
                        break;
                    }
                } else {
                    if (RotationHandler.getInstance().isRotating()) break;
                    if (FarmHelperConfig.autoTrapTpOnly) {
                        LogUtils.sendError("[Auto Trap] Could not reach trap! Skipping...");
                        setTrapState(TrapState.PREPARE_FOR_NEXT_TRAP);
                        break;
                    }
                    if (mc.thePlayer.getDistanceToEntity(nextTrap) > 3) {
                        setMainState(MainState.TRAVEL);
                        setTrapState(TrapState.PREPARE_FOR_NEXT_TRAP);
                    } else {
                        RotationHandler.getInstance().easeTo(
                                new RotationConfiguration(
                                        new Target(nextTrap),
                                        FarmHelperConfig.getRandomRotationTime(),
                                        () -> {
                                            KeyBindUtils.onTick(mc.gameSettings.keyBindForward);
                                            RotationHandler.getInstance().reset();
                                        }
                                )
                        );
                    }
                    delayClock.schedule(1_000 + Math.random() * 500);
                }
                break;
            case CHECK_TRAP:
                if (isNotInTrapInventoryThenOpen()) break;

                checked = true;

                if (FarmHelperConfig.autoTrapRefill && (!FarmHelperConfig.autoTrapDontRefillDuringContest || !GameStateHandler.getInstance().inJacobContest())) {
                    Slot refillSlot = InventoryUtils.getSlotOfIdInContainer(11);
                    int stackSize = refillSlot.getStack().stackSize;
                    if (stackSize < FarmHelperConfig.autoTrapRefillAtMaterialLeft) {

                        itemsToBuy.clear();

                        String itemName = StringUtils.stripControlCodes(refillSlot.getStack().getDisplayName());
                        int amount;
                        if (itemName.equalsIgnoreCase("Trap Bait")) {
                            itemName = getBaitItem();
                        }
                        amount = Math.min(FarmHelperConfig.autoTrapRefillToMaterial - stackSize, 64 - stackSize);
                        currentTrapBait = itemName;
                        if (amount > 0) {
                            itemsToBuy.add(Pair.of(itemName, amount));
                            setTrapState(TrapState.BUY_STATE);
                            delayClock.schedule(getRandomDelay());
                            break;
                        }
                    }
                }

                // if not refilling, emptying straight away
                setTrapState(TrapState.DECIDE_EMPTYING);
                break;
            case BUY_STATE:
                onBuyState();
                break;
            case FILL_TRAP_PICKUP:
                if (isNotInTrapInventoryThenOpen()) break;

                Slot slot = InventoryUtils.getSlotOfItemInInventory(currentTrapBait);
                if (slot == null) {
                    LogUtils.sendWarning("slot null");
                    break;
                }

                ContainerChest chest = (ContainerChest) mc.thePlayer.openContainer;

                InventoryUtils.clickSlotWithId(slot.slotNumber + 27, InventoryUtils.ClickType.LEFT, InventoryUtils.ClickMode.PICKUP, chest.windowId);
                delayClock.schedule(FarmHelperConfig.getRandomGUIMacroDelay());
                setTrapState(TrapState.FILL_TRAP_PLACE);
                break;
            case FILL_TRAP_PLACE:
                if (isNotInTrapInventoryThenOpen()) break;

                InventoryUtils.clickContainerSlot(11, InventoryUtils.ClickType.LEFT, InventoryUtils.ClickMode.PICKUP);
                delayClock.schedule(FarmHelperConfig.getRandomGUIMacroDelay());
                setTrapState(TrapState.DECIDE_EMPTYING);
                break;
            case DECIDE_EMPTYING:
                if (isNotInTrapInventoryThenOpen()) break;

                pestSlotsToClick.clear();
                if (FarmHelperConfig.autoTrapSelectiveEmpty) {
                    if (!currentContestCropEqualsSavePestCrop()) {
                        for (int i = 15; i >= 13; i--) {
                            String name = StringUtils.stripControlCodes(mc.thePlayer.openContainer.getSlot(i).getStack().getDisplayName());
                            if (getSavePest().equalsIgnoreCase(name) || name.equalsIgnoreCase("Pest Slot")) {
                                continue;
                            }
                            pestSlotsToClick.add(i);
                        }
                        if (pestSlotsToClick.isEmpty()) {
                            setTrapState(TrapState.PREPARE_FOR_NEXT_TRAP);
                            break;
                        } else if (pestSlotsToClick.size() == 3) {
                            setTrapState(TrapState.EMPTY_TRAP);
                        } else {
                            setTrapState(TrapState.SELECTIVE_EMPTYING);
                            break;
                        }
                    }

                }

                // this is slot number 16
                Slot releaseSlot = InventoryUtils.getSlotOfItemInContainer("Release All Pests");
                if (releaseSlot == null) {
                    LogUtils.sendWarning("No release slot found!");
                    break;
                }
                ItemStack itemLore = releaseSlot.getStack();
                List<String> lore = InventoryUtils.getItemLore(itemLore);
                if (lore.stream().anyMatch(l -> l.contains("Click to release!"))) {
                    setTrapState(TrapState.EMPTY_TRAP);
                    KeyBindUtils.rightClick(); // use vacuum ability
                } else if (lore.stream().anyMatch(l -> l.contains("There are no Pests to release!"))) {
                    setTrapState(TrapState.PREPARE_FOR_NEXT_TRAP);
                    LogUtils.sendWarning("[Auto Trap] Already emptied the trap!");
                } else {
                    setTrapState(TrapState.PREPARE_FOR_NEXT_TRAP);
                    LogUtils.sendError("[Auto Trap] Failed to empty trap!");
                }
                delayClock.schedule(getRandomDelay());
                break;
            case EMPTY_TRAP:
                if (isNotInTrapInventoryThenOpen()) break;

                InventoryUtils.clickContainerSlot(16, InventoryUtils.ClickType.LEFT, InventoryUtils.ClickMode.PICKUP);
                LogUtils.sendWarning("[Auto Trap] Emptied the trap!");

                setTrapState(TrapState.PREPARE_FOR_NEXT_TRAP);
                delayClock.schedule(FarmHelperConfig.getRandomGUIMacroDelay());
                break;
            case SELECTIVE_EMPTYING:
                if (isNotInTrapInventoryThenOpen()) break;

                if (!pestSlotsToClick.isEmpty()) {
                    InventoryUtils.clickContainerSlot(pestSlotsToClick.get(0), InventoryUtils.ClickType.LEFT, InventoryUtils.ClickMode.PICKUP);
                    pestSlotsToClick.remove(0);
                    delayClock.schedule(FarmHelperConfig.getRandomGUIMacroDelay());
                    break;
                }
                setTrapState(TrapState.PREPARE_FOR_NEXT_TRAP);
                break;
            case END:
                setTrapState(TrapState.PREPARE_FOR_NEXT_TRAP);
                if (FarmHelperConfig.autoTrapPetSwapMode != 0){
                    setMainState(MainState.PET_SWAP);
                } else {
                    setMainState(MainState.END);
                }
                delayClock.schedule(getRandomDelay());
                break;
        }
    }

    private void onBuyState() {
        switch (buyState) {
            case NONE:
                if (itemsToBuy.isEmpty()) {
                    setBuyState(BuyState.END);
                    break;
                }
                if (InventoryUtils.getAmountOfItemInInventory(itemsToBuy.get(0).getLeft()) >= itemsToBuy.get(0).getRight()) {
                    LogUtils.sendDebug("[Auto Trap] Already have " + itemsToBuy.get(0).getLeft() + ", skipping...");
                    itemsToBuy.remove(0);
                    break;
                } else if (InventoryUtils.getAmountOfItemInInventory(itemsToBuy.get(0).getLeft()) > 1) {
                    LogUtils.sendDebug("[Auto Trap] Not enough " + itemsToBuy.get(0).getLeft() + " in inventory, skipping...");
                    itemsToBuy.remove(0);
                    break;
                }
                setBuyState(BuyState.BUY_ITEMS);
                break;
            case BUY_ITEMS:
                AutoBazaar.getInstance().buy(itemsToBuy.get(0).getLeft(), itemsToBuy.get(0).getRight(), FarmHelperConfig.autoTrapMaxSpendLimit * 1_000_000);
                setBuyState(BuyState.WAIT_FOR_AUTOBAZAAR_FINISH);
                break;
            case WAIT_FOR_AUTOBAZAAR_FINISH:
                if (AutoBazaar.getInstance().wasPriceManipulated()) {
                    LogUtils.sendDebug("[Auto Trap] Price manipulation detected, stopping...");
                    stop();
                    break;
                }
                if (AutoBazaar.getInstance().hasFailed()) {
                    LogUtils.sendDebug("[Auto Trap] Couldn't buy " + itemsToBuy.get(0).getLeft() + " amount " + itemsToBuy.get(0).getRight() + ", retrying...");
                    setBuyState(BuyState.BUY_ITEMS);
                    PlayerUtils.closeScreen();
                    delayClock.schedule(FarmHelperConfig.getRandomGUIMacroDelay());
                    break;
                }
                if (AutoBazaar.getInstance().hasSucceeded()) {
                    LogUtils.sendDebug("[Auto Trap] Bought " + itemsToBuy.get(0).getLeft() + " amount " + itemsToBuy.get(0).getRight());
                    itemsToBuy.remove(0);
                    setBuyState(BuyState.NONE);
                    PlayerUtils.closeScreen();
                    delayClock.schedule(FarmHelperConfig.getRandomGUIMacroDelay());
                }
                break;
            case END:
                setBuyState(BuyState.NONE);
                setTrapState(TrapState.OPEN_TRAP);
                break;
        }
    }

    enum MainState {
        NONE,
        TRAVEL,
        AUTO_SELL,
        PET_SWAP,
        TRAP,
        END
    }

    private void setMainState(MainState state) {
        mainState = state;
        LogUtils.sendDebug("[Auto Trap] Main state: " + state.name());
        stuckClock.schedule(STUCK_DELAY);
    }

    enum TravelState {
        NONE,
        TELEPORT_TO_PLOT,
        WAIT_FOR_TP,
        GO_TO_TRAPS,
        END
    }

    private void setTravelState(TravelState state) {
        travelState = state;
        LogUtils.sendDebug("[Auto Trap] Travel state: " + state.name());
        stuckClock.schedule(STUCK_DELAY);
    }

    enum PetSwapState {
        NONE,
        PET_SWAP,
        ROD_SWAP,
        ARMOR_SWAP,
        WAITING_FOR_SWAP,
        END
    }

    private void setPetSwapState(PetSwapState state) {
        petSwapState = state;
        LogUtils.sendDebug("[Auto Trap] PetSwap state: " + state.name());
        stuckClock.schedule(STUCK_DELAY);
    }

    enum TrapState {
        PREPARE_FOR_NEXT_TRAP,
        ROTATE_TO_TRAP,
        SWAP_TO_VACUUM,
        OPEN_TRAP,
        CHECK_TRAP,
        BUY_STATE,
        FILL_TRAP_PICKUP,
        FILL_TRAP_PLACE,
        DECIDE_EMPTYING,
        EMPTY_TRAP,
        SELECTIVE_EMPTYING,
        AUTO_SELL,
        END
    }

    private void setTrapState(TrapState state) {
        trapState = state;
        LogUtils.sendDebug("[Auto Trap] Trap state: " + state.name());
        stuckClock.schedule(STUCK_DELAY);
    }

    enum BuyState {
        NONE,
        BUY_ITEMS,
        WAIT_FOR_AUTOBAZAAR_FINISH,
        END
    }

    private void setBuyState(BuyState state) {
        buyState = state;
        LogUtils.sendDebug("[Auto Trap] Buy state: " + state.name());
        stuckClock.schedule(STUCK_DELAY);
    }

    private long getRandomDelay() {
        return (long) (500 + Math.random() * 500);
    }

    public boolean getVacuum() {
        ItemStack currentItem = mc.thePlayer.getHeldItem();
        if (currentItem == null || !currentItem.getDisplayName().contains("Vacuum")) {
            int vacuum = InventoryUtils.getSlotIdOfItemInHotbar("Vacuum");
            if (vacuum == -1) {
                LogUtils.sendError("[Auto Trap] Failed to find vacuum in hotbar!");
                FarmHelperConfig.autoTrap = false;
                setMainState(MainState.END);
                MacroHandler.getInstance().disableMacro();
                return true;
            }
            mc.thePlayer.inventory.currentItem = vacuum;
            delayClock.schedule((long) (200 + Math.random() * 200));
            return true;
        }
        return false;
    }

    public boolean getRod() {
        ItemStack currentItem = mc.thePlayer.getHeldItem();
        if (currentItem == null || !(currentItem.getItem() instanceof ItemFishingRod)) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
                if (stack != null && stack.getItem() instanceof ItemFishingRod) {
                    mc.thePlayer.inventory.currentItem = i;
                    delayClock.schedule((long) (200 + Math.random() * 200));
                    return true;
                }
            }
            LogUtils.sendError("Could not find a fishing rod in hotbar, disabling rod swap...");
            FarmHelperConfig.autoTrapPetSwapMode = 0; //disable
            setPetSwapState(PetSwapState.END);
            return true;
        }
        return false;
    }

    private boolean currentContestCropEqualsSavePestCrop() {
        Optional<FarmHelperConfig.CropEnum> possibleCrop = GameStateHandler.getInstance().getJacobsContestCrop();
        if (possibleCrop.isPresent()) {
            FarmHelperConfig.CropEnum crop = possibleCrop.get();
            if (crop.getLocalizedName().equalsIgnoreCase(getSavePestCrop())) {
                return true;
            }
        }
        return false;
    }

    private boolean isNotInTrapInventoryThenOpen() {
        String invName = InventoryUtils.getInventoryName();
        if (invName == null) {
            setTrapState(TrapState.OPEN_TRAP);
            return true;
        }
        if (!invName.contains("Trap")) {
            PlayerUtils.closeScreen();
            setTrapState(TrapState.ROTATE_TO_TRAP);
            delayClock.schedule(getRandomDelay());
            stuckClock.schedule(STUCK_DELAY);
            return true;
        }

        return false;
    }

//    @SubscribeEvent(receiveCanceled = true)
//    public void onChatMessageReceived(ClientChatReceivedEvent event) {
//        if (FarmHelperConfig.autoTrap && event.type == 0 && event.message != null) {
//            String formattedText = event.message.getFormattedText();
//            if (formattedText.contains("§2§lGOTCHA! §7Your trap caught a §2Pest §7in §aPlot") ||
//                    formattedText.contains("§2§lGOTCHA! §7Your traps caught a §2Pest §7in §aPlot")) {
//                GameStateHandler.getInstance().setLastTrapChange(System.currentTimeMillis());
//            }
//        }
//    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!isRunning()) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (FarmHelperConfig.streamerMode) return;
        if (!FarmHelperConfig.highlightTrapLocation) return;
        if (!isTrapPosSet()) return;
        RenderUtils.drawBlockBox(trapPos(), new Color(0, 155, 255, 50));
    }
}

