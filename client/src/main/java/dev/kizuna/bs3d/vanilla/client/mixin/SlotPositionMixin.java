package dev.kizuna.bs3d.vanilla.client.mixin;

import dev.kizuna.bs3d.vanilla.client.SlotPosition;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Slot.class)
public interface SlotPositionMixin extends SlotPosition {
    @Override
    @Mutable
    @Accessor("x")
    void bs3dVanilla$setX(int x);

    @Override
    @Mutable
    @Accessor("y")
    void bs3dVanilla$setY(int y);
}
