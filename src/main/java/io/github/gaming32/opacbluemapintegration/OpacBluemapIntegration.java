package io.github.gaming32.opacbluemapintegration;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.logging.LogUtils;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.ExtrudeMarker;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import xaero.pac.common.claims.player.api.IPlayerClaimPosListAPI;
import xaero.pac.common.server.api.OpenPACServerAPI;

import java.util.*;
import java.util.stream.Collectors;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

@Mod(OpacBluemapIntegration.MOD_ID)
public final class OpacBluemapIntegration {
    public static final String MOD_ID = "opac_bluemap_integration";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final String MARKER_SET_KEY = "opac-bluemap-integration";
    private static final int TICKS_PER_SECOND = 20;
    private static MinecraftServer minecraftServer;
    private static int updateIn;

    public OpacBluemapIntegration(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, OpacBluemapConfig.serverSpec);
        BlueMapAPI.onEnable(OpacBluemapIntegration::updateClaims);
        NeoForge.EVENT_BUS.register(OpacBluemapModEvents.class);
    }

    public static void updateClaims(BlueMapAPI blueMap) {
        if (minecraftServer == null) {
            LOGGER.warn("updateClaims called with minecraftServer == null!");
            return;
        }
        LOGGER.info("Refreshing OpenPaC BlueMap markers");
        final float minY = OpacBluemapConfig.SERVER.markerMinY.get().floatValue();
        final float maxY = OpacBluemapConfig.SERVER.markerMaxY.get().floatValue();
        //noinspection SuspiciousNameCombination
        final boolean flatPlane = Mth.equal(minY, maxY);
        final boolean depthTest = OpacBluemapConfig.SERVER.depthTest.get();

        OpenPACServerAPI.get(minecraftServer)
                .getServerClaimsManager()
                .getPlayerInfoStream()
                .forEach(playerClaimInfo -> {
                    final ClaimNames claimNames = getClaimNames(playerClaimInfo.getClaimsName(), playerClaimInfo.getPlayerUsername());
                    playerClaimInfo.getStream().forEach(entry -> {
                        final BlueMapWorld world = blueMap.getWorld(ResourceKey.create(Registries.DIMENSION, entry.getKey())).orElse(null);
                        if (world == null) return;

                        final List<ShapeHolder> shapes = createShapes(
                                entry.getValue()
                                        .getStream()
                                        .flatMap(IPlayerClaimPosListAPI::getStream)
                                        .collect(Collectors.toSet())
                        );
                        updateWorldMarkers(
                                world,
                                claimNames,
                                shapes,
                                playerClaimInfo.getClaimsColor(),
                                minY,
                                maxY,
                                flatPlane,
                                depthTest
                        );
                    });
                });
        LOGGER.info("Refreshed OpenPaC BlueMap markers");
        updateIn = OpacBluemapConfig.SERVER.updateInterval.get();
    }

    private static ClaimNames getClaimNames(String claimName, String username) {
        if (!StringUtils.isBlank(claimName)) {
            return new ClaimNames(claimName, claimName);
        }

        String displayName = username;
        if (displayName.length() > 2 && displayName.charAt(0) == '"' && displayName.charAt(displayName.length() - 1) == '"') {
            displayName = displayName.substring(1, displayName.length() - 1) + " claim";
        } else {
            displayName += "'s claim";
        }
        return new ClaimNames(username, displayName);
    }

    private static void updateWorldMarkers(
            BlueMapWorld world,
            ClaimNames claimNames,
            List<ShapeHolder> shapes,
            int color,
            float minY,
            float maxY,
            boolean flatPlane,
            boolean depthTest
    ) {
        final Color fillColor = new Color(color, 150);
        final Color lineColor = new Color(color, 255);
        world.getMaps().forEach(map -> {
            final Map<String, Marker> markers = map
                    .getMarkerSets()
                    .computeIfAbsent(MARKER_SET_KEY, k ->
                            MarkerSet.builder()
                                    .toggleable(true)
                                    .label("Open Parties and Claims")
                                    .build()
                    )
                    .getMarkers();

            markers.keySet().removeIf(k -> k.startsWith(claimNames.idName() + "---"));
            for (int i = 0; i < shapes.size(); i++) {
                final ShapeHolder shape = shapes.get(i);
                markers.put(
                        claimNames.idName() + "---" + i,
                        buildMarker(shape, claimNames.displayName(), fillColor, lineColor, minY, maxY, flatPlane, depthTest)
                );
            }
        });
    }

    private static Marker buildMarker(
            ShapeHolder shape,
            String label,
            Color fillColor,
            Color lineColor,
            float minY,
            float maxY,
            boolean flatPlane,
            boolean depthTest
    ) {
        // Yes these builders are similar. BlueMap does not expose a shared builder type for these marker variants.
        return flatPlane
                ? ShapeMarker.builder()
                  .label(label)
                  .fillColor(fillColor)
                  .lineColor(lineColor)
                  .shape(shape.baseShape(), minY)
                  .holes(shape.holes())
                  .depthTestEnabled(depthTest)
                  .build()
                : ExtrudeMarker.builder()
                  .label(label)
                  .fillColor(fillColor)
                  .lineColor(lineColor)
                  .shape(shape.baseShape(), minY, maxY)
                  .holes(shape.holes())
                  .depthTestEnabled(depthTest)
                  .build();
    }

    public static List<ShapeHolder> createShapes(Set<ChunkPos> chunks) {
        return createChunkGroups(chunks)
                .stream()
                .map(ShapeHolder::create)
                .toList();
    }

    public static List<Set<ChunkPos>> createChunkGroups(Set<ChunkPos> chunks) {
        final List<Set<ChunkPos>> result = new ArrayList<>();
        final Set<ChunkPos> visited = new HashSet<>();
        for (final ChunkPos chunk : chunks) {
            if (visited.contains(chunk)) continue;
            final Set<ChunkPos> neighbors = findNeighbors(chunk, chunks);
            result.add(neighbors);
            visited.addAll(neighbors);
        }
        return result;
    }

    public static Set<ChunkPos> findNeighbors(ChunkPos chunk, Set<ChunkPos> chunks) {
        if (!chunks.contains(chunk)) {
            throw new IllegalArgumentException("chunks must contain chunk to find neighbors!");
        }
        final Set<ChunkPos> visited = new HashSet<>();
        final Queue<ChunkPos> toVisit = new ArrayDeque<>();
        visited.add(chunk);
        toVisit.add(chunk);
        while (!toVisit.isEmpty()) {
            final ChunkPos visiting = toVisit.remove();
            for (final ChunkPosDirection dir : ChunkPosDirection.values()) {
                final ChunkPos offsetPos = dir.add(visiting);
                if (!chunks.contains(offsetPos) || !visited.add(offsetPos)) continue;
                toVisit.add(offsetPos);
            }
        }
        return visited;
    }

    public static class OpacBluemapModEvents {
        @SubscribeEvent
        public static void serverStarted(final ServerStartedEvent ev) {
            minecraftServer = ev.getServer();
        }

        @SubscribeEvent
        public static void serverStopping(final ServerStoppingEvent ev) {
            minecraftServer = null;
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void serverTick(final ServerTickEvent.Post ev) {
            if (updateIn <= 0) return;
            if (--updateIn <= 0) {
                BlueMapAPI.getInstance().ifPresent(OpacBluemapIntegration::updateClaims);
            }
        }

        @SubscribeEvent
        public static void commandEvent(final RegisterCommandsEvent ev) {
            ev.getDispatcher().register(Commands.literal("openpac-bluemap")
                    .requires(s -> s.hasPermission(2))
                    .then(literal("refresh-now")
                            .requires(s -> BlueMapAPI.getInstance().isPresent())
                            .executes(ctx -> {
                                final BlueMapAPI api = BlueMapAPI.getInstance().orElse(null);
                                if (api == null) {
                                    ctx.getSource().sendFailure(Component.literal("BlueMap not loaded").withStyle(ChatFormatting.RED));
                                    return 0;
                                }
                                updateClaims(api);
                                sendSuccess(ctx.getSource(), "BlueMap OpenPaC claims refreshed");
                                return Command.SINGLE_SUCCESS;
                            })
                    )
                    .then(literal("refresh-in")
                            .executes(ctx -> sendTimedSuccess(ctx.getSource(), "OpenPaC BlueMap will refresh in ", updateIn))
                            .then(argument("time", TimeArgument.time())
                                    .executes(ctx -> {
                                        updateIn = IntegerArgumentType.getInteger(ctx, "time");
                                        return sendTimedSuccess(ctx.getSource(), "OpenPaC BlueMap will refresh in ", updateIn);
                                    })
                            )
                    )
                    .then(literal("refresh-every")
                            .executes(ctx -> sendTimedSuccess(
                                    ctx.getSource(),
                                    "OpenPaC BlueMap auto refreshes every ",
                                    OpacBluemapConfig.SERVER.updateInterval.get()
                            ))
                            .then(argument("interval", TimeArgument.time())
                                    .executes(ctx -> {
                                        final int interval = IntegerArgumentType.getInteger(ctx, "interval");
                                        OpacBluemapConfig.SERVER.updateInterval.set(interval);
                                        if (interval < updateIn) {
                                            updateIn = interval;
                                        }
                                        OpacBluemapConfig.SERVER.updateInterval.save();
                                        return sendTimedSuccess(ctx.getSource(), "OpenPaC BlueMap will auto refresh every ", interval);
                                    })
                            )
                    )
                    .then(literal("reload")
                            .executes(ctx -> {
                                if (OpacBluemapConfig.SERVER.updateInterval.get() < updateIn) {
                                    updateIn = OpacBluemapConfig.SERVER.updateInterval.get();
                                }
                                sendSuccess(ctx.getSource(), "Reloaded OpenPaC BlueMap config");
                                return Command.SINGLE_SUCCESS;
                            })
                    )
            );
        }

        private static int sendTimedSuccess(CommandSourceStack source, String prefix, int ticks) {
            source.sendSuccess(
                    () -> Component.literal(prefix).append(Component.literal((ticks / TICKS_PER_SECOND) + "s").withStyle(ChatFormatting.GREEN)),
                    true
            );
            return Command.SINGLE_SUCCESS;
        }

        private static void sendSuccess(CommandSourceStack source, String message) {
            source.sendSuccess(() -> Component.literal(message).withStyle(ChatFormatting.GREEN), true);
        }
    }

    private record ClaimNames(String idName, String displayName) {
    }
}
