package io.github.d3vx3n0.chronicle.recovery;

/** Paper-free canonical codec vocabulary. Eligibility still requires the qualified live adapter. */
public final class InertStates {
    public static final String AIR = "minecraft:air", STONE = "minecraft:stone";
    private InertStates() {}
    public static boolean supports(String state) { return AIR.equals(state) || STONE.equals(state); }
}
