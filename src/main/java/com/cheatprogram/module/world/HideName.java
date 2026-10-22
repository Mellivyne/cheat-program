package com.cheatprogram.module.world;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.util.ServerCompanionBridge;

/** Other clients' name tags are controlled by the server's real scoreboard teams. */
public final class HideName extends Module {
    public HideName() { super("hidename", Category.WORLD); }
    @Override public void onTick() { ServerCompanionBridge.request("hide", "on"); }
    @Override public void onDisable() { if (ServerCompanionBridge.ready()) ServerCompanionBridge.request("hide", "off"); }
    @Override public String suffix() { return ServerCompanionBridge.status("hide"); }
}
