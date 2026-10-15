package dev.kizuna.bs3d.vanilla.client.mixin;

import dev.kizuna.bs3d.vanilla.client.StationHost;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public abstract class HudMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void bs3dVanilla$hideHud(DrawContext context, float delta, CallbackInfo callback) {
        // 原版热栏、准星及其他 HUD 一起隐藏；离开附属页面立即恢复，不修改 F1 设置。
        var screen = MinecraftClient.getInstance().currentScreen;
        if (screen instanceof StationHost host
                && host.stationPanel() != null
                && host.stationPanel().enabled()) {
            callback.cancel();
        }
    }
}
