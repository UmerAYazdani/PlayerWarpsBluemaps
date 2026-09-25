package org.bluemaps.playerwarpsbluemap;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;

import dev.revivalo.playerwarps.PlayerWarpsPlugin;
import dev.revivalo.playerwarps.warp.Warp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public final class PlayerWarpsBlueMap extends JavaPlugin {

    private static final String DEFAULT_MARKER_SET_ID = "player-warps";

    private BlueMapAPI blueMapAPI;
    private BukkitTask syncTask;

    private final Map<UUID, MarkerSet> worldMarkerSets = new HashMap<>();

    private final Consumer<BlueMapAPI> blueMapEnableListener = api -> {
        this.blueMapAPI = api;

        Bukkit.getScheduler().runTask(this, () -> {
            getLogger().info(
                    "Connected to BlueMap " + api.getBlueMapVersion()
                            + " (API " + api.getAPIVersion() + ")"
            );

            syncMarkers();
        });
    };

    private final Consumer<BlueMapAPI> blueMapDisableListener = api -> {
        this.blueMapAPI = null;
        this.worldMarkerSets.clear();
    };

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (Bukkit.getPluginManager().getPlugin("PlayerWarps") == null) {
            getLogger().severe("PlayerWarps was not found.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (Bukkit.getPluginManager().getPlugin("BlueMap") == null) {
            getLogger().severe("BlueMap was not found.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        BlueMapAPI.onEnable(blueMapEnableListener);
        BlueMapAPI.onDisable(blueMapDisableListener);

        startSyncTask();

        getLogger().info("PlayerWarpsBlueMap enabled.");
    }

    @Override
    public void onDisable() {
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }

        removeOurMarkerSets();

        BlueMapAPI.unregisterListener(blueMapEnableListener);
        BlueMapAPI.unregisterListener(blueMapDisableListener);

        worldMarkerSets.clear();
        blueMapAPI = null;

        getLogger().info("PlayerWarpsBlueMap disabled.");
    }

    private void startSyncTask() {
        if (syncTask != null) {
            syncTask.cancel();
        }

        long seconds = Math.max(
                1,
                getConfig().getLong("sync-interval-seconds", 10)
        );

        long ticks = seconds * 20L;

        syncTask = Bukkit.getScheduler().runTaskTimer(
                this,
                this::syncMarkers,
                20L,
                ticks
        );
    }

    public void syncMarkers() {
        BlueMapAPI api = blueMapAPI;

        if (api == null) {
            return;
        }

        PlayerWarpsPlugin playerWarps = PlayerWarpsPlugin.get();

        if (playerWarps == null) {
            debug("PlayerWarps instance is unavailable.");
            return;
        }

        if (PlayerWarpsPlugin.getWarpHandler() == null) {
            debug("PlayerWarps WarpManager is unavailable.");
            return;
        }

        removeOurMarkerSets();
        worldMarkerSets.clear();

        int totalWarps = 0;
        int markersCreated = 0;
        int skipped = 0;

        /*
         * Read every PlayerWarp and group it according to its
         * actual Bukkit world.
         */
        for (Warp warp : PlayerWarpsPlugin.getWarpHandler().getWarps()) {

            totalWarps++;

            if (warp == null) {
                skipped++;
                continue;
            }

            if (getConfig().getBoolean("only-accessible-warps", true)
                    && !warp.isAccessible()) {

                debug("Skipping inaccessible warp: " + warp.getName());
                skipped++;
                continue;
            }

            Location location = warp.getLocation();

            if (location == null) {
                debug("Warp " + warp.getName() + " has no location.");
                skipped++;
                continue;
            }

            World bukkitWorld = location.getWorld();

            if (bukkitWorld == null) {
                debug("Warp " + warp.getName() + " has no Bukkit world.");
                skipped++;
                continue;
            }

            /*
             * Ask BlueMap which BlueMapWorld belongs to this
             * Bukkit world.
             *
             * We do NOT manually compare world names.
             */
            BlueMapWorld blueMapWorld = api
                    .getWorld(bukkitWorld)
                    .orElse(null);

            if (blueMapWorld == null) {
                debug(
                        "BlueMap does not have a map for Bukkit world: "
                                + bukkitWorld.getName()
                );

                skipped++;
                continue;
            }

            MarkerSet markerSet = worldMarkerSets.computeIfAbsent(
                    bukkitWorld.getUID(),
                    ignored -> createMarkerSet()
            );

            String markerId =
                    "playerwarp-" + warp.getWarpID();

            POIMarker marker =
                    createMarker(warp, location);

            markerSet.getMarkers().put(
                    markerId,
                    marker
            );

            markersCreated++;

            debug(
                    "Loaded warp "
                            + warp.getName()
                            + " at "
                            + location.getBlockX()
                            + ", "
                            + location.getBlockY()
                            + ", "
                            + location.getBlockZ()
                            + " in Bukkit world "
                            + bukkitWorld.getName()
            );
        }

        /*
         * Attach each world's marker set ONLY to BlueMap maps
         * belonging to that exact world.
         *
         * The marker-set ID is generated from the BlueMap map ID,
         * NOT the Bukkit world name.
         */
        for (World bukkitWorld : Bukkit.getWorlds()) {

            MarkerSet markerSet =
                    worldMarkerSets.get(bukkitWorld.getUID());

            if (markerSet == null) {
                continue;
            }

            BlueMapWorld blueMapWorld = api
                    .getWorld(bukkitWorld)
                    .orElse(null);

            if (blueMapWorld == null) {
                continue;
            }

            for (BlueMapMap map : blueMapWorld.getMaps()) {

                /*
                 * Example:
                 *
                 * BlueMap map ID: survival
                 * Marker Set ID: player-warps-survival
                 *
                 * BlueMap map ID: survival-nether
                 * Marker Set ID: player-warps-survival-nether
                 */
                String markerSetId =
                        getMarkerSetId()
                                + "-"
                                + sanitizeId(map.getId());

                map.getMarkerSets().put(
                        markerSetId,
                        markerSet
                );

                debug(
                        "Attached PlayerWarps markers to BlueMap map: "
                                + map.getName()
                                + " | BlueMap ID: "
                                + map.getId()
                                + " | MarkerSet ID: "
                                + markerSetId
                );
            }
        }

        debug(
                "Sync complete. Total warps="
                        + totalWarps
                        + ", markers="
                        + markersCreated
                        + ", skipped="
                        + skipped
        );
    }

    private MarkerSet createMarkerSet() {

        String label = getConfig().getString(
                "marker-set.label",
                "Player Warps"
        );

        boolean toggleable = getConfig().getBoolean(
                "marker-set.toggleable",
                true
        );

        boolean defaultHidden = getConfig().getBoolean(
                "marker-set.default-hidden",
                false
        );

        return MarkerSet.builder()
                .label(label)
                .toggleable(toggleable)
                .defaultHidden(defaultHidden)
                .build();
    }

    private POIMarker createMarker(
            Warp warp,
            Location location
    ) {

        String labelFormat = getConfig().getString(
                "markers.label",
                "%warp%"
        );

        String detailFormat = getConfig().getString(
                "markers.detail",
                """
                <div style="text-align:center;">
                    <b>%warp%</b><br>
                    Owner: %owner%<br>
                    World: %world%<br>
                    X: %x% Y: %y% Z: %z%<br>
                    %description%
                </div>
                """
        );

        String label = replacePlaceholders(
                labelFormat,
                warp,
                location
        );

        String detail = replacePlaceholders(
                detailFormat,
                warp,
                location
        );

        return POIMarker.builder()
                .label(label)
                .detail(detail)
                .position(
                        location.getX(),
                        location.getY(),
                        location.getZ()
                )
                .build();
    }

    private String replacePlaceholders(
            String text,
            Warp warp,
            Location location
    ) {

        if (text == null) {
            return "";
        }

        World world = location.getWorld();

        String worldName =
                world == null
                        ? "Unknown"
                        : getBlueMapName(world);

        String description =
                warp.getDescription();

        if (description == null || description.isBlank()) {
            description = "";
        }

        return text
                .replace(
                        "%warp%",
                        escapeHtml(warp.getName())
                )
                .replace(
                        "%owner%",
                        escapeHtml(warp.getOwnerName())
                )
                .replace(
                        "%world%",
                        escapeHtml(worldName)
                )
                .replace(
                        "%x%",
                        String.valueOf(location.getBlockX())
                )
                .replace(
                        "%y%",
                        String.valueOf(location.getBlockY())
                )
                .replace(
                        "%z%",
                        String.valueOf(location.getBlockZ())
                )
                .replace(
                        "%description%",
                        escapeHtml(description)
                )
                .replace(
                        "%visits%",
                        String.valueOf(warp.getVisits())
                );
    }

    /*
     * Gets the display name directly from BlueMap.
     *
     * If BlueMap has multiple maps for one world,
     * the first map's configured display name is used.
     */
    private String getBlueMapName(World bukkitWorld) {

        BlueMapAPI api = blueMapAPI;

        if (api == null) {
            return bukkitWorld.getName();
        }

        BlueMapWorld blueMapWorld = api
                .getWorld(bukkitWorld)
                .orElse(null);

        if (blueMapWorld == null) {
            return bukkitWorld.getName();
        }

        for (BlueMapMap map : blueMapWorld.getMaps()) {

            String name = map.getName();

            if (name != null && !name.isBlank()) {
                return name;
            }

            String id = map.getId();

            if (id != null && !id.isBlank()) {
                return id;
            }
        }

        return bukkitWorld.getName();
    }

    /*
     * Removes every marker set created by this addon.
     *
     * Handles:
     *
     * player-warps
     * player-warps-survival
     * player-warps-survival-nether
     * etc.
     */
    private void removeOurMarkerSets() {

        BlueMapAPI api = blueMapAPI;

        if (api == null) {
            return;
        }

        String baseId =
                getMarkerSetId();

        String prefix =
                baseId + "-";

        for (BlueMapMap map : api.getMaps()) {

            map.getMarkerSets()
                    .keySet()
                    .removeIf(
                            id ->
                                    id.equals(baseId)
                                            || id.startsWith(prefix)
                    );
        }
    }

    private String getMarkerSetId() {

        String value = getConfig().getString(
                "marker-set.id",
                DEFAULT_MARKER_SET_ID
        );

        if (value == null || value.isBlank()) {
            return DEFAULT_MARKER_SET_ID;
        }

        return value;
    }

    private String sanitizeId(String input) {

        if (input == null || input.isBlank()) {
            return "unknown";
        }

        return input
                .toLowerCase()
                .replaceAll(
                        "[^a-z0-9_-]",
                        "-"
                );
    }

    private void debug(String message) {

        if (getConfig().getBoolean(
                "debug",
                false
        )) {

            getLogger().info(
                    "[DEBUG] " + message
            );
        }
    }

    private String escapeHtml(String input) {

        if (input == null) {
            return "";
        }

        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {

        if (!sender.hasPermission(
                "playerwarpsbluemap.admin"
        )) {

            sender.sendMessage(
                    "You do not have permission."
            );

            return true;
        }

        if (args.length == 0) {

            sender.sendMessage(
                    "/pwbluemap sync"
            );

            sender.sendMessage(
                    "/pwbluemap reload"
            );

            return true;
        }

        if (args[0].equalsIgnoreCase("sync")) {

            syncMarkers();

            sender.sendMessage(
                    "PlayerWarps markers synchronized with BlueMap."
            );

            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {

            reloadConfig();
            startSyncTask();
            syncMarkers();

            sender.sendMessage(
                    "PlayerWarpsBlueMap configuration reloaded."
            );

            return true;
        }

        sender.sendMessage(
                "/pwbluemap <sync|reload>"
        );

        return true;
    }
}