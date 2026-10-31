package dev.kizuna.bs3d.vanilla.mixin;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 快捷取出复用原版合并与空槽插入规则。 */
@Mixin(AbstractContainerMenu.class)
public interface ScreenHandlerAccess {
    @Invoker("moveItemStackTo")
    boolean bs3dVanilla$insertItem(ItemStack stack, int start, int end, boolean fromLast);
}
