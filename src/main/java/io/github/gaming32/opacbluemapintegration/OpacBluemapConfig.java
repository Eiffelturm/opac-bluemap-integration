package io.github.gaming32.opacbluemapintegration;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class OpacBluemapConfig {
    public static final ModConfigSpec serverSpec;
    public static final ServerConfig SERVER;

    static {
        Pair<ServerConfig, ModConfigSpec> pair = new ModConfigSpec.Builder()
                .configure(ServerConfig::new);
        serverSpec = pair.getRight();
        SERVER = pair.getLeft();
    }

    public static class ServerConfig {

        public final ModConfigSpec.IntValue updateInterval;
        public final ModConfigSpec.DoubleValue markerMinY;
        public final ModConfigSpec.DoubleValue markerMaxY;
        public final ModConfigSpec.BooleanValue depthTest;

        ServerConfig(ModConfigSpec.Builder builder) {
            builder.comment("OPAC Bluemap Integration Config");

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
}
