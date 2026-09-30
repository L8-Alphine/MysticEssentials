package org.hyzionstudios.mysticessentials.core.license;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.hyzionstudios.mysticessentials.generated.LicensingEndpoint;

import com.mystic.licensing.LicenseGate;
import com.mystic.licensing.PublicKeyRing;

/**
 * Which licensed features this server has, from MysticLicenses v2.
 *
 * <p>The operator puts the key from the license portal in {@code license.key}
 * next to the mod's {@code config.json}. The server activates online, then runs
 * on a signed authorization cached in {@code .mystic/} at the server root and
 * renewed in the background, with an offline grace period while the licensing
 * service is unreachable.
 *
 * <h2>What licensing may and may not do here</h2>
 * A licensing problem switches off the modules that declare a licensed feature
 * and changes nothing else. Mystic Essentials loads, every unlicensed module
 * enables normally, and the server starts. Nothing here throws: a build without
 * a licensing service, an unreachable service, a wrong key or a denial each
 * answer "not licensed".
 */
public final class EssentialsLicense implements AutoCloseable {

    /** Product slug in the MysticLicenses catalog. */
    public static final String PRODUCT = "mysticessentials";

    public static final String EDITOR_KIT = "editor.kit";
    public static final String EDITOR_SHOP = "editor.shop";
    public static final String MAIL_SEND_MONEY = "mail.send.money";

    /**
     * Gates the CustomGUIs and CustomDialogs module: the entitlement
     * {@code product.mysticessentials.module.customcontent}.
     */
    public static final String MODULE_CUSTOM_CONTENT = "module.customcontent";

    /** Every feature id, for {@code /mystic license}. Constants so a typo is a compile error. */
    public static final List<String> ALL =
            List.of(EDITOR_KIT, EDITOR_SHOP, MAIL_SEND_MONEY, MODULE_CUSTOM_CONTENT);

    /** The retired licensing prototype's offline file, which this version no longer reads. */
    private static final String PROTOTYPE_LICENSE_FILE = "license.mclicense";

    /** Grants nothing; what {@code MysticCore.license()} answers before licensing starts. */
    public static final EssentialsLicense NOT_STARTED = new EssentialsLicense(null, "not started yet");

    private final LicenseGate gate;
    private final String unavailable;

    private EssentialsLicense(LicenseGate gate, String unavailable) {
        this.gate = gate;
        this.unavailable = unavailable;
    }

    /**
     * Reads {@code license.key} and activates. On a server licensed before this
     * returns at once from the cached authorization; on a first start it waits
     * up to 5 seconds for the licensing service. Never throws.
     *
     * @param dataDir    the mod's directory, {@code mods/MysticEssentials}
     * @param modVersion this build's version, shown in the license portal; may be null
     */
    public static EssentialsLicense start(Path dataDir, String modVersion, LicenseGate.Log log) {
        noticePrototypeLicense(dataDir, log);

        String url = LicensingEndpoint.url();
        if (url == null) {
            log.warn("This build has no licensing service configured; licensed modules stay off.");
            return new EssentialsLicense(null, "this build has no licensing service configured");
        }
        try {
            Map<String, PublicKey> keys = new LinkedHashMap<>();
            LicensingEndpoint.publicKeys().forEach((kid, raw) -> keys.put(kid, PublicKeyRing.parseRaw(raw)));
            LicenseGate gate = LicenseGate.builder(PRODUCT)
                    .displayName("Mystic Essentials")
                    .dataDir(dataDir)
                    .serverUrl(URI.create(url))
                    .publicKeys(new PublicKeyRing(keys))
                    .modVersion(modVersion == null || modVersion.isBlank() ? null : modVersion)
                    .log(log)
                    .build();
            gate.start();
            return new EssentialsLicense(gate, null);
        } catch (Throwable t) {
            log.warn("Licensing could not start (" + t + "); licensed modules stay off.");
            return new EssentialsLicense(null, "licensing could not start");
        }
    }

    /** Whether this server may use one licensed feature now. Cheap, never throws. */
    public boolean hasFeature(String feature) {
        try {
            return gate != null && gate.hasFeature(feature);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether this server may use Mystic Essentials' licensed side at all. */
    public boolean isLicensed() {
        try {
            return gate != null && gate.isLicensed();
        } catch (Throwable t) {
            return false;
        }
    }

    /** The licensed features this server has, in {@link #ALL} order. */
    public List<String> licensedFeatures() {
        return ALL.stream().filter(this::hasFeature).toList();
    }

    /** One line for {@code /mystic license} and the log. Never contains the key. */
    public String summaryLine() {
        if (gate == null) {
            return "Mystic Essentials license: " + unavailable + ".";
        }
        try {
            return gate.summaryLine();
        } catch (Throwable t) {
            return "Mystic Essentials license: unknown.";
        }
    }

    /** This server's licensing id, as the license portal lists it, or null before licensing starts. */
    public String serverId() {
        try {
            return gate == null ? null : gate.client().instance().id();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Re-reads {@code license.key}: a new key is activated, the same key checked
     * again, a removed key releases this server. Returns at once; the answer
     * arrives in the log and through {@link #onFeatureChange}.
     */
    public void reload() {
        if (gate == null) {
            return;
        }
        try {
            gate.reload();
        } catch (Throwable t) {
            // keep the previous state
        }
    }

    /**
     * Tells {@code listener} each time the feature turns on or off after start,
     * on the licensing thread. Does nothing when licensing is unavailable.
     */
    public void onFeatureChange(String feature, Consumer<Boolean> listener) {
        if (gate == null) {
            return;
        }
        try {
            gate.onFeatureChange(feature, listener);
        } catch (Throwable t) {
            // no notifications; the next /mystic reload still applies the license
        }
    }

    /** Stops renewing. The activation stays, so the next start picks up where this one left off. */
    @Override
    public void close() {
        if (gate != null) {
            try {
                gate.close();
            } catch (Throwable ignored) {
                // shutting down
            }
        }
    }

    private static void noticePrototypeLicense(Path dataDir, LicenseGate.Log log) {
        try {
            if (Files.exists(dataDir.resolve(PROTOTYPE_LICENSE_FILE))) {
                log.warn(PROTOTYPE_LICENSE_FILE + " is from the retired licensing prototype and is no longer"
                        + " read. Put your key from the license portal in license.key instead; "
                        + PROTOTYPE_LICENSE_FILE + ", server-id.txt and license-request.json can be deleted.");
            }
        } catch (Throwable ignored) {
            // a notice only
        }
    }
}
