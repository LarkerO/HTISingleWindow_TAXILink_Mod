package cn.bg7qvu.htisw.taxilink.fabric;
import net.fabricmc.api.DedicatedServerModInitializer;
import cn.bg7qvu.htisw.taxilink.TaxiLink;
public final class TaxiLinkFabric implements DedicatedServerModInitializer {
    @Override public void onInitializeServer() { TaxiLink.init(); }
}
