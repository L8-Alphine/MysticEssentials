MysticEssentials — Machinima scene assets
==========================================

Cinematic scenes played by the Tutorial module's "machinima" scene provider are
loaded from here, by scene id, and shipped to the client inline in the
UpdateMachinimaScene play packet.

Resolution order for a scene id (e.g. "first_join_spawn_intro"):

  1. Operator override (recommended, no rebuild needed):
       mods/MysticEssentials/modules/tutorial/scenes/<sceneId>.scene
       (".machinima" is also accepted)

  2. Bundled asset shipped inside the mod jar (this folder):
       Common/Resources/MysticEssentials/Machinima/<sceneId>.scene

If neither exists, the module sends a name-only play packet, so a scene the
client already has under that name still plays.

To add a scene:
  - Export it from the in-game Machinima tool as a .scene file.
  - Either drop it in the operator "scenes" folder above (then run
    /tutorial reload), or place it in this folder and rebuild the mod.

Reference the scene id from a tutorial definition's "machinima.sceneId" field
(see modules/tutorial/tutorials/*.json).
