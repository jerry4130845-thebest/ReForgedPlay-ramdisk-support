//#if FABRIC>=1
package de.johni0702.minecraft.gui.versions.mixin;

import de.johni0702.minecraft.gui.versions.callbacks.RenderHudCallback;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.neoforged.neoforge.client.gui.GuiLayerManager;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//#else
//$$ import net.minecraft.client.util.math.MatrixStack;
//#endif

@Mixin(value = GuiLayerManager.class, remap = false)
public class Mixin_RenderHudCallback {
    @Inject(
            method = "renderInner",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/LayeredDrawer$Layer;render(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
                    remap = true),
            remap = false,
            require = 1
    )
    private void renderOverlay(DrawContext context, RenderTickCounter counter, CallbackInfo ci,
                               @Local GuiLayerManager.NamedLayer layer) {
        if (!VanillaGuiLayers.DEBUG_OVERLAY.equals(layer.name())) {
            return;
        }
        // Run before the layer's !hudHidden wrapper, after NeoForge's cancellable Pre event.
        // Like the old HUD callback, F1 still permits rendering while a screen is open.
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden && mc.currentScreen == null) {
            return;
        }
        RenderHudCallback.EVENT.invoker().renderHud(context, counter.getTickDelta(false));
    }
}
//#endif
