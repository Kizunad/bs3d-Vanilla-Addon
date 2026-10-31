package dev.kizuna.bs3d.vanilla.client.mixin;

import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 创造页「物品栏」页签的页面槽包着玩家菜单的真实槽，场景按真实槽提交事务。 */
@Mixin(targets = "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen$SlotWrapper")
public interface CreativeSlotAccess {
    @Accessor("target")
    Slot bs3dVanilla$slot();
}
