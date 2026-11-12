package dev.technix.mica.api.compat.v26_2;

import dev.technix.mica.api.SpriteBounds;
import dev.technix.mica.internal.MinecraftRenderCompat;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

public final class MinecraftCompatImpl_26_2 extends MinecraftRenderCompat {
    @NotNull
    public static Optional<SpriteBounds> itemIcon(@NotNull ItemStack stack,
                                                 @NotNull MinecraftCompatImpl_26_2 compat) {
        return compat.locateItemIcon(stack);
    }
}
