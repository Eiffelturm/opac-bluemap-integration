package io.github.gaming32.opacbluemapintegration;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class OpacBluemapConfig {
    public static class ServerConfig {

        public final ForgeConfigSpec.IntValue updateInterval;
        public final ForgeConfigSpec.DoubleValue markerMinY;
        public final ForgeConfigSpec.DoubleValue markerMaxY;
        public final ForgeConfigSpec.BooleanValue depthTest;

        ServerConfig(ForgeConfigSpec.Builder builder) {
            builder.comment("OPAC Bluemap Integration Cofig");

            this.updateInterval = builder
                    .comment("Claims Update Interval (in ticks)")
                    .defineInRange("updateInterval", 12000, 0, Integer.MAX_VALUE);
            this.markerMinY = builder
                    .comment("Minimum Y Marker")
                    .defineInRange("markerMinY", 75f, -60f, 255f);
            this.markerMaxY = builder
                    .comment("Maximum Y Marker")
                    .defineInRange("markerMaxY", 75f, -60f, 255f);
            this.depthTest = builder
                    .comment("Depth Test")
                    .define("depthTest", false);
        }
    }

    public static final ForgeConfigSpec serverSpec;
    public static final ServerConfig SERVER;

    static {
        Pair<ServerConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder()
                .configure(ServerConfig::new);
        serverSpec = pair.getRight();
        SERVER = pair.getLeft();
    }
}
