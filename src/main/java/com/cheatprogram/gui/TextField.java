package com.cheatprogram.gui;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;
import com.cheatprogram.util.UnicodeText;

/** Minimal single-line text input state; drawing is done by the screen. */
public final class TextField {
	public final int maxLength;
	public String text = "";
	public int cursor;
	public boolean allSelected;
	public boolean masked;
	public Consumer<String> onChange = s -> {
	};
	public Runnable onEnter = () -> {
	};
	private long lastInput = System.currentTimeMillis();
	private char pendingHighSurrogate;

	public TextField(int maxLength) {
		this.maxLength = maxLength;
	}

	/** Requests and character settings keep the complete input rather than imposing a UI text cap. */
	public TextField() {
		this(Integer.MAX_VALUE);
	}

	public record View(String text, int cursor) { }

	/** Only the visible neighbourhood is rasterized; large pasted prompts remain intact in text. */
	public View viewport(int maximumCharacters) {
		int length = text.length();
		int caret = Math.max(0, Math.min(cursor, length));
		int start = Math.max(0, caret - maximumCharacters / 2);
		int end = Math.min(length, start + maximumCharacters);
		if (start > 0 && Character.isLowSurrogate(text.charAt(start))) start--;
		if (end < length && end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end++;
		String visible = masked ? "•".repeat(end - start) : text.substring(start, end);
		return new View(visible, caret - start);
	}

	public void setText(String value) {
		text = UnicodeText.clip(value, maxLength);
		cursor = text.length();
		allSelected = false;
	}

	public boolean caretVisible() {
		return ((System.currentTimeMillis() - lastInput) / 530) % 2 == 0;
	}

	public String displayText() {
		return masked ? "•".repeat(text.length()) : text;
	}

	private void changed() {
		lastInput = System.currentTimeMillis();
		onChange.accept(text);
	}

	public boolean keyPressed(int key, int modifiers) {
		pendingHighSurrogate=0;
		boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
		lastInput = System.currentTimeMillis();
		if (ctrl && key == GLFW.GLFW_KEY_A) {
			allSelected = !text.isEmpty();
			return true;
		}
		if (ctrl && key == GLFW.GLFW_KEY_C) {
			if (!masked) Minecraft.getInstance().keyboardHandler.setClipboard(text);
			return true;
		}
		if (ctrl && key == GLFW.GLFW_KEY_V) {
			insert(Minecraft.getInstance().keyboardHandler.getClipboard().replace("\n", " ").replace("\r", ""));
			return true;
		}
		switch (key) {
			case GLFW.GLFW_KEY_BACKSPACE -> {
				if (allSelected) clearAll();
				else if (cursor > 0) {
					int start = ctrl ? previousWord() : UnicodeText.previous(text,cursor);
					text = text.substring(0, start) + text.substring(cursor);
					cursor = start;
					changed();
				}
				return true;
			}
			case GLFW.GLFW_KEY_DELETE -> {
				if (allSelected) clearAll();
				else if (cursor < text.length()) {
					text = text.substring(0, cursor) + text.substring(UnicodeText.next(text,cursor));
					changed();
				}
				return true;
			}
			case GLFW.GLFW_KEY_LEFT -> {
				allSelected = false;
				cursor = Math.max(0, ctrl ? previousWord() : UnicodeText.previous(text,cursor));
				return true;
			}
			case GLFW.GLFW_KEY_RIGHT -> {
				allSelected = false;
				cursor = UnicodeText.next(text,cursor);
				return true;
			}
			case GLFW.GLFW_KEY_HOME -> {
				allSelected = false;
				cursor = 0;
				return true;
			}
			case GLFW.GLFW_KEY_END -> {
				allSelected = false;
				cursor = text.length();
				return true;
			}
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
				onEnter.run();
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	public boolean charTyped(char c) {
		if (c < 32 || c == 127) return false;
		if(Character.isHighSurrogate(c)){pendingHighSurrogate=c;return true;}
		if(Character.isLowSurrogate(c)){if(pendingHighSurrogate!=0){insert(new String(new char[]{pendingHighSurrogate,c}));pendingHighSurrogate=0;}return true;}
		pendingHighSurrogate=0;
		insert(String.valueOf(c));
		return true;
	}

	private void insert(String s) {
		if (allSelected) {
			text = "";
			cursor = 0;
			allSelected = false;
		}
		int room = maxLength - text.length();
		if (room <= 0 || s.isEmpty()) return;
		if (s.length() > room) s = UnicodeText.clip(s,room);
		text = text.substring(0, cursor) + s + text.substring(cursor);
		cursor += s.length();
		changed();
	}

	private void clearAll() {
		text = "";
		cursor = 0;
		allSelected = false;
		changed();
	}

	private int previousWord() {
		int i = cursor - 1;
		while (i > 0 && text.charAt(i - 1) == ' ') i--;
		while (i > 0 && text.charAt(i - 1) != ' ') i--;
		return Math.max(0, i);
	}
}
