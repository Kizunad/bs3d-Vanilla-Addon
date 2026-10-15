package dev.kizuna.bs3d.vanilla.client.mixin;

import dev.kizuna.bs3d.vanilla.client.StationHost;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class ScreenBackgroundMixin {
    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void bs3dVanilla$transparent(DrawContext context, CallbackInfo callback) {
        // 只取消启用附属页面的全屏暗幕，工作站自己的槽位纹理保持原版。
        if ((Object) this instanceof StationHost host
                && host.stationPanel() != null
                && host.stationPanel().enabled()) {
            callback.cancel();
        }
    }
}
