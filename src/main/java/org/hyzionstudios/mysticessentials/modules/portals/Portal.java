package org.hyzionstudios.mysticessentials.modules.portals;

import java.util.Locale;

/**
 * One configured portal, anchored to a block in a world. The anchor is the
 * block the portal was first triggered or configured at; collision triggers on
 * neighbouring blocks (multi-block portal frames) resolve to the nearest
 * anchor. What happens on entry depends on {@link Type}:
 *
 * <ul>
 *   <li>{@code WORLD} — teleport to {@code targetWorld}, either at its spawn
 *       or at a fixed position/facing when {@code useLocation} is set.</li>
 *   <li>{@code SERVER} — refer the player's client to another server
 *       ({@code host:port}).</li>
 *   <li>{@code COMMAND} — run a command sequence ({@code ;}-separated,
 *       {@code wait <ticks>} pauses) as console or as the player.</li>
 * </ul>
 */
public final class Portal {

    public enum Type {
        WORLD,
        SERVER,
        COMMAND;

        public static Type parse(String raw) {
            if (raw == null) {
                return WORLD;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return WORLD;
            }
        }
    }

    /** Stable id, generated once on creation ({@code portal_<hex>}). */
    private String id = "";
    /** Optional display name shown in list UIs and the map marker default. */
    private String name = "";

    /** World (uuid string) the portal block lives in. */
    private String world = "";
    private int x;
    private int y;
    private int z;

    private Type type = Type.WORLD;

    // WORLD
    private String targetWorld = "";
    private boolean useLocation;
    private double posX;
    private double posY;
    private double posZ;
    /** Facing letter N/E/S/W applied after a fixed-location teleport, or empty. */
    private String facing = "";

    // SERVER
    private String host = "";
    private int port;

    // COMMAND
    private String command = "";
    /** "server" (console) or "player". */
    private String commandSender = "server";

    /** Permission node required to use the portal; empty = everyone. */
    private String permission = "";

    private boolean markerEnabled;
    private String markerText = "";
    private String markerIcon = "";

    /** No-arg constructor for JSON deserialization. */
    public Portal() {
    }

    public Portal(String id, String world, int x, int y, int z) {
        this.id = id;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public String getId() {
        return id == null ? "" : id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name == null ? "" : name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name.trim();
    }

    public String getWorld() {
        return world == null ? "" : world;
    }

    public void setWorld(String world) {
        this.world = world;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public void setX(int x) {
        this.x = x;
    }

    public void setY(int y) {
        this.y = y;
    }

    public void setZ(int z) {
        this.z = z;
    }

    public Type getType() {
        return type == null ? Type.WORLD : type;
    }

    public void setType(Type type) {
        this.type = type == null ? Type.WORLD : type;
    }

    public String getTargetWorld() {
        return targetWorld == null ? "" : targetWorld;
    }

    public void setTargetWorld(String targetWorld) {
        this.targetWorld = targetWorld == null ? "" : targetWorld.trim();
    }

    public boolean isUseLocation() {
        return useLocation;
    }

    public void setUseLocation(boolean useLocation) {
        this.useLocation = useLocation;
    }

    public double getPosX() {
        return posX;
    }

    public double getPosY() {
        return posY;
    }

    public double getPosZ() {
        return posZ;
    }

    public void setPos(double posX, double posY, double posZ) {
        this.posX = posX;
        this.posY = posY;
        this.posZ = posZ;
    }

    public String getFacing() {
        return normalizeFacing(facing);
    }

    public void setFacing(String facing) {
        this.facing = normalizeFacing(facing);
    }

    public String getHost() {
        return host == null ? "" : host;
    }

    public void setHost(String host) {
        this.host = host == null ? "" : host.trim();
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getCommand() {
        return command == null ? "" : command;
    }

    public void setCommand(String command) {
        this.command = command == null ? "" : command.trim();
    }

    public String getCommandSender() {
        return "player".equalsIgnoreCase(commandSender) ? "player" : "server";
    }

    public void setCommandSender(String commandSender) {
        this.commandSender = "player".equalsIgnoreCase(commandSender == null ? "" : commandSender.trim())
                ? "player" : "server";
    }

    public String getPermission() {
        return permission == null ? "" : permission;
    }

    public void setPermission(String permission) {
        this.permission = permission == null ? "" : permission.trim();
    }

    public boolean isMarkerEnabled() {
        return markerEnabled;
    }

    public void setMarkerEnabled(boolean markerEnabled) {
        this.markerEnabled = markerEnabled;
    }

    public String getMarkerText() {
        return markerText == null ? "" : markerText;
    }

    public void setMarkerText(String markerText) {
        this.markerText = markerText == null ? "" : markerText.trim();
    }

    public String getMarkerIcon() {
        return markerIcon == null ? "" : markerIcon;
    }

    /** Stores just the icon file name — path prefixes are stripped. */
    public void setMarkerIcon(String markerIcon) {
        String icon = markerIcon == null ? "" : markerIcon.trim().replace('\\', '/');
        int slash = icon.lastIndexOf('/');
        this.markerIcon = slash >= 0 && slash + 1 < icon.length() ? icon.substring(slash + 1) : icon;
    }

    /** Storage key of the anchor block. */
    public String key() {
        return key(getWorld(), x, y, z);
    }

    public static String key(String world, int x, int y, int z) {
        return (world == null ? "" : world) + ":" + x + ":" + y + ":" + z;
    }

    public static String normalizeFacing(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "N", "E", "S", "W" -> value;
            default -> "";
        };
    }
}
