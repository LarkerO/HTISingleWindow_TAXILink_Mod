package cn.bg7qvu.htisw.taxilink.mixin;

import cn.bg7qvu.htisw.taxilink.VehicleControl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "org.mtr.core.data.Vehicle", remap = false)
public abstract class VehicleMotionMixin {
    // Cancel only motion, preserving simulate()'s occupancy writes and passenger synchronization.
    @Inject(method = {"simulateMoving", "simulateStopped"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void taxilink$hold(CallbackInfo ci) { if (VehicleControl.block(this)) ci.cancel(); }
}
