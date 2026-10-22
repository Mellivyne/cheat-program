package com.cheatprogram.module.visual;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.core.setting.TextSetting;
import com.cheatprogram.gui.PersonalScreen;
import com.cheatprogram.render.PersonalAvatar;
import net.minecraft.client.gui.screens.Screen;

/** Local presentation only. Authentication, player UUIDs and network account names are unchanged. */
public final class Personal extends Module {
    private final TextSetting displayId = add(new TextSetting("displayId", "", 32));
    private final TextSetting avatar = add(new TextSetting("avatarPath", "", 2048));
    private final BoolSetting welcome = add(new BoolSetting("welcome", true));
    private final TextSetting cropSource = add(new TextSetting("avatarCropSource", "", 2048).visibleWhen(() -> false));
    private final NumberSetting cropX = add(new NumberSetting("avatarCropX", 0, 0, 1, 0).visibleWhen(() -> false));
    private final NumberSetting cropY = add(new NumberSetting("avatarCropY", 0, 0, 1, 0).visibleWhen(() -> false));
    private final NumberSetting cropWidth = add(new NumberSetting("avatarCropWidth", 1, 0, 1, 0).visibleWhen(() -> false));
    private final NumberSetting cropHeight = add(new NumberSetting("avatarCropHeight", 1, 0, 1, 0).visibleWhen(() -> false));
    private final BoolSetting cropStretch = add(new BoolSetting("avatarCropStretch", false).visibleWhen(() -> false));

    /** A non-destructive selection in original image UV space, shared by profile and welcome card. */
    public record AvatarCrop(double x, double y, double width, double height, boolean stretch) {
        public static final AvatarCrop FULL = new AvatarCrop(0, 0, 1, 1, false);
        public AvatarCrop {
            width = Math.max(1d / 65536, Math.min(1, Double.isFinite(width) ? width : 1));
            height = Math.max(1d / 65536, Math.min(1, Double.isFinite(height) ? height : 1));
            x = Math.max(0, Math.min(1 - width, Double.isFinite(x) ? x : 0));
            y = Math.max(0, Math.min(1 - height, Double.isFinite(y) ? y : 0));
        }
    }

    public Personal() { super("personal", Category.VISUAL); }
    @Override public boolean isOneShot() { return true; }
    @Override public void onEnable() { mc.setScreen(open(mc.screen)); setEnabled(false); }
    public String displayName() {
        String name = clean(displayId.get());
        return name.isBlank() ? authenticatedName() : name;
    }
    public String configuredDisplayName() { return clean(displayId.get()); }
    public void displayName(String name) { displayId.set(clean(name)); }
    public String authenticatedName() {
        return mc.player == null ? mc.getUser().getName() : mc.player.getGameProfile().name();
    }
    public String avatarPath() { return avatar.get() == null ? "" : avatar.get().strip(); }
    public void avatarPath(String path) {
        applyAvatar(path, AvatarCrop.FULL);
    }
    public AvatarCrop avatarCrop() {
        return hasAvatarCrop()
                ? new AvatarCrop(cropX.val(), cropY.val(), cropWidth.val(), cropHeight.val(), cropStretch.on()) : AvatarCrop.FULL;
    }
    public boolean hasAvatarCrop() { return !avatarPath().isBlank() && avatarPath().equals(cropSource.get()); }
    public void applyAvatar(String path, AvatarCrop selected) {
        String next = path == null ? "" : path.strip();
        AvatarCrop crop = selected == null ? AvatarCrop.FULL : selected;
        avatar.set(next); cropSource.set(next);
        cropX.set(crop.x()); cropY.set(crop.y()); cropWidth.set(crop.width()); cropHeight.set(crop.height());
        cropStretch.set(crop.stretch());
        PersonalAvatar.retry(avatarPath());
    }
    public boolean welcomeEnabled() { return welcome.on(); }
    public void welcomeEnabled(boolean value) { welcome.set(value); }
    public PersonalScreen open(Screen parent) { return new PersonalScreen(parent, this); }
    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("§.", "").replaceAll("[\\p{Cntrl}§]", "").strip();
    }
}
