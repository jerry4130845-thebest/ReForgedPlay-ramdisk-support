package com.replaymod.render.mixin;

import com.replaymod.render.capturer.CubicOpenGlFrameCapturer;
import com.replaymod.render.hooks.EntityRendererHandler;
import net.minecraft.client.render.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import static com.replaymod.core.versions.MCVer.getMinecraft;

@Mixin(GameRenderer.class)
public abstract class Mixin_Omnidirectional_Rotation implements EntityRendererHandler.IEntityRenderer {
    // 1.21.1 stores the real view at slot 14, before setupFrustum and WorldRenderer.render.
    // The earlier MatrixStack only contains bob/hurt transforms applied to the projection.
    @ModifyVariable(method = "renderWorld", at = @At("STORE"), index = 14, require = 1)
    private Matrix4f replayModRender_setupCubicFrameRotation(Matrix4f view) {
        EntityRendererHandler handler = replayModRender_getHandler();
        if (handler == null || !(handler.data instanceof CubicOpenGlFrameCapturer.Data face)) {
            return view;
        }
        float quarterTurn = (float) (Math.PI / 2);
        Matrix4f rotation = switch (face) {
            case FRONT -> new Matrix4f().rotationY(0);
            case RIGHT -> new Matrix4f().rotationY(quarterTurn);
            case BACK -> new Matrix4f().rotationY((float) Math.PI);
            case LEFT -> new Matrix4f().rotationY(-quarterTurn);
            case TOP -> new Matrix4f().rotationX(-quarterTurn);
            case BOTTOM -> new Matrix4f().rotationX(quarterTurn);
        };
        getMinecraft().worldRenderer.scheduleTerrainUpdate();
        return rotation.mul(view);
    }
}
