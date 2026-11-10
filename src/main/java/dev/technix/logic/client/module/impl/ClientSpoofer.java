package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.setting.ModeSetting;
import dev.technix.logic.client.setting.BooleanSetting;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.chat.contents.KeybindContents;
import net.minecraft.world.level.block.entity.SignBlockEntity;

public final class ClientSpoofer extends Module {
    private final ModeSetting brand = registerSetting(new ModeSetting(
        "Brand", "Client brand to report on the next connection.", "Vanilla", "Fabric", "Vanilla", "Lunar"));
    private final BooleanSetting hideChannels = registerSetting(new BooleanSetting("Hide mod channels", "Suppress channel registration advertisements; reconnect to apply.", true));
    private final BooleanSetting protectTranslations = registerSetting(new BooleanSetting("Block translation probes", "Prevent sign editors from revealing translations and keybinds.", true));
    private final BooleanSetting blockForcedPacks = registerSetting(new BooleanSetting("Anti force resource pack", "Report required server resource packs as loaded without applying them.", false));

    public ClientSpoofer() {
        super("Client Spoofer", "Protect client brand, channel registrations and sign text.", Category.PLAYER);
    }

    public boolean suppress(Packet<?> packet) {
        if (!enabled() || !hideChannels.get() || !(packet instanceof ServerboundCustomPayloadPacket custom)) return false;
        String id = custom.payload().type().id().toString();
        return id.equals("minecraft:register") || id.equals("minecraft:unregister") || id.equals("c:register");
    }

    public void protectSign(SignBlockEntity sign, boolean front) {
        if (!protectsTranslations()) return;
        var text = sign.getText(front);
        for (int i = 0; i < 4; i++) {
            var raw = text.getMessage(i, false);
            var filtered = text.getMessage(i, true);
            text = text.setMessage(i, safeSignComponent(raw), safeSignComponent(filtered));
        }
        sign.setText(text, front);
    }

    public boolean protectsTranslations() { return enabled() && protectTranslations.get(); }

    public boolean spoofPack(Packet<?> packet) {
        return enabled() && blockForcedPacks.get() && packet instanceof ClientboundResourcePackPushPacket pack && pack.required();
    }

    public static java.util.List<ServerboundResourcePackPacket> packResponses(ClientboundResourcePackPushPacket pack) {
        return java.util.List.of(
            new ServerboundResourcePackPacket(pack.id(), ServerboundResourcePackPacket.Action.ACCEPTED),
            new ServerboundResourcePackPacket(pack.id(), ServerboundResourcePackPacket.Action.DOWNLOADED),
            new ServerboundResourcePackPacket(pack.id(), ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED));
    }

    private static Component safeSignComponent(Component component) {
        if (component.getContents() instanceof PlainTextContents
                && component.getSiblings().stream().allMatch(ClientSpoofer::isPlainSignComponent)) return component;
        return Component.literal(safeSignText(component));
    }

    private static boolean isPlainSignComponent(Component component) {
        return component.getContents() instanceof PlainTextContents
            && component.getSiblings().stream().allMatch(ClientSpoofer::isPlainSignComponent);
    }

    public static String safeSignText(Component component) {
        var contents = component.getContents();
        String result = contents instanceof PlainTextContents literal ? literal.text()
            : contents instanceof TranslatableContents translated ? (translated.getFallback() == null ? translated.getKey() : translated.getFallback())
            : contents instanceof KeybindContents keybind ? keybind.getName() : "";
        StringBuilder safe = new StringBuilder(result);
        for (var sibling : component.getSiblings()) safe.append(safeSignText(sibling));
        return safe.toString();
    }

    public Packet<?> rewriteBrand(Packet<?> packet) {
        if (!(packet instanceof ServerboundCustomPayloadPacket custom) || !(custom.payload() instanceof BrandPayload)
                || !enabled()) return packet;

        String reportedBrand = switch (brand.get()) {
            case "Fabric" -> "fabric";
            case "Vanilla" -> "vanilla";
             
            case "Lunar" -> "lunarclient:v2.12.0-2349,fabric";
            default -> throw new IllegalStateException("Unknown client brand: " + brand.get());
        };
        return new ServerboundCustomPayloadPacket(new BrandPayload(reportedBrand));
    }
}
