package com.cheatprogram.audio;

/** Frequency-dependent reflected-tail presets. These are game material approximations, not measurements. */
public record ReflectionTone(double highReference,double lowReference,double highDecay,double lowDecay) {
    public static final ReflectionTone NEUTRAL=new ReflectionTone(5000,250,1,1);
    private static final ReflectionTone FABRIC=new ReflectionTone(1800,160,.24,.70);
    private static final ReflectionTone WOOD=new ReflectionTone(2800,220,.52,1.22);
    private static final ReflectionTone METAL=new ReflectionTone(6400,320,1.34,1.42);
    private static final ReflectionTone CRYSTAL=new ReflectionTone(8200,180,1.62,.65);
    private static final ReflectionTone GLASS=new ReflectionTone(7000,200,1.25,.75);
    private static final ReflectionTone STONE=new ReflectionTone(4400,250,.86,1.12);
    private static final ReflectionTone EARTH=new ReflectionTone(2100,180,.38,.84);
    private static final ReflectionTone WATER=new ReflectionTone(2400,200,.62,1.36);
    public ReflectionTone {
        lowReference=bound(lowReference,20,1000,250);
        highReference=bound(highReference,Math.max(1000,lowReference*10),20000,5000);
        highDecay=bound(highDecay,.1,2,1);lowDecay=bound(lowDecay,.1,2,1);
    }
    public static ReflectionTone material(String category) {
        return switch(category==null?"":category){
            case "metal","iron","copper","gold","netherite"->METAL;
            case "crystal","glow"->CRYSTAL;
            case "glass","ice"->GLASS;
            case "wood","bookshelf","bone"->WOOD;
            case "wool","carpet","soft","porous","sponge","hay","snow","powder_snow","sculk"->FABRIC;
            case "soil","sand","sandstone","netherrack","end_stone","leaves","organic","slime","honey"->EARTH;
            case "water","lava"->WATER;
            default->STONE;
        };
    }
    private static double bound(double x,double min,double max,double fallback){return Double.isFinite(x)?Math.max(min,Math.min(max,x)):fallback;}
}
