package com.github.kunoisayami.telegramhiddenphone.resolve;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

/**
 * Persists DexKit-resolved member names so the (expensive) dex scan runs at most
 * once per Telegram version. The cache is keyed by the host app's versionCode:
 * an upgrade changes the key, so stale mappings are never reused.
 */
public final class SignatureCache {

    private static final String TAG = "TelegramHiddenPhone";
    private static final String PREFS = "thp_sig_cache";
    private static final String KEY_VERSION = "__version_code";

    private final SharedPreferences prefs;
    private final boolean valid;

    public SignatureCache(Context context) {
        SharedPreferences p = null;
        boolean ok = false;
        try {
            p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long current = hostVersionCode(context);
            long stored = p.getLong(KEY_VERSION, -1L);
            if (stored != current) {
                Log.d(TAG, "SignatureCache: version changed " + stored + " -> " + current + ", clearing");
                p.edit().clear().putLong(KEY_VERSION, current).apply();
            }
            ok = true;
        } catch (Exception e) {
            Log.w(TAG, "SignatureCache: unavailable, running without cache", e);
        }
        this.prefs = p;
        this.valid = ok;
    }

    private static long hostVersionCode(Context context) throws PackageManager.NameNotFoundException {
        PackageManager pm = context.getPackageManager();
        String name = context.getPackageName();
        PackageInfo info;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            info = pm.getPackageInfo(name, PackageManager.PackageInfoFlags.of(0));
        } else {
            info = pm.getPackageInfo(name, 0);
        }
        return info.getLongVersionCode();
    }

    public String get(String key) {
        if (!valid) return null;
        try {
            return prefs.getString(key, null);
        } catch (Exception e) {
            return null;
        }
    }

    public void put(String key, String value) {
        if (!valid || value == null) return;
        try {
            prefs.edit().putString(key, value).apply();
        } catch (Exception e) {
            Log.w(TAG, "SignatureCache: put failed for " + key, e);
        }
    }
}
