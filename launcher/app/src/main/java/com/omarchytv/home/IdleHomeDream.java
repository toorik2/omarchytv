package com.omarchytv.home;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
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
 * sleeps without dreaming). On TVs the wake-up needs the TURN_SCREEN_ON app-op:
 *   adb shell appops set com.omarchytv.home TURN_SCREEN_ON allow
 */
public class IdleHomeDream extends DreamService {

    /** MainActivity paused this recently: it was in front when the screensaver came up (and paused because of it). */
    private static final long FRONT_GRACE_MS = 10_000L;
    /** Time for the OmarchyTV start to land under the screensaver before it turns translucent. */
    private static final long START_SETTLE_MS = 400L;
    /** Translucent but still dreaming for this long, so OmarchyTV's window shows and its turn-screen-on flag wakes. */
    private static final long WAKE_FINISH_MS = 1_500L;
    private static final long WAKE_HOLD_MS = 3_000L;

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
        // No CATEGORY_HOME: on Android 14 a MAIN/HOME start makes this a home-type activity and OmarchyTV the
        // "home process", which takes the home-app launch exemption away from the stock launcher, so its remote
        // buttons (YouTube, Netflix, the star key) are then blocked as background launches.
        Intent i = new Intent(Intent.ACTION_MAIN).setClass(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                .putExtra(MainActivity.EXTRA_IDLE_WAKE, true);
        try {
            startActivity(i);   // lands under the screensaver, so the old app is not shown again
        } catch (RuntimeException e) {
            finish();
            return;
        }
        idleWake = true;
        handler.postDelayed(this::wake, START_SETTLE_MS);
    }

    /**
     * Ending a screensaver after the idle timeout means sleep: as soon as it starts waking, the power manager puts the
     * TV to sleep unless something woke it first. ACQUIRE_CAUSES_WAKEUP wakes it straight from dreaming (and restarts
     * the idle timer), but on TVs only with the TURN_SCREEN_ON app-op. Without it, the fallback is the gentle wake:
     * wakeUp() turns this window translucent and OmarchyTV's turn-screen-on window wakes the TV
     * (MainActivity.wakeFromIdle), which can lose the race against the sleep.
     */
    @SuppressWarnings("deprecation")
    private void wake() {
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP, "omarchytv:idle-home");
            wl.setReferenceCounted(false);
            wl.acquire(WAKE_HOLD_MS);
        } catch (RuntimeException ignored) {   // the fallback below
        }
        wakeUp();
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
