package com.replaymod.core.mixin;

import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderTickCounter.Dynamic.class)
public interface TimerAccessor {
    @Accessor("prevTimeMillis")
    long getLastSyncSysClock();
    @Accessor("prevTimeMillis")
    void setLastSyncSysClock(long value);

    @Accessor("tickDelta")
    float getTickDelta();
    @Accessor("tickDelta")
    void setTickDelta(float value);

    @Accessor("lastFrameDuration")
    float getLastFrameDurationRaw();
    @Accessor("lastFrameDuration")
    void setLastFrameDurationRaw(float value);

    @Accessor("lastDuration")
    float getLastDurationRaw();
    @Accessor("lastDuration")
    void setLastDurationRaw(float value);

    @Accessor("tickDeltaBeforePause")
    float getTickDeltaBeforePause();
    @Accessor("tickDeltaBeforePause")
    void setTickDeltaBeforePause(float value);

    @Accessor("timeMillis")
    long getTimeMillisRaw();
    @Accessor("timeMillis")
    void setTimeMillisRaw(long value);

    @Accessor("paused")
    boolean isPaused();
    @Accessor("paused")
    void setPaused(boolean value);

    @Accessor("tickFrozen")
    boolean isTickFrozen();
    @Accessor("tickFrozen")
    void setTickFrozenRaw(boolean value);

    @Accessor("targetMillisPerTick")
    it.unimi.dsi.fastutil.floats.FloatUnaryOperator getTargetMillisPerTick();
    @Accessor("targetMillisPerTick")
    @Mutable
    void setTargetMillisPerTick(it.unimi.dsi.fastutil.floats.FloatUnaryOperator value);

    //#if MC>=11200
    @Accessor("tickTime")
    float getTickLength();
    @Accessor("tickTime")
    @Mutable
    void setTickLength(float value);
    //#else
    //$$ @Accessor
    //$$ float getTimerSpeed();
    //$$ @Accessor
    //$$ void setTimerSpeed(float value);
    //$$ @Accessor
    //$$ float getTicksPerSecond();
    //$$ @Accessor
    //$$ void setTicksPerSecond(float value);
    //$$ @Accessor
    //$$ double getLastHRTime();
    //$$ @Accessor
    //$$ void setLastHRTime(double value);
    //$$ @Accessor
    //$$ long getLastSyncHRClock();
    //$$ @Accessor
    //$$ void setLastSyncHRClock(long value);
    //$$ @Accessor
    //$$ double getTimeSyncAdjustment();
    //$$ @Accessor
    //$$ void setTimeSyncAdjustment(double value);
    //$$ @Accessor
    //$$ long getCounter();
    //$$ @Accessor
    //$$ void setCounter(long value);
    //#endif
}
