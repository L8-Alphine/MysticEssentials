package org.hyzionstudios.mysticessentials.api.model;

/**
 * A saved warp location. Used for both server warps and player warps; the
 * {@code owner} field is {@code null} for server-owned warps.
 */
public final class Warp {

    /** Visibility of a warp to players who have not been granted it explicitly. */
    public enum Visibility {
        PUBLIC,
        HIDDEN,
        PERMISSION
    }

    private String name;
    private MysticLocation location;
    private String owner;
    private String ownerName;
    private String category;
    private String description;
    private String permission;
    private Visibility visibility = Visibility.PUBLIC;
    private double cost;

    public Warp() {
    }

    public Warp(String name, MysticLocation location) {
        this.name = name;
        this.location = location;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public MysticLocation getLocation() {
        return location;
    }

    public void setLocation(MysticLocation location) {
        this.location = location;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public boolean isPlayerWarp() {
        return owner != null;
    }

    /** Last-known username of the owner, for display; may lag behind renames. */
    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }

    public Visibility getVisibility() {
        return visibility;
    }

    public void setVisibility(Visibility visibility) {
        this.visibility = visibility;
    }

    public double getCost() {
        return cost;
    }

    public void setCost(double cost) {
        this.cost = cost;
    }
}
