package dev.kizuna.bs3d.vanilla.client.mixin;

import dev.kizuna.bs3d.vanilla.client.*;
import dev.kizuna.bs3d.vanilla.protocol.VanillaMenus;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/** 只在已适配的原版页面插入两侧场景，保留原版 Screen 和所有配方控件。 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin extends Screen implements StationHost {
    @Shadow protected int x;
    @Shadow protected int y;
    @Shadow protected int backgroundWidth;
    @Shadow @Final protected ScreenHandler handler;

    @Shadow
    private Slot getSlotAt(double x, double y) {
        throw new AssertionError();
    }

    @Unique private StationPanel bs3dVanilla$panel;

    protected HandledScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void bs3dVanilla$initialize(CallbackInfo callback) {
        // 改分辨率保留已有场景相机；不支持的菜单不创建附属输入层。
        if (bs3dVanilla$panel == null
                && VanillaClient.instance() != null
                && VanillaMenus.supports(handler)) {
            bs3dVanilla$panel = new StationPanel((HandledScreen<?>) (Object) this);
        }
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void bs3dVanilla$render(
            DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        // 先画场景，再让原版绘制槽位、光标和悬停提示，保留原版前后关系。
        if (bs3dVanilla$panel != null) {
            bs3dVanilla$panel.draw(context, mouseX, mouseY);
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void bs3dVanilla$press(
            double x, double y, int button, CallbackInfoReturnable<Boolean> callback) {
        // 仅消费附属区域或等待中的操作，其他点击继续交给原版。
        if (bs3dVanilla$panel != null && bs3dVanilla$panel.press(x, y, button)) {
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void bs3dVanilla$drag(
            double x,
            double y,
            int button,
            double dx,
            double dy,
            CallbackInfoReturnable<Boolean> callback) {
        // 场景旋转不触发原版格子分配；普通原版拖放仍走原方法。
        if (bs3dVanilla$panel != null && bs3dVanilla$panel.drag(x, y, button, dx, dy)) {
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void bs3dVanilla$release(
            double x, double y, int button, CallbackInfoReturnable<Boolean> callback) {
        // 由取得捕获的场景完成落点，避免一次松开同时提交两次物品操作。
        if (bs3dVanilla$panel != null && bs3dVanilla$panel.release(x, y, button)) {
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void bs3dVanilla$key(
            int key, int scan, int modifiers, CallbackInfoReturnable<Boolean> callback) {
        // ESC 的实际关闭继续由原版负责，附属只负责先取消计时。
        if (bs3dVanilla$panel != null && bs3dVanilla$panel.key(key, scan, modifiers)) {
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void bs3dVanilla$removed(CallbackInfo callback) {
        // 切换页面和游戏窗口关闭同样释放捕获，不能只处理键盘 ESC。
        if (bs3dVanilla$panel != null) {
            bs3dVanilla$panel.removed();
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        // 只在内景使用滚轮缩放；其他位置保持父类滚动事件。
        if (bs3dVanilla$panel != null && bs3dVanilla$panel.scroll(x, y, amount)) {
            return true;
        }
        return super.mouseScrolled(x, y, amount);
    }

    @Override
    public StationPanel stationPanel() {
        return bs3dVanilla$panel;
    }

    @Override
    public int stationLeft() {
        return x;
    }

    @Override
    public int stationTop() {
        return y;
    }

    @Override
    public int stationWidth() {
        return backgroundWidth;
    }

    @Override
    public Slot stationSlotAt(double x, double y) {
        return getSlotAt(x, y);
    }
}
