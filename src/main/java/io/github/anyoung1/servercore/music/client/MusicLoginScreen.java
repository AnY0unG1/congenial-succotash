package io.github.anyoung1.servercore.music.client;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import io.github.anyoung1.servercore.music.MusicApiPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** 管理员扫码界面：本地生成二维码，关闭时仅取消对应会话，不触碰已保存账号。 */
final class MusicLoginScreen extends Screen {
    final long session;
    private BitMatrix qr;
    private String message;
    private boolean complete;

    MusicLoginScreen(MusicApiPacket.Login packet) {
        super(Component.literal("服务器网易云扫码登录")); session = packet.session(); update(packet);
    }
    void update(MusicApiPacket.Login packet) {
        message = packet.message(); complete = packet.complete();
        if (complete) { qr = null; return; }
        if (!packet.url().isEmpty()) {
            try { qr = new QRCodeWriter().encode(packet.url(), BarcodeFormat.QR_CODE, 0, 0); }
            catch (Exception e) { message = "二维码生成失败，请关闭后重新执行 /music login"; }
        }
    }
    @Override protected void init() {
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
            .bounds((width - 100) / 2, height - 27, 100, 20).build());
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        g.text(font, title, (width - font.width(title)) / 2, 12, 0xFFFFFFFF);
        if (qr != null) {
            int scale = Math.max(1, Math.min(4, Math.min(width - 20, height - 100) / qr.getWidth()));
            int size = qr.getWidth() * scale, x = (width - size) / 2, y = 32 + (height - 100 - size) / 2;
            g.fill(x, y, x + size, y + size, 0xFFFFFFFF);
            for (int row = 0; row < qr.getHeight(); row++) for (int col = 0; col < qr.getWidth(); col++)
                if (qr.get(col, row)) g.fill(x + col * scale, y + row * scale, x + (col + 1) * scale, y + (row + 1) * scale, 0xFF000000);
        }
        String text = font.plainSubstrByWidth(message, width - 16);
        g.text(font, text, (width - font.width(text)) / 2, height - 51, 0xFF55DDCC);
        if (!complete) g.text(font, "关闭窗口会取消本次扫码登录", (width - font.width("关闭窗口会取消本次扫码登录")) / 2, height - 39, 0xFFCCCCCC);
    }
    @Override public void removed() {
        if (MusicClient.loginScreen == this) MusicClient.loginScreen = null;
        if (!complete && ClientPlayNetworking.canSend(MusicApiPacket.Request.TYPE))
            ClientPlayNetworking.send(new MusicApiPacket.Request(0, "cancel_login", "", session, 0));
    }
    @Override public boolean isPauseScreen() { return false; }
}
