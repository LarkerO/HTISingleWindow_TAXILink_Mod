package cn.bg7qvu.htisw.taxilink.mixin;

import cn.bg7qvu.htisw.taxilink.ClientBoarding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "org.mtr.render.RenderVehicles", remap = false)
public abstract class PlatformlessDoorsMixin {
    @Inject(method = "lambda$render$4", at = @At("HEAD"), cancellable = true, remap = false)
    private static void taxilink$boarding(@Coerce Object transform, @Coerce Object vehicle, @Coerce Object doorway, CallbackInfoReturnable<Boolean> ci) {
        if (ClientBoarding.allows(vehicle)) ci.setReturnValue(true);
    }
}
