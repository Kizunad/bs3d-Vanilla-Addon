package dev.kizuna.bs3d.vanilla.client.mixin;

import dev.kizuna.bs3d.vanilla.client.StationHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class HudMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void bs3dVanilla$hideHud(GuiGraphics context, float delta, CallbackInfo callback) {
        // 原版热栏、准星及其他 HUD 一起隐藏；离开附属页面立即恢复，不修改 F1 设置。
        var screen = Minecraft.getInstance().screen;
        if (screen instanceof StationHost host
                && host.stationPanel() != null
                && host.stationPanel().enabled()) {
            callback.cancel();
        }
    }
}
