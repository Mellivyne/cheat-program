package com.cheatprogram.audio;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.LiquidBlock;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Game-oriented three-band material presets, not laboratory measurements of Minecraft blocks. */
public final class AcousticMaterials {
    public record Profile(String category, double lowAbsorption, double midAbsorption,
                          double highAbsorption, double scattering) {
        public Profile {
            category = category == null ? "stone" : category;
            lowAbsorption = finite(lowAbsorption, .05, .005, .99);
            midAbsorption = finite(midAbsorption, .08, .005, .99);
            highAbsorption = finite(highAbsorption, .12, .005, .99);
            scattering = finite(scattering, .25, 0, 1);
        }

        public double meanAbsorption() { return (lowAbsorption + midAbsorption + highAbsorption) / 3; }

        /** Pressure transmission per metre, including the material's approximate mass loss.
         * Absorption alone cannot describe a wall: a reflective concrete wall still blocks sound.
         * These are game-scale presets, not measured transmission-loss certificates. */
        public double transmission(int band) {
            return switch (category) {
                case "iron", "metal", "netherite" -> band(band, .035, .005, .0008);
                case "copper", "gold" -> band(band, .045, .007, .0012);
                case "obsidian", "deepslate", "basalt" -> band(band, .07, .009, .0015);
                case "stone", "polished_stone", "concrete", "brick", "terracotta", "ceramic", "calcite" -> band(band, .12, .018, .003);
                case "glass", "crystal", "ice", "glow" -> band(band, .035, .007, .0015);
                case "wood", "bookshelf" -> band(band, .30, .10, .025);
                case "wool", "carpet", "soft", "hay", "sponge", "porous", "sculk", "powder_snow" -> band(band, .42, .10, .012);
                case "snow", "sand", "soil", "sandstone", "netherrack", "end_stone" -> band(band, .23, .06, .009);
                case "slime", "honey", "organic", "bone" -> band(band, .24, .055, .01);
                case "leaves" -> band(band, .80, .61, .37);
                case "water" -> band(band, .74, .57, .38);
                case "lava" -> band(band, .54, .30, .12);
                default -> band(band, .12, .018, .003);
            };
        }

        private static double band(int index, double low, double mid, double high) {
            return index <= 0 ? low : index == 1 ? mid : high;
        }
    }

    public static final Profile WOOL = new Profile("wool", .26, .72, .92, .78);
    public static final Profile CARPET = new Profile("carpet", .12, .54, .86, .66);
    public static final Profile METAL = new Profile("metal", .025, .035, .045, .08);
    public static final Profile CRYSTAL = new Profile("crystal", .07, .035, .015, .13);
    public static final Profile GLASS = new Profile("glass", .12, .055, .025, .05);
    public static final Profile WOOD = new Profile("wood", .18, .14, .11, .34);
    public static final Profile STONE = new Profile("stone", .045, .065, .09, .33);
    public static final Profile POLISHED_STONE = new Profile("polished_stone", .035, .045, .055, .10);
    public static final Profile SOIL = new Profile("soil", .24, .39, .57, .67);
    public static final Profile SAND = new Profile("sand", .30, .52, .73, .76);
    public static final Profile SNOW = new Profile("snow", .37, .68, .91, .75);
    public static final Profile ICE = new Profile("ice", .055, .035, .025, .08);
    public static final Profile LEAVES = new Profile("leaves", .17, .42, .73, .92);
    public static final Profile WATER = new Profile("water", .12, .065, .035, .12);
    public static final Profile LAVA = new Profile("lava", .26, .41, .63, .28);
    public static final Profile CERAMIC = new Profile("ceramic", .06, .075, .10, .18);
    public static final Profile POROUS = new Profile("porous", .40, .76, .94, .90);
    public static final Profile SOFT = new Profile("soft", .31, .51, .70, .56);
    public static final Profile ORGANIC = new Profile("organic", .23, .36, .52, .59);
    public static final Profile GLOW = new Profile("glow", .075, .055, .04, .23);
    public static final Profile COPPER = new Profile("copper", .025, .03, .04, .10);
    public static final Profile IRON = new Profile("iron", .02, .025, .035, .07);
    public static final Profile GOLD = new Profile("gold", .035, .025, .02, .06);
    public static final Profile NETHERITE = new Profile("netherite", .018, .025, .035, .13);
    public static final Profile CONCRETE = new Profile("concrete", .025, .04, .055, .12);
    public static final Profile TERRACOTTA = new Profile("terracotta", .055, .075, .10, .16);
    public static final Profile BRICK = new Profile("brick", .04, .06, .08, .29);
    public static final Profile SANDSTONE = new Profile("sandstone", .10, .19, .29, .48);
    public static final Profile DEEPSLATE = new Profile("deepslate", .035, .05, .075, .43);
    public static final Profile BASALT = new Profile("basalt", .035, .045, .065, .27);
    public static final Profile OBSIDIAN = new Profile("obsidian", .02, .025, .04, .07);
    public static final Profile NETHERRACK = new Profile("netherrack", .12, .23, .34, .63);
    public static final Profile END_STONE = new Profile("end_stone", .09, .16, .23, .49);
    public static final Profile BOOKSHELF = new Profile("bookshelf", .20, .32, .46, .85);
    public static final Profile HAY = new Profile("hay", .25, .62, .89, .91);
    public static final Profile SPONGE = new Profile("sponge", .43, .79, .96, .94);
    public static final Profile SCULK = new Profile("sculk", .35, .65, .88, .80);
    public static final Profile SLIME = new Profile("slime", .33, .51, .69, .29);
    public static final Profile HONEY = new Profile("honey", .38, .57, .76, .20);
    public static final Profile POWDER_SNOW = new Profile("powder_snow", .47, .78, .97, .88);
    public static final Profile BONE = new Profile("bone", .07, .10, .16, .41);
    public static final Profile CALCITE = new Profile("calcite", .065, .105, .16, .37);

    private static final List<Profile> PROFILES = List.of(WOOL, CARPET, METAL, CRYSTAL, GLASS, WOOD,
            STONE, POLISHED_STONE, SOIL, SAND, SNOW, ICE, LEAVES, WATER, LAVA, CERAMIC,
            POROUS, SOFT, ORGANIC, GLOW, COPPER, IRON, GOLD, NETHERITE, CONCRETE, TERRACOTTA,
            BRICK, SANDSTONE, DEEPSLATE, BASALT, OBSIDIAN, NETHERRACK, END_STONE, BOOKSHELF,
            HAY, SPONGE, SCULK, SLIME, HONEY, POWDER_SNOW, BONE, CALCITE);
    private static final Map<String, Profile> BY_CATEGORY = PROFILES.stream()
            .collect(Collectors.toUnmodifiableMap(Profile::category, Function.identity()));

    private AcousticMaterials() {}

    public static List<Profile> profiles() { return PROFILES; }
    public static Profile byCategory(String category) { return BY_CATEGORY.getOrDefault(category, STONE); }

    /** Tags include modded blocks; names cover families that vanilla does not put in a common tag. */
    public static Profile resolve(BlockState state) {
        if (state == null) return STONE;
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        // An ore block is mostly host rock, rather than a one-metre solid sheet of metal.
        if (id.endsWith("_ore")) return id.contains("deepslate") ? DEEPSLATE
                : id.contains("nether") ? NETHERRACK : STONE;
        if (state.is(BlockTags.WOOL_CARPETS) || id.endsWith("carpet")) return CARPET;
        if (state.is(BlockTags.WOOL) || id.contains("wool")) return WOOL;
        if (id.contains("sponge")) return SPONGE;
        if (id.contains("sculk")) return SCULK;
        if (id.contains("moss")) return POROUS;
        if (id.equals("powder_snow")) return POWDER_SNOW;
        if (state.is(BlockTags.SNOW) || id.contains("snow")) return SNOW;
        if (state.is(BlockTags.ICE) || id.endsWith("ice")) return ICE;
        if (state.is(BlockTags.LEAVES) || id.contains("leaves") || id.contains("azalea")
                || id.contains("coral") || id.contains("vine")) return LEAVES;
        if (id.contains("amethyst") || id.contains("crystal") || id.contains("quartz")) return CRYSTAL;
        if (id.contains("glass") || id.contains("tinted_glass")) return GLASS;
        if (id.contains("copper")) return COPPER;
        if (id.contains("netherite") || id.contains("ancient_debris")) return NETHERITE;
        if (id.contains("gold")) return GOLD;
        if (id.contains("iron")) return IRON;
        if (id.contains("anvil") || id.equals("chain") || id.contains("heavy_core")
                || id.contains("cauldron") || id.contains("hopper")) return METAL;
        if (id.contains("bookshelf")) return BOOKSHELF;
        if (id.contains("hay")) return HAY;
        if (id.contains("slime")) return SLIME;
        if (id.contains("honey")) return HONEY;
        if (id.contains("bone")) return BONE;
        if (state.is(BlockTags.PLANKS) || state.is(BlockTags.LOGS) || state.is(BlockTags.WOODEN_DOORS)
                || state.is(BlockTags.WOODEN_STAIRS) || state.is(BlockTags.WOODEN_SLABS)
                || state.is(BlockTags.WOODEN_FENCES) || state.is(BlockTags.WOODEN_TRAPDOORS)
                || id.contains("bamboo") || id.contains("bookshelf") || id.contains("barrel")
                || id.contains("chest") || id.contains("crafting") || id.contains("wood")
                || id.contains("stem") || id.contains("hyphae")) return WOOD;
        if (state.is(BlockTags.SAND) || state.is(BlockTags.CONCRETE_POWDER)
                || id.contains("gravel") || id.equals("soul_sand")) return SAND;
        if (state.is(BlockTags.DIRT) || id.contains("mud") || id.contains("farmland")
                || id.contains("soul_soil") || id.equals("clay")) return SOIL;
        if (id.contains("concrete")) return CONCRETE;
        if (state.is(BlockTags.TERRACOTTA) || id.contains("terracotta")) return TERRACOTTA;
        if (id.contains("brick")) return BRICK;
        if (id.contains("pot")) return CERAMIC;
        if (id.contains("slime") || id.contains("honey") || id.contains("bed")
                || id.contains("hay") || id.contains("wart")) return SOFT;
        if (id.contains("pumpkin") || id.contains("melon") || id.contains("mushroom")
                || id.contains("cactus") || id.contains("sugar") || id.contains("bone")
                || id.contains("dried_kelp") || id.contains("cake") || id.contains("coral")) return ORGANIC;
        if (id.contains("glowstone") || id.contains("sea_lantern") || id.contains("shroomlight")
                || id.contains("froglight")) return GLOW;
        if (id.contains("obsidian")) return OBSIDIAN;
        if (id.contains("deepslate")) return DEEPSLATE;
        if (id.contains("basalt")) return BASALT;
        if (id.contains("netherrack")) return NETHERRACK;
        if (id.contains("end_stone")) return END_STONE;
        if (id.contains("sandstone")) return SANDSTONE;
        if (id.contains("calcite") || id.contains("limestone")) return CALCITE;
        if (id.contains("polished") || id.contains("smooth")
                || id.contains("diamond") || id.contains("emerald")) return POLISHED_STONE;
        // A waterlogged solid is still its solid material; fluids only supply the exposed liquid surface.
        if (state.getBlock() instanceof LiquidBlock) {
            if (state.getFluidState().is(FluidTags.WATER)) return WATER;
            if (state.getFluidState().is(FluidTags.LAVA)) return LAVA;
        }
        return STONE;
    }

    private static double finite(double value, double fallback, double low, double high) {
        return Math.max(low, Math.min(high, Double.isFinite(value) ? value : fallback));
    }
}
