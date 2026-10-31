package dev.kizuna.bs3d.vanilla.client.mixin;

import dev.kizuna.bs3d.vanilla.client.StationHost;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 创造页「物品栏」页签接入同一套人物与内景场景。服务器侧菜单是玩家菜单，页面槽换成真实槽后
 * 沿用现有事务；创造页光标只在客户端，携物时由原版创造写入处理。
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeInventoryScreenMixin
        extends AbstractContainerScreen<CreativeModeInventoryScreen.ItemPickerMenu> implements StationHost {
    protected CreativeInventoryScreenMixin(
            CreativeModeInventoryScreen.ItemPickerMenu handler, Inventory inventory, Component title) {
        super(handler, inventory, title);
    }

    @Shadow
    public abstract boolean isInventoryOpen();

    @Override
    public boolean stationSupported() {
        // 创造页的客户端菜单不在适配表中，服务器侧对应的始终是玩家菜单。
        return true;
    }

    @Override
    public boolean stationActive() {
        return isInventoryOpen();
    }

    @Override
    public Slot stationMenuSlot(Slot slot) {
        // 删除格等页面专有槽没有服务器对应槽。
        if (slot instanceof CreativeSlotAccess creative) {
            return creative.bs3dVanilla$slot();
        }
        return null;
    }

    @Override
    public boolean stationSharedCursor() {
        return false;
    }
}
