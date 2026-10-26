package io.github.anyoung1.servercore.music.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** 半透明控件保留不透明文字，以及原版键盘、鼠标交互和无障碍行为。 */
final class MusicWidgets {
    private MusicWidgets() {}

    private static void surface(GuiGraphicsExtractor g, int x, int y, int width, int height,
                                boolean highlighted, boolean active) {
        g.fill(x, y, x + width, y + height, !active ? 0x16081018 : highlighted ? 0x66203445 : 0x30081018);
        int border = !active ? 0x287D8995 : highlighted ? 0xCC88E5EE : 0x558FAEBB;
        g.fill(x, y, x + width, y + 1, border);
        g.fill(x, y + height - 1, x + width, y + height, border);
        g.fill(x, y, x + 1, y + height, border);
        g.fill(x + width - 1, y, x + width, y + height, border);
    }

    static final class TransparentButton extends Button {
        private final Font font;
        TransparentButton(Font font, String text, int x, int y, int width, int height, Runnable action) {
            super(x, y, width, height, Component.literal(text), b -> action.run(), DEFAULT_NARRATION);
            this.font = font;
        }
        @Override protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            surface(g, getX(), getY(), getWidth(), getHeight(), isHoveredOrFocused(), active);
            String label = font.plainSubstrByWidth(getMessage().getString(), Math.max(0, getWidth() - 6));
            g.text(font, label, getX() + (getWidth() - font.width(label)) / 2,
                getY() + (getHeight() - font.lineHeight) / 2, active ? 0xFFFFFFFF : 0xFF919BA5);
        }
    }

    static final class TransparentEditBox extends EditBox {
        TransparentEditBox(Font font, int x, int y, int width, int height, Component label) {
            super(font, x, y, width, height, label);
            setTextColor(0xFFFFFFFF);
        }
        // 保留输入框内部边距和光标定位，仅禁止绘制原版不透明背景。
        @Override public boolean isBordered() { return false; }
        @Override public int getInnerWidth() { return Math.max(0, getWidth() - 8); }
        @Override public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            surface(g, getX(), getY(), getWidth(), getHeight(), isFocused() || isHovered(), active);
            super.extractWidgetRenderState(g, mouseX, mouseY, delta);
        }
    }
}
