package org.hyzionstudios.mysticessentials.modules.tutorial.scene;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * A parsed Hytale machinima scene, exactly as the client editor writes it to
 * {@code %APPDATA%/Hytale/UserData/Scenes/<Name>.json} and exactly as the
 * client expects it in the {@code scene} field of the {@code UpdateMachinimaScene}
 * packet.
 *
 * <p><b>Format (verified against the client, 2026-07-11):</b> the Hytale client
 * is a .NET app whose machinima model is serialized with System.Text.Json
 * ({@code MachinimaJsonContext}). Every field name in the on-disk scene file
 * ({@code Origin}, {@code Version}, {@code Name}, {@code Actors[]}, {@code Track},
 * {@code Keyframes[]}, {@code PathType}, {@code OriginLook}, {@code StartupCommands})
 * appears as a literal string in {@code HytaleClient.exe} — so the packet's
 * scene {@code byte[]} is simply the UTF-8 JSON of this document.</p>
 *
 * <p>The document keeps the whole JSON tree (via Gson), so unknown/newer fields
 * — additional actor types, keyframe events, easing settings — survive a
 * round-trip untouched. Only {@link #relocatedTo} rewrites values, and only the
 * absolute world coordinates (scene {@code Origin} and each keyframe camera
 * {@code Position}). Directions ({@code Look}, {@code OriginLook}), rotations and
 * bezier tangents ({@code Curve}) are relative and are left as-is.</p>
 */
public final class MachinimaSceneDocument {

    /** Compact serializer for the wire: no pretty-printing keeps the packet small. */
    private static final Gson WIRE = new Gson();

    private final JsonObject root;

    private MachinimaSceneDocument(JsonObject root) {
        this.root = root;
    }

    /**
     * Parses and structurally validates scene JSON.
     *
     * @throws IllegalArgumentException if the bytes are not a machinima scene
     *         (not JSON, not an object, or missing the {@code Actors} array).
     */
    public static MachinimaSceneDocument parse(byte[] json) {
        if (json == null || json.length == 0) {
            throw new IllegalArgumentException("empty scene data");
        }
        JsonElement element;
        try {
            element = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("not valid JSON: " + e.getMessage(), e);
        }
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException("scene root is not a JSON object");
        }
        JsonObject object = element.getAsJsonObject();
        JsonElement actors = object.get("Actors");
        if (actors == null || !actors.isJsonArray()) {
            throw new IllegalArgumentException("not a machinima scene (no \"Actors\" array)");
        }
        return new MachinimaSceneDocument(object);
    }

    /** @return the scene's {@code Name}, or {@code fallback} when absent/blank. */
    public String name(String fallback) {
        JsonElement name = root.get("Name");
        if (name != null && name.isJsonPrimitive()) {
            String value = name.getAsString();
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return fallback;
    }

    /** @return the format {@code Version}, or {@code 0} when absent. */
    public int version() {
        JsonElement version = root.get("Version");
        return version != null && version.isJsonPrimitive() ? version.getAsInt() : 0;
    }

    public int actorCount() {
        JsonElement actors = root.get("Actors");
        return actors != null && actors.isJsonArray() ? actors.getAsJsonArray().size() : 0;
    }

    /** @return total keyframes across every actor track (for info/logging). */
    public int keyframeCount() {
        int total = 0;
        JsonArray actors = root.getAsJsonArray("Actors");
        if (actors == null) {
            return 0;
        }
        for (JsonElement actorElement : actors) {
            if (!actorElement.isJsonObject()) {
                continue;
            }
            JsonElement track = actorElement.getAsJsonObject().get("Track");
            if (track == null || !track.isJsonObject()) {
                continue;
            }
            JsonElement keyframes = track.getAsJsonObject().get("Keyframes");
            if (keyframes != null && keyframes.isJsonArray()) {
                total += keyframes.getAsJsonArray().size();
            }
        }
        return total;
    }

    /** @return the scene {@code Origin} as {@code [x, y, z]}, or {@code null} when absent. */
    public double[] origin() {
        return readPoint(root.get("Origin"));
    }

    /**
     * One camera keyframe flattened for playback: the timeline {@code frame}, the
     * world {@code Position}, the euler {@code Rotation} (x=pitch, y=yaw, z=roll,
     * radians), and the {@code Look} vector (the editor stores camera pitch here
     * for camera actors). Missing sub-objects read as {@code 0}.
     */
    public record Keyframe(double frame, double px, double py, double pz,
            double rotX, double rotY, double rotZ,
            double lookX, double lookY, double lookZ) {
    }

    /**
     * @return the keyframes of the first actor that has a track (the camera),
     *         in file order. Empty when the scene has no usable track.
     */
    public List<Keyframe> cameraKeyframes() {
        List<Keyframe> result = new ArrayList<>();
        JsonArray actors = root.getAsJsonArray("Actors");
        if (actors == null) {
            return result;
        }
        for (JsonElement actorElement : actors) {
            if (!actorElement.isJsonObject()) {
                continue;
            }
            JsonElement track = actorElement.getAsJsonObject().get("Track");
            if (track == null || !track.isJsonObject()) {
                continue;
            }
            JsonElement keyframes = track.getAsJsonObject().get("Keyframes");
            if (keyframes == null || !keyframes.isJsonArray()) {
                continue;
            }
            for (JsonElement keyframeElement : keyframes.getAsJsonArray()) {
                if (!keyframeElement.isJsonObject()) {
                    continue;
                }
                JsonObject keyframe = keyframeElement.getAsJsonObject();
                double frame = keyframe.has("Frame") ? keyframe.get("Frame").getAsDouble() : 0;
                JsonElement settings = keyframe.get("Settings");
                JsonObject s = settings != null && settings.isJsonObject()
                        ? settings.getAsJsonObject() : new JsonObject();
                double[] pos = readPointOrZero(s.get("Position"));
                double[] rot = readPointOrZero(s.get("Rotation"));
                double[] look = readPointOrZero(s.get("Look"));
                result.add(new Keyframe(frame, pos[0], pos[1], pos[2],
                        rot[0], rot[1], rot[2], look[0], look[1], look[2]));
            }
            if (!result.isEmpty()) {
                return result; // First actor with a track wins.
            }
        }
        return result;
    }

    /**
     * Returns a copy of this scene translated so its {@code Origin} sits at the
     * given world point. Every absolute camera {@code Position} (and the
     * {@code Origin} itself) is shifted by {@code target - origin}; a scene with
     * no {@code Origin} is anchored on its first keyframe position instead, and a
     * scene with neither is returned unchanged.
     */
    public MachinimaSceneDocument relocatedTo(double targetX, double targetY, double targetZ) {
        JsonObject copy = root.deepCopy();
        double[] base = readPoint(copy.get("Origin"));
        if (base == null) {
            base = firstKeyframePosition(copy);
        }
        if (base == null) {
            return new MachinimaSceneDocument(copy); // Nothing to anchor to.
        }
        double dx = targetX - base[0];
        double dy = targetY - base[1];
        double dz = targetZ - base[2];
        offsetPoint(copy.get("Origin"), dx, dy, dz);
        JsonArray actors = copy.getAsJsonArray("Actors");
        if (actors != null) {
            for (JsonElement actorElement : actors) {
                if (!actorElement.isJsonObject()) {
                    continue;
                }
                JsonElement track = actorElement.getAsJsonObject().get("Track");
                if (track == null || !track.isJsonObject()) {
                    continue;
                }
                JsonElement keyframes = track.getAsJsonObject().get("Keyframes");
                if (keyframes == null || !keyframes.isJsonArray()) {
                    continue;
                }
                for (JsonElement keyframe : keyframes.getAsJsonArray()) {
                    if (!keyframe.isJsonObject()) {
                        continue;
                    }
                    JsonElement settings = keyframe.getAsJsonObject().get("Settings");
                    if (settings != null && settings.isJsonObject()) {
                        offsetPoint(settings.getAsJsonObject().get("Position"), dx, dy, dz);
                    }
                }
            }
        }
        return new MachinimaSceneDocument(copy);
    }

    /** @return the scene serialized as compact UTF-8 JSON, ready for the packet. */
    public byte[] toBytes() {
        return WIRE.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    /** @return the underlying tree (read-only use). */
    public JsonObject tree() {
        return root;
    }

    // ----- Helpers ---------------------------------------------------------------

    private static double[] firstKeyframePosition(JsonObject scene) {
        JsonArray actors = scene.getAsJsonArray("Actors");
        if (actors == null) {
            return null;
        }
        for (JsonElement actorElement : actors) {
            if (!actorElement.isJsonObject()) {
                continue;
            }
            JsonElement track = actorElement.getAsJsonObject().get("Track");
            if (track == null || !track.isJsonObject()) {
                continue;
            }
            JsonElement keyframes = track.getAsJsonObject().get("Keyframes");
            if (keyframes == null || !keyframes.isJsonArray()) {
                continue;
            }
            for (JsonElement keyframe : keyframes.getAsJsonArray()) {
                if (!keyframe.isJsonObject()) {
                    continue;
                }
                JsonElement settings = keyframe.getAsJsonObject().get("Settings");
                if (settings != null && settings.isJsonObject()) {
                    double[] point = readPoint(settings.getAsJsonObject().get("Position"));
                    if (point != null) {
                        return point;
                    }
                }
            }
        }
        return null;
    }

    /** Reads an {@code {X,Y,Z}} object into {@code [x, y, z]}, defaulting missing parts to 0. */
    private static double[] readPointOrZero(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return new double[] {0, 0, 0};
        }
        JsonObject point = element.getAsJsonObject();
        return new double[] {
                point.has("X") ? point.get("X").getAsDouble() : 0,
                point.has("Y") ? point.get("Y").getAsDouble() : 0,
                point.has("Z") ? point.get("Z").getAsDouble() : 0
        };
    }

    /** Reads an {@code {X,Y,Z}} object into {@code [x, y, z]}, or {@code null}. */
    private static double[] readPoint(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject point = element.getAsJsonObject();
        if (!point.has("X") || !point.has("Y") || !point.has("Z")) {
            return null;
        }
        return new double[] {
                point.get("X").getAsDouble(),
                point.get("Y").getAsDouble(),
                point.get("Z").getAsDouble()
        };
    }

    /** Adds the offset to an {@code {X,Y,Z}} object in place, if present. */
    private static void offsetPoint(JsonElement element, double dx, double dy, double dz) {
        if (element == null || !element.isJsonObject()) {
            return;
        }
        JsonObject point = element.getAsJsonObject();
        if (point.has("X")) {
            point.addProperty("X", point.get("X").getAsDouble() + dx);
        }
        if (point.has("Y")) {
            point.addProperty("Y", point.get("Y").getAsDouble() + dy);
        }
        if (point.has("Z")) {
            point.addProperty("Z", point.get("Z").getAsDouble() + dz);
        }
    }
}
