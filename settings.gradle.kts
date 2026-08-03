rootProject.name = "MysticEssentials"

// Offline license verification, kept deliberately separate from the mod:
// mystic-license-core has zero Hytale and zero third-party dependencies, so it
// compiles and tests on its own and can be lifted into its own repository (or
// consumed by the other Mystic mods) without untangling anything.
include("mystic-license-core")
include("mystic-license-example-mod")
