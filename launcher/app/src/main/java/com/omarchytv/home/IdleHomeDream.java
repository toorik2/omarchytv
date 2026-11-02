package com.omarchytv.home;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.dreams.DreamService;

/**
 * "Idle: OmarchyTV, then sleep" screensaver, for TVs where OmarchyTV can't be the default home (Google TV).
 * Picked as the system screensaver, the system starts it after the screen-off timeout with no input:
 * <ul>
 *   <li>another app was in front (YouTube left on a menu, a paused film): OmarchyTV is opened and the TV wakes up,
 *       so the idle timer starts again from OmarchyTV;</li>
 *   <li>OmarchyTV was already in front: the screensaver ends at once and the system puts the TV to sleep (with
 *       HDMI-CEC on, the TV set switches off with it).</li>
 * </ul>
 * Apps that keep the screen on (a playing video) hold off the timeout as usual. It shows nothing and uses no network.
 * Settings: screensaver_components com.omarchytv.home/.IdleHomeDream, screensaver_enabled 1,
 * screensaver_activate_on_sleep 1, and secure sleep_timeout longer than system screen_off_timeout (or the system
 * sleeps without dreaming).
 */
public class IdleHomeDream extends DreamService {

    /** MainActivity paused this recently: it was in front when the screensaver came up (and paused because of it). */
    private static final long FRONT_GRACE_MS = 10_000L;
    /** Time for the OmarchyTV start to land under the screensaver before it turns translucent. */
    private static final long START_SETTLE_MS = 400L;
    /** Translucent but still dreaming for this long, so OmarchyTV's window shows and its turn-screen-on flag wakes. */
    private static final long WAKE_FINISH_MS = 1_500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean idleWake;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key ends it and the system wakes as usual
        setFullscreen(true);
        setScreenBright(false);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        // OmarchyTV idle too, or OmarchyTV must not take over (Originale, crash watchdog): let the TV sleep.
        if (homeWasFront() || Originale.isOriginal(this) || Watchdog.crashedTooOften(this)) {
            finish();
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setClass(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                .putExtra(MainActivity.EXTRA_IDLE_WAKE, true);
        try {
            startActivity(i);   // lands under the screensaver, so the old app is not shown again
        } catch (RuntimeException e) {
            finish();
            return;
        }
        idleWake = true;
        // Ending a screensaver after the idle timeout means sleep, and app wake locks can't wake the TV from it.
        // wakeUp() makes this window translucent first: OmarchyTV's window then shows while the TV is still dreaming,
        // and the window manager wakes the TV for its turn-screen-on flag (MainActivity.wakeFromIdle).
        handler.postDelayed(this::wakeUp, START_SETTLE_MS);
    }

    @Override
    public void onWakeUp() {
        if (!idleWake) {   // a key press or the system: end as usual
            super.onWakeUp();
            return;
        }
        handler.postDelayed(this::finish, WAKE_FINISH_MS);
    }

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    static boolean homeWasFront() {
        return MainActivity.homeFront || SystemClock.elapsedRealtime() - MainActivity.homeLeftAt < FRONT_GRACE_MS;
    }
}
