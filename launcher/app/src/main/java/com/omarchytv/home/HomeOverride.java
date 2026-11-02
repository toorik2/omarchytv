package com.omarchytv.home;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * Optional "OmarchyTV home button" accessibility service, for TVs where no app can be the default home.
 * On Google TV (Google TV Streamer, Philips/TPV Android 14, issue #22) launcherx keeps the HOME intent at
 * priority 2, and with it disabled setupwraith's RecoveryActivity (priority 1) takes Home instead: a sideloaded
 * app stays at priority 0, so set-home-activity prints Success and changes nothing. The Home key itself can't be
 * captured either. This lets Home open the stock launcher as usual, notices its home screen coming to the front
 * and opens OmarchyTV over it.
 *
 * It receives only window changes from the stock launchers in home_accessibility.xml, reads no window content
 * and changes no setting. Off until the user enables it (Settings > Accessibility), or over adb:
 *   adb shell settings put secure enabled_accessibility_services com.omarchytv.home/.HomeOverride
 *   adb shell settings put secure accessibility_enabled 1
 * It stands down while OmarchyTV is the real default home, in "Originale" mode, and after the crash watchdog
 * gave the home screen back.
 */
public class HomeOverride extends AccessibilityService {

    /** Ignore the stock home for this long after OmarchyTV sent the user there on purpose. */
    private static final long PASS_MS = 4_000L;

    private static volatile HomeOverride instance;
    private static volatile long passUntil;

    private final Set<String> homes = new HashSet<>();
    /** Double events per Home press (Fire OS) count once; loop guard of 4 takeovers in 15 s (issue #58). */
    private final TakeoverGuard guard = new TakeoverGuard();
    /**
     * Google TV's launcher re-opens its own home once when it comes back after a long time in the background
     * (BootModeAppToForeground: "Background timeout reached, starting home intent"), tens of ms after OmarchyTV was
     * started, so the guard coalesces it with the Home press and OmarchyTV stays covered. Look again after this long.
     */
    private static final long RECHECK_MS = 1_500L;
    /**
     * When that re-opened stock home shows up as an event (it does, ~0.1 s after our start, coalesced by the guard),
     * look again this soon instead of waiting the full RECHECK_MS: the stock home is then visible for ~0.4 s, not 1.5 s.
     * RECHECK_MS stays as the fallback in case MainActivity hadn't paused yet at this point.
     */
    private static final long FAST_RECHECK_MS = 350L;
    private final Runnable fastRecheck = () -> recheckNow();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastHomeEventAt, takeoverAt;
    private final Runnable recheck = this::recheckNow;

    private void recheckNow() {
        long t = SystemClock.elapsedRealtime();
        // Only when the stock home came up again after our start and OmarchyTV is not in front (not an app the user
        // opened meanwhile: that shows no stock-home event).
        if (lastHomeEventAt <= takeoverAt || MainActivity.homeFront || t < passUntil || standDown()
                || !guard.underLoopLimit(t)) return;
        takeOver(t);
    }

    static boolean enabled() {
        return instance != null;
    }

    /** OmarchyTV itself is opening the stock home (Back, "Not now", Originale): let it through. */
    static void pass() {
        passUntil = SystemClock.elapsedRealtime() + PASS_MS;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        loadHomes();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        handler.removeCallbacks(recheck);
        handler.removeCallbacks(fastRecheck);
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName(), cls = e.getClassName();
        if (pkg == null || cls == null || !homes.contains(pkg + "/" + cls)) return;
        long now = SystemClock.elapsedRealtime();
        lastHomeEventAt = now;
        if (takeoverAt > 0 && now > takeoverAt && now - takeoverAt < RECHECK_MS) {
            // The stock home came back right after our takeover (Google TV's background-timeout reset): retake soon.
            handler.removeCallbacks(fastRecheck);
            handler.postDelayed(fastRecheck, FAST_RECHECK_MS);
        }
        if (now < passUntil || standDown() || !guard.allow(pkg + "/" + cls, now)) return;
        takeOver(now);
    }

    private void takeOver(long now) {
        // No CATEGORY_HOME: on Android 14 a MAIN/HOME start makes this a home-type activity and OmarchyTV the
        // "home process", which takes the home-app launch exemption away from the stock launcher, so its remote
        // buttons (YouTube, Netflix, the star key) are then blocked as background launches.
        Intent i = new Intent(Intent.ACTION_MAIN).setClass(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
            guard.started(now);
            takeoverAt = now;
            handler.removeCallbacks(recheck);
            handler.postDelayed(recheck, RECHECK_MS);
        } catch (RuntimeException ignored) {   // the system refused the start: the stock home stays, nothing else
        }
    }

    @Override
    public void onInterrupt() {
    }

    /**
     * The HOME activities of the TV's default home app (launcherx on Google TV), as "package/class" (the event's
     * package and class name), and events from that package only: the service never hears about any other app.
     * Only the default home: setupwraith's RecoveryActivity (Wi-Fi/setup recovery) and Settings' FallbackHome are
     * HOME activities too and must never be covered.
     */
    private void loadHomes() {
        homes.clear();
        PackageManager pm = getPackageManager();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo def = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        String stock = def != null && def.activityInfo != null ? def.activityInfo.packageName : null;
        // "android" is the chooser (no default yet); our own package means there is nothing to override.
        if ("android".equals(stock) || getPackageName().equals(stock)) stock = null;
        if (stock != null) {
            for (ResolveInfo r : pm.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)) {
                if (r.activityInfo != null && stock.equals(r.activityInfo.packageName)) {
                    homes.add(stock + "/" + r.activityInfo.name);
                }
            }
        }
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        // Never empty: no filter would mean every app. With no stock home there is nothing to watch but ourselves.
        info.packageNames = new String[] {stock != null ? stock : getPackageName()};
        setServiceInfo(info);
    }

    private boolean standDown() {
        return HomeCheck.isDefaultHome(this) || Originale.isOriginal(this) || Watchdog.crashedTooOften(this);
    }
}
