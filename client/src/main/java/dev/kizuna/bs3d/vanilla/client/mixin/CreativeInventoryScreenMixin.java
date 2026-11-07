package dev.kizuna.bs3d.vanilla.client.mixin;

import dev.kizuna.bs3d.vanilla.client.StationHost;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 创造页各页签接入同一套人物与内景场景。服务器侧菜单是玩家菜单，页面槽换成真实槽后
 * 沿用现有事务；类别页物品先经原版创造授权写槽，再提交穿戴意图。
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeInventoryScreenMixin
        extends AbstractContainerScreen<CreativeModeInventoryScreen.ItemPickerMenu> implements StationHost {
    protected CreativeInventoryScreenMixin(
            CreativeModeInventoryScreen.ItemPickerMenu handler, Inventory inventory, Component title) {
        super(handler, inventory, title);
    }

    @Override
    public boolean stationSupported() {
        // 创造页的客户端菜单不在适配表中，服务器侧对应的始终是玩家菜单。
        return true;
    }

    @Override
    public Slot stationMenuSlot(Slot slot) {
        // 删除格等页面专有槽没有服务器对应槽。
        if (slot instanceof CreativeSlotAccess creative) {
            return creative.bs3dVanilla$slot();
        }
        // 类别页快捷栏直接引用玩家 Inventory，物品目录和删除格没有真实来源。
        var player = minecraft.player;
        if (slot.container == player.getInventory()) {
            return player.inventoryMenu.slots.stream()
                    .filter(target -> target.container == slot.container)
                    .filter(target -> target.getContainerSlot() == slot.getContainerSlot())
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    @Override
    public boolean stationSharedCursor() {
        return false;
    }
}
