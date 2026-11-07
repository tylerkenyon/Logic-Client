package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.setting.ModeSetting;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;

public final class ClientSpoofer extends Module {
    private final ModeSetting brand = registerSetting(new ModeSetting(
        "Brand", "Client brand to report on the next connection.", "Vanilla", "Fabric", "Vanilla", "Lunar"));

    public ClientSpoofer() {
        super("Client Spoofer", "Change the Minecraft client brand sent to servers.", Category.PLAYER);
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
