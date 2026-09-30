package org.hyzionstudios.mysticessentials.platform;

/** Regression checks for Hytale chunk-fluid sentinel handling during RTP. */
public final class RtpFluidSafetyTest {

    private RtpFluidSafetyTest() {
    }

    public static void main(String[] args) {
        require(!RtpFluidSafety.isPresent(0), "the empty-fluid asset was treated as liquid");
        require(!RtpFluidSafety.isPresent(Integer.MIN_VALUE),
                "a missing FluidSection was treated as liquid");
        require(RtpFluidSafety.isPresent(1), "the unknown-fluid asset was treated as dry");
        require(RtpFluidSafety.isPresent(2), "a registered fluid asset was treated as dry");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
