package com.replaymod.core.utils;

import com.replaymod.core.mixin.TimerAccessor;
import net.minecraft.client.render.RenderTickCounter;

public class WrappedTimer extends RenderTickCounter.Dynamic {
    public static final float DEFAULT_MS_PER_TICK = 1000 / 20;

    protected final RenderTickCounter.Dynamic wrapped;
    protected boolean tickEnabled = true;

    public WrappedTimer(RenderTickCounter wrapped) {
        //#if MC>=12003
        super(20, 0, f -> f);
        //#elseif MC>=11400
        //$$ super(0, 0);
        //#else
        //$$ super(0);
        //#endif
        if (!(wrapped instanceof RenderTickCounter.Dynamic dynamic)) {
            throw new IllegalArgumentException("Replay timers require a dynamic tick counter");
        }
        this.wrapped = dynamic;
        copy(dynamic, this);
    }

    @Override
    public int beginRenderTick(long sysClock, boolean tick) {
        boolean previousTickEnabled = tickEnabled;
        tickEnabled = tick;
        try {
            // Keep dispatching through the single-argument hook used by InputReplayTimer.
            return beginRenderTick(sysClock);
        } finally {
            tickEnabled = previousTickEnabled;
        }
    }

    // Compatibility hook for timer subclasses; Dynamic's single-argument method is private.
    public
    //#if MC>=11600
    int
    //#else
    //$$ void
    //#endif
    beginRenderTick(
            //#if MC>=11400
            long sysClock
            //#endif
    ) {
        copy(this, wrapped);
        try {
            //#if MC>=11600
            return
            //#endif
            wrapped.beginRenderTick(
                    //#if MC>=11400
                    sysClock, tickEnabled
                    //#endif
            );
        } finally {
            copy(wrapped, this);
        }
    }

    // Copy raw fields, not the pause/freeze-adjusted or clamped public getters.
    // This includes every Dynamic field so installing/removing wrappers does not lose clock state.
    protected void copy(RenderTickCounter.Dynamic from, RenderTickCounter.Dynamic to) {
        TimerAccessor fromA = (TimerAccessor) from;
        TimerAccessor toA = (TimerAccessor) to;

        //#if MC<11600
        //$$ to.ticksThisFrame = from.ticksThisFrame;
        //#endif
        toA.setTickDelta(fromA.getTickDelta());
        toA.setLastSyncSysClock(fromA.getLastSyncSysClock());
        toA.setLastFrameDurationRaw(fromA.getLastFrameDurationRaw());
        toA.setLastDurationRaw(fromA.getLastDurationRaw());
        toA.setTickDeltaBeforePause(fromA.getTickDeltaBeforePause());
        toA.setTimeMillisRaw(fromA.getTimeMillisRaw());
        toA.setPaused(fromA.isPaused());
        toA.setTickFrozenRaw(fromA.isTickFrozen());
        toA.setTargetMillisPerTick(fromA.getTargetMillisPerTick());
        //#if MC>=11200
        toA.setTickLength(fromA.getTickLength());
        //#else
        //$$ toA.setTicksPerSecond(fromA.getTicksPerSecond());
        //$$ toA.setLastHRTime(fromA.getLastHRTime());
        //$$ toA.setTimerSpeed(fromA.getTimerSpeed());
        //$$ toA.setLastSyncHRClock(fromA.getLastSyncHRClock());
        //$$ toA.setCounter(fromA.getCounter());
        //$$ toA.setTimeSyncAdjustment(fromA.getTimeSyncAdjustment());
        //#endif
    }
}
