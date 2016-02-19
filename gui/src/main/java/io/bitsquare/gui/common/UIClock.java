package io.bitsquare.gui.common;

import io.bitsquare.common.Clock;
import org.reactfx.util.FxTimer;
import org.reactfx.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedList;
import java.util.List;

public class UIClock implements Clock {
    private static final Logger log = LoggerFactory.getLogger(UIClock.class);
    private Timer timer;

    private final List<Listener> listeners = new LinkedList<>();
    private long counter = 0;

    public UIClock() {
    }

    @Override
    public void start() {
        if (timer == null)
            timer = FxTimer.runPeriodically(Duration.ofSeconds(1), () -> {
                listeners.stream().forEach(Listener::onSecondTick);
                counter++;
                if (counter >= 60) {
                    counter = 0;
                    listeners.stream().forEach(Listener::onMinuteTick);
                }
            });
    }

    @Override
    public void stop() {
        timer.stop();
        timer = null;
        counter = 0;
    }

    @Override
    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }
}