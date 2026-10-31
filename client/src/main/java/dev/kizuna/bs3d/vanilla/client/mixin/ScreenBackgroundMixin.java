package dev.kizuna.bs3d.vanilla.client.mixin;

import dev.kizuna.bs3d.vanilla.client.PlayerInventoryView;
import dev.kizuna.bs3d.vanilla.client.StationHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class ScreenBackgroundMixin {
    @Shadow @Final protected Minecraft minecraft;

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void bs3dVanilla$transparent(
            GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        // 工作站重写的背景方法先调用这里画暗幕再画纹理；这里只取消暗幕，槽位纹理保持原版。
        if (PlayerInventoryView.textureOnly()) {
            callback.cancel();
            return;
        }
        if ((Object) this instanceof StationHost host
                && host.stationPanel() != null
                && host.stationPanel().enabled()) {
            // 与原版结尾一致交还延后字幕；HUD 隐藏时没有待画字幕，这里不会多画。
            minecraft.gui.hud.extractDeferredSubtitles();
            callback.cancel();
        }
    }

    @Redirect(method = "extractRenderStateWithTooltipAndSubtitles", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/Screen;extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"))
    private void bs3dVanilla$background(
            Screen screen, GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // 26.2 去掉了单独的工作站纹理方法，裁接改为包住整个背景调用；槽位物品和悬停提示仍只绘制一次。
        if (screen instanceof AbstractContainerScreen<?> container) {
            PlayerInventoryView.drawBackground(
                    container,
                    context,
                    () -> ((StationHost) container).stationBackdrop(context, mouseX, mouseY, delta),
                    () -> screen.extractBackground(context, mouseX, mouseY, delta));
            return;
        }
        screen.extractBackground(context, mouseX, mouseY, delta);
    }
}
