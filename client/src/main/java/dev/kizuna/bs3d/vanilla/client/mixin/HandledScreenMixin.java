package dev.kizuna.bs3d.vanilla.client.mixin;

import dev.kizuna.bs3d.vanilla.client.PlayerInventoryView;
import dev.kizuna.bs3d.vanilla.client.StationHost;
import dev.kizuna.bs3d.vanilla.client.StationPanel;
import dev.kizuna.bs3d.vanilla.client.VanillaClient;
import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
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
    @Shadow @Final protected int imageWidth;
    @Shadow protected abstract void slotClicked(Slot slot, int slotId, int button, ContainerInput input);

    @Shadow
    private Slot getHoveredSlot(double x, double y) {
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

    @Inject(method = "extractContents", at = @At("HEAD"))
    private void bs3dVanilla$render(
            GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        // 先画场景，再让原版绘制槽位、光标和悬停提示，保留原版前后关系。
        // 26.2 的配方书页面重写了 extractRenderState，只调用 extractContents；挂在这里两类页面都能覆盖。
        PlayerInventoryView.arrange((AbstractContainerScreen<?>) (Object) this);
        if (bs3dVanilla$panel != null) {
            bs3dVanilla$panel.draw(context, mouseX, mouseY);
        }
    }

    @Inject(method = "extractSlot", at = @At("HEAD"), cancellable = true)
    private void bs3dVanilla$pendingSlot(
            GuiGraphicsExtractor context, Slot slot, int mouseX, int mouseY, CallbackInfo callback) {
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
            MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> callback) {
        // 仅消费附属区域或等待中的操作，其他点击继续交给原版。
        if (bs3dVanilla$panel != null && bs3dVanilla$panel.press(event.x(), event.y(), event.button())) {
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void bs3dVanilla$drag(
            MouseButtonEvent event, double dx, double dy, CallbackInfoReturnable<Boolean> callback) {
        // 场景旋转不触发原版格子分配；普通原版拖放仍走原方法。
        if (bs3dVanilla$panel != null && bs3dVanilla$panel.drag(event.x(), event.y(), event.button(), dx, dy)) {
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void bs3dVanilla$release(MouseButtonEvent event, CallbackInfoReturnable<Boolean> callback) {
        // 由取得捕获的场景完成落点，避免一次松开同时提交两次物品操作。
        if (bs3dVanilla$panel != null && bs3dVanilla$panel.release(event.x(), event.y(), event.button())) {
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void bs3dVanilla$key(KeyEvent event, CallbackInfoReturnable<Boolean> callback) {
        // ESC 的实际关闭继续由原版负责，附属只负责清理本地选择。
        if (bs3dVanilla$panel != null && bs3dVanilla$panel.key(event)) {
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

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void bs3dVanilla$scroll(
            double x, double y, double scrollX, double scrollY, CallbackInfoReturnable<Boolean> callback) {
        // 只在内景使用纵向滚轮缩放；其他位置保持原版滚动（含收纳袋切换）。
        // 26.2 的创造页先调用这里再处理自己的滚动条，因此创造页不必另行拦截。
        if (bs3dVanilla$panel != null && bs3dVanilla$panel.scroll(x, y, scrollY)) {
            callback.setReturnValue(true);
        }
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
        return getHoveredSlot(x, y);
    }

    @Override
    public void stationBackdrop(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // 直接调用 Screen 的实现：只有暗幕与延后字幕，不进入各工作站重写的纹理绘制。
        super.extractBackground(context, mouseX, mouseY, delta);
    }

    @Override
    public void stationClick(Slot slot) {
        slotClicked(slot, slot.index, GLFW.GLFW_MOUSE_BUTTON_LEFT, ContainerInput.PICKUP);
    }
}
