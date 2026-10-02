package dev.exiledddev.deathhandler;

/**
 * The damage rule for /immortal, kept free of Bukkit so it can be unit-tested.
 */
public final class ImmortalMath {

    private ImmortalMath() {
    }

    /**
     * Whether a hit would take an immortal player below the minimum health. Absorption hearts soak
     * damage first, like in vanilla.
     */
    public static boolean wouldDropBelow(final double health, final double absorption, final double damage, final double minHealth) {
        return health + absorption - damage < minHealth;
    }

    /**
     * The health an immortal player is left with after a blocked hit: the minimum, or their current
     * health if it's already lower (a blocked hit never heals).
     */
    public static double protectedHealth(final double health, final double minHealth) {
        return Math.min(health, minHealth);
    }
}
