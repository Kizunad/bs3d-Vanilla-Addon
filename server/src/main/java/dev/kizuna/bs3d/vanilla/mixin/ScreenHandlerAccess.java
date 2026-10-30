package dev.kizuna.bs3d.vanilla.mixin;

import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 快捷取出复用原版合并与空槽插入规则。 */
@Mixin(ScreenHandler.class)
public interface ScreenHandlerAccess {
    @Invoker("insertItem")
    boolean bs3dVanilla$insertItem(ItemStack stack, int start, int end, boolean fromLast);
}
