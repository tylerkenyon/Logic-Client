package dev.technix.mica.api;

import dev.technix.mica.internal.backend.vulkan.VulkanContext;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Optional;



public interface MinecraftCompat {
    static MinecraftCompat current() {
        //? if <26.3 {
        return new dev.technix.mica.api.compat.v26_2.MinecraftCompatImpl_26_2();
        //?} else {
        /*return new dev.technix.mica.api.compat.v26_3.MinecraftCompatImpl_26_3();
        *///?}
    }


    @NotNull
    Optional<VulkanContext> currentVulkanContext();



    boolean isVulkanRendererActive();

    @Nullable
    VkCommandBuffer activeCommandBuffer();

    long vkImageViewFor(@NotNull Identifier textureId);



    @NotNull
    Optional<SpriteBounds> locateSprite(@NotNull Identifier atlasId, @NotNull Identifier spriteId);



    @NotNull
    default Optional<SpriteBounds> locateItemIcon(@NotNull ItemStack stack) {
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        Identifier registryId = stack.getItem().builtInRegistryHolder().unwrapKey()
                .map(key -> key.identifier()).orElse(null);
        if (registryId == null) {
            return Optional.empty();
        }
        Identifier spriteId = Identifier.fromNamespaceAndPath(registryId.getNamespace(),
                "item/" + registryId.getPath());
        return locateSprite(VanillaAtlases.ITEMS, spriteId);
    }


}
