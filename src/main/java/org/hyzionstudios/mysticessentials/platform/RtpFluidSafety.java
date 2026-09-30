package org.hyzionstudios.mysticessentials.platform;

/** Interprets fluid ids returned by Hytale's chunk API for RTP safety checks. */
final class RtpFluidSafety {

    private RtpFluidSafety() {
    }

    /**
     * Valid fluid asset ids are positive: zero is {@code Fluid.EMPTY_ID}. The
     * Update 6 component-backed reader uses {@link Integer#MIN_VALUE} when the
     * chunk section has no fluid component, which likewise means the sampled
     * block is dry.
     */
    static boolean isPresent(int fluidId) {
        return fluidId > 0;
    }
}
