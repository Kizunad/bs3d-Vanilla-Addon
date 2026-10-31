package dev.kizuna.bs3d.vanilla.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 26.2 的配方书页面不再公开配方书组件，展开时场景让出空间需要读取它。 */
@Mixin(AbstractRecipeBookScreen.class)
public interface RecipeBookScreenAccess {
    @Accessor("recipeBookComponent")
    RecipeBookComponent<?> bs3dVanilla$recipeBook();
}
