package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

/**
 * wake_cec_service: some boxes wake from standby without the TV coming on. Android's own wake-up CEC fails (its
 * HDMI service has no playback device yet at that moment), and the firmware's one-touch-play attempt can time out
 * while the TV is in deep standby, with no retry, so the TV stays off. After every screen-on this starts the
 * firmware's one-touch-play service once more, as that retry. Nothing runs while the key is empty.
 */
final class WakeCec {
    /**
     * After screen-on: once the firmware's own attempt has finished or given up. On the TIM Box it starts 4 s
     * after wake and gives up 22 s later (26 s), and an attempt then still in progress swallows a new one; a
     * retry at about 45 s turned the TV on. When the TV is already on, the retry only re-announces the box as
     * the active source.
     */
    static final long DELAY_MS = 30_000L;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver receiver;

    WakeCec(Context context) {
        this.context = context;
    }

    /** Listens for screen-on and starts "package/.Service" after it; an empty or invalid value stops listening. */
    void start(String service) {
        stop();
        ComponentName target = service.isEmpty() ? null : ComponentName.unflattenFromString(service);
        if (target == null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                handler.removeCallbacksAndMessages(null);
                handler.postDelayed(() -> {
                    try {
                        context.startService(new Intent().setComponent(target));
                    } catch (RuntimeException ignored) {
                    }
                }, DELAY_MS);
            }
        };
        context.registerReceiver(receiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);
        if (receiver == null) return;
        context.unregisterReceiver(receiver);
        receiver = null;
    }
}
