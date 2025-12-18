package com.alphine.mysticessentials.commands;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.commands.admin.KillCmd;
import com.alphine.mysticessentials.commands.admin.ModlistCmd;
import com.alphine.mysticessentials.commands.admin.RecipeCmd;
import com.alphine.mysticessentials.commands.admin.RenameCmds;
import com.alphine.mysticessentials.commands.afk.AfkCmd;
import com.alphine.mysticessentials.commands.afk.AfkPoolSetupCmd;
import com.alphine.mysticessentials.commands.chat.*;
import com.alphine.mysticessentials.commands.homes.DelHomeCmd;
import com.alphine.mysticessentials.commands.homes.HomeCmd;
import com.alphine.mysticessentials.commands.homes.HomesCmd;
import com.alphine.mysticessentials.commands.homes.SetHomeCmd;
import com.alphine.mysticessentials.commands.kits.CreateKitCmd;
import com.alphine.mysticessentials.commands.kits.DelKitCmd;
import com.alphine.mysticessentials.commands.kits.KitCmd;
import com.alphine.mysticessentials.commands.misc.*;
import com.alphine.mysticessentials.commands.mod.*;
import com.alphine.mysticessentials.commands.tp.*;
import com.alphine.mysticessentials.commands.vaults.VaultAdminCMD;
import com.alphine.mysticessentials.commands.vaults.VaultCMD;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;

public final class CommandRegistrar {
    private CommandRegistrar() {
    }

    private static boolean on(java.util.function.Supplier<Boolean> s) {
        try {
            Boolean b = s.get();
            return b == null || b;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Convenience: index multiple roots to this mod id.
     */
    private static void idx(String... roots) {
        for (String r : roots) CommandIndex.indexRoot(r, MysticEssentialsCommon.MOD_ID);
    }

    public static void registerAll(MysticEssentialsCommon common, CommandDispatcher<CommandSourceStack> d) {

        // --------- ADMIN / UTIL ---------
        new com.alphine.mysticessentials.commands.admin.ReloadCmd(common).register(d);
        idx("mereload"); // include alias if your ReloadCmd registers one

        new HelpCmd().register(d);
        idx("help");

        new RepairCmd().register(d);
        idx("repair", "fix");

        new KillCmd().register(d);
        idx("kill");

        new SleepCmd().register(d);
        idx("sleep", "rest");

        new RenameCmds().register(d);
        idx("rename", "lore");

        new RecipeCmd().register(d);
        idx("recipe", "recipes", "clearrecipes");

        new EcShareCmd().register(d);
        idx("ecshare");

        new InvShareCmd().register(d);
        idx("invshare");

        if (common.getModInfoService() != null) {
            new ModlistCmd(common.getModInfoService()).register(d);
            idx("modlist");
        }

        // --------- AFK ---------
        if (on(() -> common.cfg.features.enableAfkSystem)) {
            // AFK system enabled
            new AfkCmd(common.afk).register(d);
            idx("afk");
            new AfkPoolSetupCmd(common.afk, common.cfg, common.afkPools).register(d);
            idx("afkpool");
        }


        // --------- CORE (Homes/Warps/TP/Spawn) ---------
        if (on(() -> common.cfg.features.enableHomesWarpsTP)) {

            // Create ONE executor for all teleport commands
            // (So warmup + cooldown behavior is consistent everywhere)
            var tpExec = new com.alphine.mysticessentials.teleport.TeleportExecutor(
                    common.cooldowns,
                    common.warmups
            );

            new SetHomeCmd(common.homes).register(d);
            idx("sethome");

            // If your HomeCmd currently takes (homes, cooldowns, warmups, pdata),
            // change it to (homes, tpExec, pdata) and update this line accordingly:
            new HomeCmd(common.homes, tpExec, common.pdata).register(d);
            idx("home");

            new DelHomeCmd(common.homes).register(d);
            idx("delhome");

            new HomesCmd(common.homes).register(d);
            idx("homes");

            // WarpCmd: change constructor to (warps, tpExec, pdata)
            new WarpCmd(common.warps, tpExec, common.pdata).register(d);
            idx("warp", "warps", "setwarp", "delwarp");

            // SpawnCmds: change constructor to (spawn, tpExec, pdata)
            new SpawnCmds(common.spawn, tpExec, common.pdata).register(d);
            idx("spawn", "setspawn");

            // TpDirectCmds: you already switched it to (TeleportExecutor, PlayerDataStore)
            new TpDirectCmds(tpExec, common.pdata).register(d);
            idx("tp", "tppos", "tphere", "tpo");

            // TpaCmds: change constructor to (tpas, tpExec, pdata)
            new TpaCmds(common.tpas, tpExec, common.pdata).register(d);
            idx("tpa", "tpahere", "tpaccept", "tpdeny", "tpcancel");

            // BackCmds: change constructor to (pdata, tpExec)
            new BackCmds(common.pdata, tpExec).register(d);
            idx("back", "deathback");
        }

        // --------- MISC ---------
        if (on(() -> common.cfg.features.enableMiscCommands)) {
            new GodCmd(common.god).register(d);
            idx("god");

            new FlyCmd().register(d);
            idx("fly");

            new HealCmd().register(d);
            idx("heal");

            new FeedCmd().register(d);
            idx("feed");

            new JumpCmd(common.pdata).register(d);
            idx("jump");

            new NearCmd().register(d);
            idx("near");

            new TimeCmd().register(d);
            idx("time", "day", "night");

            new WeatherCmd().register(d);
            idx("weather", "sun", "rain", "thunder");

            new SpeedCmd().register(d);
            idx("speed", "walkspeed", "flyspeed");

            new WorkbenchCmd().register(d);
            idx("workbench", "wb", "craft");

            new GmCmd().register(d);
            // Usual GM roots
            idx("gm", "gmc", "gms", "gmsp", "gma");

            new InvseeFullCmd(common.pdata).register(d);
            idx("invsee");

            new AnvilCmd().register(d);
            idx("anvil");

            new EnchantCmd().register(d);
            idx("enchant");

            new PlaytimeCmds().register(d);
            idx("playtime", "topplaytime");

            new EnderChestCmd().register(d);
            idx("enderchest", "ec", "echest");
        }

        // --------- Chat System ---------
        if (on(() -> common.cfg.features.enableChatSystem)) {
            new IgnoreCmd().register(d);
            idx("ignore", "unignore", "ignores", "ignoredby");

            new MsgCmd().register(d);
            idx("msg", "message", "tell", "whisper", "w", "pm");

            new ReplyCmd().register(d);
            idx("reply", "r");

            new SocialSpyCmd().register(d);
            idx("socialspy", "ss");

            new ChannelCmd().register(d);
            idx("channel", "ch", "channels");

            new ClearChatCmd().register(d);
            idx("clearchat", "cc");

            new BroadcastCmd().register(d);
            idx("broadcast", "bc", "bcast");

            new ShoutCmd().register(d);
            idx("shout", "sh", "yell");

        }

        // --------- MODERATION ---------
        if (on(() -> common.cfg.features.enableModerationSystem)) {
            new WarnCmd(common.punish, common.audit).register(d);
            idx("warn");

            new KickCmd(common.audit).register(d);
            idx("kick");

            new BanCmds(common.punish, common.audit).register(d);
            // Typical ban roots BanCmds might expose
            idx("ban", "tempban", "unban");

            new IpBanCmds(common.punish, common.audit).register(d);
            idx("ipban", "unipban");

            new MuteCmds(common.punish, common.audit).register(d);
            idx("mute", "tempmute", "unmute");

            new FreezeCmd(common.punish, common.audit).register(d);
            idx("freeze", "unfreeze");

            new JailCmds(common.punish, common.pdata, common.audit).register(d);
            idx("jail", "unjail", "setjail", "deljail");

            new HistoryCmd(common.audit).register(d);
            idx("history");
        }

        // --------- KITS ---------
        if (on(() -> common.cfg.features.enableKits)) {
            new CreateKitCmd(common.kits).register(d);
            idx("createkit");

            new DelKitCmd(common.kits).register(d);
            idx("delkit");

            new KitCmd(common.kits, common.kitsPlayers).register(d);
            idx("kit", "kits");
        }

        // Vaults
        if (on(() -> common.cfg.features.enableVaultSystem)) {
            VaultCMD.register(d);
            idx("vault", "vaults");

            VaultAdminCMD.register(d);
            idx("vaultadmin", "vaultadmincmd");
        }
    }
}
