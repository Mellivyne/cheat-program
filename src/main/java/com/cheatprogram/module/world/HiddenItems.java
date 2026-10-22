package com.cheatprogram.module.world;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.TextSetting;
import com.cheatprogram.util.ServerCompanionBridge;

/** Server-held escrow, not a fake client inventory. Escrowed items cannot be used until retrieved. */
public final class HiddenItems extends Module {
    public final TextSetting itemIds = add(new TextSetting("itemIds", "", 2048));
    public final TextSetting slots = add(new TextSetting("slots", "", 256));
    public final BoolSetting restoreOnDisable = add(new BoolSetting("restoreOnDisable", true));
    private boolean applied;
    public HiddenItems() { super("hiddenitems", Category.WORLD); }
    @Override public void onEnable() { applied = false; }
    @Override public void onTick() { if (!applied) sealMarked(); }
    @Override public void onDisable() {
        applied = false;
        ServerCompanionBridge.cancelActions("vault store ");
        if (restoreOnDisable.on()) retrieveAll();
    }
    public void sealMarked() {
        String ids = sanitize(itemIds.get()), marked = sanitize(slots.get());
        if (ids.isEmpty() && marked.isEmpty()) { applied = true; return; }
        if (ServerCompanionBridge.action("vault store " + (ids.isEmpty() ? "-" : ids) + " " + (marked.isEmpty() ? "-" : marked))) applied = true;
    }
    public void retrieveAll() { ServerCompanionBridge.cancelActions("vault store "); ServerCompanionBridge.action("vault take"); }
    public void refreshVault() { ServerCompanionBridge.action("vault status"); }
    private static String sanitize(String value) { return value.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_:,.-]", ""); }
    @Override public String suffix() {
        return ServerCompanionBridge.status("vault") + ", " + Lang.t("ui.hiddenitems.stored", ServerCompanionBridge.vaultCount());
    }
}
