package org.hyzionstudios.mysticessentials.api.model;

/** A named player home: a label plus a stored location. Owned by the Homes submodule. */
public final class Home {

    private String name;
    private MysticLocation location;

    public Home() {
    }

    public Home(String name, MysticLocation location) {
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
}
