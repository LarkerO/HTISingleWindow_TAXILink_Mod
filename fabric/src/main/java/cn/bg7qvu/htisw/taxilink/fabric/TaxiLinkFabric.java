package cn.bg7qvu.htisw.taxilink.fabric;
import net.fabricmc.api.ModInitializer;
import cn.bg7qvu.htisw.taxilink.TaxiLink;
public final class TaxiLinkFabric implements ModInitializer {
    @Override public void onInitialize() { TaxiLink.init(); }
}
