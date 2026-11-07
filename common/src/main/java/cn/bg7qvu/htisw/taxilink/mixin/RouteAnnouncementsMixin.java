package cn.bg7qvu.htisw.taxilink.mixin;

import cn.bg7qvu.htisw.taxilink.VehicleControl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "org.mtr.data.PersistentStateData", remap = false)
public abstract class RouteAnnouncementsMixin {
    @Inject(method = "getRouteIdHasDisabledAnnouncements", at = @At("HEAD"), cancellable = true, remap = false)
    private void taxilink$silent(long routeId, CallbackInfoReturnable<Boolean> ci) {
        if (VehicleControl.silent(routeId)) ci.setReturnValue(true);
    }
}
