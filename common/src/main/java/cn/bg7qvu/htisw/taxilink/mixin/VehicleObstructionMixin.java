package cn.bg7qvu.htisw.taxilink.mixin;

import cn.bg7qvu.htisw.taxilink.ObstructionTracker;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "org.mtr.core.data.Vehicle", remap = false)
public abstract class VehicleObstructionMixin {
    @Inject(method = "railBlockedDistance", at = @At("RETURN"), remap = false)
    private void taxilink$obstruction(int index, double progress, double distance, ObjectArrayList<?> positions, boolean reserve, boolean secondPass, CallbackInfoReturnable<Double> ci) {
        if (reserve && !secondPass) ObstructionTracker.record(this, ci.getReturnValueD());
    }
}
