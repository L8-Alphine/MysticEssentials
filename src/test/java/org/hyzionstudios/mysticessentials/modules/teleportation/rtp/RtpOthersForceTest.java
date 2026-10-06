package org.hyzionstudios.mysticessentials.modules.teleportation.rtp;

import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.rtp.RtpRequest;

/**
 * Dependency-free checks that {@code /rtp <player>} only skips the target's checks
 * (forces) for a sender holding {@code rtp.admin.force}, and never waives the cost.
 */
public final class RtpOthersForceTest {

    private static final UUID SENDER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID TARGET = UUID.fromString("00000000-0000-4000-8000-000000000002");

    private RtpOthersForceTest() {
    }

    public static void main(String[] args) {
        othersOnlyGoesThroughNormalChecks();
        adminForceStillForcesOthers();
        forceFlagForces();
        selfIsNeverForcedImplicitly();
        consoleForcesOnlyWithAdminForce();
        costIsNeverWaivedByTheCommand();
        selectorsAndActorArePassedOn();
    }

    private static void othersOnlyGoesThroughNormalChecks() {
        RtpRequest request = send(SENDER, TARGET, true, false, false);
        require(!request.isForce(), "a sender with only rtp.others forced the target");
        require(request.isAdminInitiated(), "the request lost who sent it");
    }

    private static void adminForceStillForcesOthers() {
        require(send(SENDER, TARGET, true, false, true).isForce(),
                "a sender with rtp.admin.force no longer forces /rtp <player>");
    }

    private static void forceFlagForces() {
        require(send(SENDER, TARGET, true, true, true).isForce(), "--force did not force");
        require(send(SENDER, SENDER, false, true, true).isForce(), "--force on yourself did not force");
    }

    private static void selfIsNeverForcedImplicitly() {
        require(!send(SENDER, SENDER, false, false, true).isForce(),
                "your own /rtp was forced just for holding rtp.admin.force");
    }

    private static void consoleForcesOnlyWithAdminForce() {
        require(send(null, TARGET, true, false, true).isForce(), "the console lost its force");
        require(!send(null, TARGET, true, false, false).isForce(),
                "a console sender without rtp.admin.force forced the target");
    }

    private static void costIsNeverWaivedByTheCommand() {
        for (boolean mayForce : new boolean[] {false, true}) {
            for (boolean flag : new boolean[] {false, true}) {
                require(!send(SENDER, TARGET, true, flag, mayForce).isBypassCost(),
                        "the command asked to waive the cost; only rtp.bypass.cost or forcing does");
            }
        }
    }

    private static void selectorsAndActorArePassedOn() {
        RtpRequest request = RtpCommand.request(TARGET, "wild", "overworld", SENDER, true, false, true,
                false);
        require(TARGET.equals(request.getPlayerId()), "wrong target");
        require("wild".equals(request.getProfileId()), "profile lost");
        require("overworld".equals(request.getWorld()), "world lost");
        require(SENDER.equals(request.getActorId()), "actor lost");
        require(request.isSilent(), "--silent lost");
    }

    private static RtpRequest send(UUID actor, UUID target, boolean adminInitiated, boolean forceFlag,
            boolean senderMayForce) {
        return RtpCommand.request(target, null, null, actor, adminInitiated, forceFlag, false, senderMayForce);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
