package dev.basehunt.bhclient.utils;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/**
 * Keeps an elytra on your back and swaps in a fresh one before the current one fails.
 *
 * <p>Meteor's ElytraFly can replace elytras, but it stops working once the chest slot holds a non-elytra
 * item, and it does not account for the two conditions that actually end a flight: the elytra breaking
 * mid-air, and an elytra arriving with any durability at all. This class handles both, so a flight can
 * start on a nearly-broken elytra and continue as long as spares last.
 *
 * <p>Spare lookups only scan the main inventory. {@code PlayerInventory.getStack} maps indices past
 * {@code MAIN_SIZE} onto the armour slots, so scanning the full size would count the elytra you are
 * wearing as a spare and could pick the chest slot as the replacement source.
 */
public final class ElytraManager {
    private ElytraManager() {
    }

    /** True when the chest slot holds an elytra. */
    public static boolean hasElytraEquipped() {
        if (ServerUtils.mc().player == null) return false;
        return ServerUtils.mc().player.getEquippedStack(EquipmentSlot.CHEST).getItem() == Items.ELYTRA;
    }

    public static ItemStack equippedElytra() {
        if (ServerUtils.mc().player == null) return ItemStack.EMPTY;
        return ServerUtils.mc().player.getEquippedStack(EquipmentSlot.CHEST);
    }

    /** Remaining durability of the worn elytra, or -1 when no elytra is worn. */
    public static int equippedRemaining() {
        ItemStack stack = equippedElytra();
        if (stack.getItem() != Items.ELYTRA) return -1;

        return stack.getMaxDamage() - stack.getDamage();
    }

    public static int equippedMaxDurability() {
        ItemStack stack = equippedElytra();
        if (stack.getItem() != Items.ELYTRA) return -1;

        return stack.getMaxDamage();
    }

    /** Remaining durability as a percentage of maximum, or -1 when no elytra is worn. */
    public static double equippedPercent() {
        int max = equippedMaxDurability();
        if (max <= 0) return -1;

        return equippedRemaining() * 100.0 / max;
    }

    /** Number of usable elytras carried in the inventory, excluding the one being worn. */
    public static int spareCount() {
        if (ServerUtils.mc().player == null) return 0;

        int count = 0;

        for (int slot = 0; slot < PlayerInventory.MAIN_SIZE; slot++) {
            if (ServerUtils.mc().player.getInventory().getStack(slot).getItem() == Items.ELYTRA) count++;
        }

        return count;
    }

    public static boolean hasSpare() {
        return spareCount() > 0;
    }

    /**
     * Durability of the best spare in the inventory, or -1 when there is none. Used to decide whether a
     * flight has enough elytra left to be worth starting at all.
     */
    public static int bestSpareRemaining() {
        int best = -1;

        for (int slot = 0; slot < PlayerInventory.MAIN_SIZE; slot++) {
            ItemStack stack = ServerUtils.mc().player.getInventory().getStack(slot);
            if (stack.getItem() != Items.ELYTRA) continue;

            int remaining = stack.getMaxDamage() - stack.getDamage();
            if (remaining > best) best = remaining;
        }

        return best;
    }

    /**
     * Puts the most durable spare elytra on. Works from any carried elytra regardless of its durability,
     * so a nearly-broken elytra is still a valid starting point.
     *
     * @return true when an elytra is worn afterwards.
     */
    public static boolean equipBest() {
        if (ServerUtils.mc().player == null) return false;
        if (hasElytraEquipped()) return true;

        int slot = bestSpareSlot();
        if (slot < 0) return false;

        InvUtils.move().from(slot).toArmor(2);
        return true;
    }

    /**
     * Swaps in a fresh elytra when the worn one is close to breaking.
     *
     * @param threshold remaining durability at or below which a replacement is triggered
     * @return true when a swap was performed.
     */
    public static boolean replaceIfBelow(int threshold) {
        if (threshold <= 0 || !hasElytraEquipped()) return false;

        if (equippedRemaining() > threshold) return false;

        int slot = bestSpareSlot();
        if (slot < 0) return false;

        InvUtils.move().from(slot).toArmor(2);
        return true;
    }

    private static int bestSpareSlot() {
        int bestSlot = -1;
        int bestRemaining = -1;

        for (int slot = 0; slot < PlayerInventory.MAIN_SIZE; slot++) {
            ItemStack stack = ServerUtils.mc().player.getInventory().getStack(slot);
            if (stack.getItem() != Items.ELYTRA) continue;

            int remaining = stack.getMaxDamage() - stack.getDamage();
            if (remaining > bestRemaining) {
                bestRemaining = remaining;
                bestSlot = slot;
            }
        }

        return bestSlot;
    }

    /** Short description of the elytra situation, for chat and webhook messages. */
    public static String status() {
        if (!hasElytraEquipped()) {
            return hasSpare() ? "none worn, %d spare".formatted(spareCount()) : "none";
        }

        String worn = "%d/%d (%d%%)".formatted(
            equippedRemaining(), equippedMaxDurability(), (int) equippedPercent());

        return hasSpare() ? "%s, %d spare".formatted(worn, spareCount()) : worn;
    }
}
