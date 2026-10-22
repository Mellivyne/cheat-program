package com.cheatprogram.module.visual;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;

/** Opt-in presentation only; disabling this module leaves the original GUI path intact. */
public final class PanelTransitions extends Module {
    private static final String[] ENTRANCES={"panel_spring","panel_smooth","panel_sticky","panel_flip","panel_rise","panel_zoom","panel_curtain"};
    private final ModeSetting opening=add(new ModeSetting("opening","panel_spring",ENTRANCES));
    private final ModeSetting closing=add(new ModeSetting("closing","panel_smooth",ENTRANCES));
    private final ModeSetting page=add(new ModeSetting("page","panel_book","panel_book","panel_slide","panel_crossfade","panel_flip","panel_sticky","panel_liquid","panel_none"));
    private final NumberSetting openDuration=add(new NumberSetting("openDuration",420,120,1500,10,"ms"));
    private final NumberSetting closeDuration=add(new NumberSetting("closeDuration",260,100,1000,10,"ms"));
    private final NumberSetting pageDuration=add(new NumberSetting("pageDuration",360,100,1000,10,"ms")).visibleWhen(()->!page.is("panel_none"));
    private final NumberSetting strength=add(new NumberSetting("strength",100,20,180,5,"%"));
    public PanelTransitions(){super("paneltransitions",Category.VISUAL);}
    public String opening(){return opening.get();}
    public String closing(){return closing.get();}
    public String page(){return page.get();}
    public float openDuration(){return openDuration.floatVal();}
    public float closeDuration(){return closeDuration.floatVal();}
    public float pageDuration(){return pageDuration.floatVal();}
    public float strength(){return strength.floatVal()/100;}
    @Override public String suffix(){return opening.display()+" · "+page.display();}
}
