package com.cheatprogram.dev;
import com.cheatprogram.gui.TextField;
import org.lwjgl.glfw.GLFW;

/** Exercises committed Unicode through the same char pairs used by CompatScreen. */
public final class TextFieldUnicodeCheck {
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        var request = new TextField();
        String chinese = "建造一座樱花庭院，粒子围绕玩家旋转。";
        for (int cp : chinese.codePoints().toArray()) for (char value : Character.toChars(cp)) require(request.charTyped(value), "Chinese commit rejected");
        require(request.text.equals(chinese), "Chinese changed or duplicated");
        String extra = "𠮷😀";
        for (int cp : extra.codePoints().toArray()) for (char value : Character.toChars(cp)) request.charTyped(value);
        require(request.text.equals(chinese + extra), "supplementary characters split");
        request.keyPressed(GLFW.GLFW_KEY_BACKSPACE, 0);
        require(request.text.equals(chinese + "𠮷"), "emoji backspace split");
        request.keyPressed(GLFW.GLFW_KEY_BACKSPACE, 0);
        require(request.text.equals(chinese), "supplementary ideograph backspace split");
        request.keyPressed(GLFW.GLFW_KEY_A, GLFW.GLFW_MOD_CONTROL);
        request.charTyped('中'); request.charTyped('文');
        require(request.text.equals("中文"), "selection replacement lost Chinese");
        var bounded = new TextField(3);
        for (char c : "你𠮷好".toCharArray()) bounded.charTyped(c);
        require(bounded.text.equals("你𠮷"), "bounded input splits code points");
        require(bounded.viewport(2).text().equals("𠮷"), "visible input splits code points");
        int[] notifications = {0}; request.onChange = value -> notifications[0]++;
        request.charTyped('！');
        require(notifications[0] == 1, "one commit notified more than once");
        System.out.println("TEXT_FIELD_UNICODE_PASS Chinese supplementary-ideograph emoji editing selection viewport bounds single-commit");
    }
}
