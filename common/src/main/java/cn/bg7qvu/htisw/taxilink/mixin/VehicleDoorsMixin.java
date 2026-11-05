package cn.bg7qvu.htisw.taxilink.mixin;

import cn.bg7qvu.htisw.taxilink.DoorControl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "org.mtr.core.data.VehicleExtraData", remap = false)
public abstract class VehicleDoorsMixin {
    @Inject(method = "openDoors", at = @At("HEAD"), cancellable = true, remap = false)
    private void taxilink$open(CallbackInfo ci) { if (DoorControl.blocks(this, true)) ci.cancel(); }
    @Inject(method = "closeDoors", at = @At("HEAD"), cancellable = true, remap = false)
    private void taxilink$close(CallbackInfo ci) { if (DoorControl.blocks(this, false)) ci.cancel(); }
}
