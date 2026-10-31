package dev.kizuna.bs3d.vanilla.client.mixin;

import dev.kizuna.bs3d.vanilla.client.PlayerInventoryView;
import dev.kizuna.bs3d.vanilla.client.StationHost;
import dev.kizuna.bs3d.vanilla.client.StationPanel;
import dev.kizuna.bs3d.vanilla.client.VanillaClient;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 只在已适配的原版页面插入两侧场景，保留原版 Screen 和所有配方控件。 */
@Mixin(AbstractContainerScreen.class)
public abstract class HandledScreenMixin extends Screen implements StationHost {
    @Unique private static final int BS3D_SLOT_PIXELS = 16;
    @Unique private static final int BS3D_RGB_MASK = 0x00FFFFFF;
    @Unique private static final int BS3D_PENDING_ALPHA = 0x70000000;
    @Shadow protected int leftPos;
    @Shadow protected int topPos;
    @Shadow protected int imageWidth;
    @Shadow protected abstract void renderBg(GuiGraphics context, float delta, int mouseX, int mouseY);
    @Shadow protected abstract void slotClicked(Slot slot, int slotId, int button, ClickType actionType);

    @Shadow
    private Slot findSlot(double x, double y) {
        throw new AssertionError();
    }

    @Unique private StationPanel bs3dVanilla$panel;

    protected HandledScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void bs3dVanilla$initialize(CallbackInfo callback) {
        // 改分辨率保留已有场景相机；不支持的菜单不创建附属输入层。
        if (bs3dVanilla$panel == null
                && VanillaClient.instance() != null
                && stationSupported()) {
            bs3dVanilla$panel = new StationPanel((AbstractContainerScreen<?>) (Object) this);
        }
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void bs3dVanilla$render(
            GuiGraphics context, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        // 先画场景，再让原版绘制槽位、光标和悬停提示，保留原版前后关系。
        PlayerInventoryView.arrange((AbstractContainerScreen<?>) (Object) this);
        if (bs3dVanilla$panel != null) {
            bs3dVanilla$panel.draw(context, mouseX, mouseY);
        }
    }

    @Inject(method = "renderSlot", at = @At("HEAD"), cancellable = true)
    private void bs3dVanilla$pendingSlot(GuiGraphics context, Slot slot, CallbackInfo callback) {
        // 源槽只在网络回执前显示半透明占位，拒绝时原版物品仍在同一位置。
        if (!PlayerInventoryView.visible((AbstractContainerScreen<?>) (Object) this, slot)) {
            callback.cancel();
            return;
        }
        if (bs3dVanilla$pending(slot)) {
            int color = InventoryUiClient.runtime().color(ThemeTokens.ACCENT) & BS3D_RGB_MASK | BS3D_PENDING_ALPHA;
            context.fill(slot.x, slot.y, slot.x + BS3D_SLOT_PIXELS, slot.y + BS3D_SLOT_PIXELS, color);
            callback.cancel();
        }
    }

    @Unique
    private boolean bs3dVanilla$pending(Slot slot) {
        // 占位按服务器菜单槽号匹配，创造页的页面槽先换成对应的真实槽。
        if (bs3dVanilla$panel == null) {
            return false;
        }
        Slot menuSlot = stationMenuSlot(slot);
        if (menuSlot == null) {
            return false;
        }
        return VanillaClient.instance().pendingMenuSlot(menuSlot.index);
    }

    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;renderBg(Lnet/minecraft/client/gui/GuiGraphics;FII)V"))
    private void bs3dVanilla$background(AbstractContainerScreen<?> screen, GuiGraphics context,
                                       float delta, int mouseX, int mouseY) {
        // 裁接只作用于背景，原版槽位物品和悬停提示仍绘制一次。
        PlayerInventoryView.drawBackground(screen, context, () -> renderBg(context, delta, mouseX, mouseY));
    }

    @Inject(method = "isHovering(Lnet/minecraft/world/inventory/Slot;DD)Z", at = @At("HEAD"), cancellable = true)
    private void bs3dVanilla$hiddenHit(Slot slot, double mouseX, double mouseY,
                                      CallbackInfoReturnable<Boolean> callback) {
        // 隐藏槽同时退出命中与高亮，不能留下不可见的客户端交互区域。
        if (!PlayerInventoryView.visible((AbstractContainerScreen<?>) (Object) this, slot)) {
            callback.setReturnValue(false);
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
        // ESC 的实际关闭继续由原版负责，附属只负责清理本地选择。
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
        return leftPos;
    }

    @Override
    public int stationTop() {
        return topPos;
    }

    @Override
    public int stationWidth() {
        return imageWidth;
    }

    @Override
    public Slot stationSlotAt(double x, double y) {
        return findSlot(x, y);
    }

    @Override
    public void stationClick(Slot slot) {
        slotClicked(slot, slot.index, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP);
    }
}
