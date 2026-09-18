package com.github.kunoisayami.telegramhiddenphone;

import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;


/**
 * Hides the current account's own phone number in Telegram's UI.
 *
 * <p>Every place Telegram renders a phone number goes through
 * {@code PhoneFormat.format(String) -> String}. Hooking that single method and
 * rewriting the return value whenever the input is our own number covers the
 * drawer, settings and profile without depending on the obfuscated
 * {@code TLRPC.User.phone} field. The method is located by
 * {@link DexKitResolver} via string anchors R8 preserves, with a hardcoded-name
 * fallback for unobfuscated builds.
 */
public class xposedInit implements IXposedHookLoadPackage {

    public final static List<String> hookPackages = Arrays.asList(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta");

    private static final String TAG = "org.telegram.hidden.phone.xposedInit";

    /** The fake number shown in place of the real one (digits only, no "+"). */
    private static final String FAKE_PHONE = "12345678901";

    private static final String PREFS_NAME = "dexkit_cache";
    private static final String KEY_VERSION = "resolved_version";
    private static final String KEY_FORMAT = "format_method";
    private static final String KEY_GET_CURRENT_USER = "get_current_user";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!hookPackages.contains(lpparam.packageName)) {
            return;
        }
        XposedBridge.log("Loaded app:" + lpparam.packageName);

        DexKitResolver.Resolved resolved = resolveWithCache(lpparam);
        final ClassLoader cl = lpparam.classLoader;

        Method formatMethod = null;
        Method getCurrentUser = null;
        if (resolved != null) {
            if (resolved.formatMethod != null) {
                formatMethod = DexKitResolver.methodFromDescriptor(resolved.formatMethod, cl);
            }
            if (resolved.getCurrentUserMethod != null) {
                getCurrentUser = DexKitResolver.methodFromDescriptor(resolved.getCurrentUserMethod, cl);
            }
        }

        // Fallback for unobfuscated builds where names survive.
        if (formatMethod == null) {
            formatMethod = findFormatByName(cl);
        }
        if (getCurrentUser == null) {
            getCurrentUser = findGetCurrentUserByName(cl);
        }

        if (formatMethod == null) {
            XposedBridge.log(TAG + ": PhoneFormat.format not resolved; skipping");
            return;
        }

        // The real number is captured passively: whenever Telegram itself calls
        // getCurrentUser(), we read the phone off its result. Held in a
        // 1-element array so the getCurrentUser hook can fill it and the format
        // hook can read it. getCurrentUser is an instance method on the
        // UserConfig singleton, so we never invoke it ourselves.
        final String[] realPhone = {null};

        if (getCurrentUser != null) {
            XposedBridge.hookMethod(getCurrentUser, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    super.afterHookedMethod(param);
                    Object user = param.getResult();
                    if (user == null) {
                        return;
                    }
                    try {
                        Field phone = user.getClass().getDeclaredField("phone");
                        phone.setAccessible(true);
                        Object value = phone.get(user);
                        if (value instanceof String && !((String) value).isEmpty()) {
                            realPhone[0] = (String) value;
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "capture real phone", t);
                    }
                }
            });
        }

        XposedBridge.hookMethod(formatMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                if (param.args.length < 1 || !(param.args[0] instanceof String)) {
                    return;
                }
                String real = realPhone[0];
                if (real == null || real.isEmpty()) {
                    return; // real number not seen yet; leave output untouched
                }
                // Inputs arrive as "+<digits>"; compare on digits only.
                if (digitsOf((String) param.args[0]).equals(digitsOf(real))) {
                    param.setResult(formatFake(param));
                }
            }
        });
        Log.d(TAG, "handleLoadPackage: hooked PhoneFormat.format");
    }

    /**
     * Produces the replacement display string. Re-runs the original formatter on
     * the fake number so the output keeps Telegram's own spacing/country
     * formatting, preserving the "+" prefix when the real input had one.
     */
    private static String formatFake(XC_MethodHook.MethodHookParam param) {
        String input = (String) param.args[0];
        String prefix = input.startsWith("+") ? "+" : "";
        try {
            return (String) XposedBridge.invokeOriginalMethod(
                    param.method, param.thisObject, new Object[]{prefix + FAKE_PHONE});
        } catch (Throwable t) {
            // If re-invoking fails, fall back to a plain fake string.
            return prefix + FAKE_PHONE;
        }
    }

    private static String digitsOf(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Method findFormatByName(ClassLoader cl) {
        Class<?> pf = XposedHelpers.findClassIfExists(
                "org.telegram.PhoneFormat.PhoneFormat", cl);
        if (pf == null) {
            pf = XposedHelpers.findClassIfExists(
                    "org.telegram.messenger.PhoneFormat.PhoneFormat", cl);
        }
        if (pf == null) {
            return null;
        }
        try {
            Method m = pf.getDeclaredMethod("format", String.class);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "findFormatByName: no format(String)", e);
            return null;
        }
    }

    private static Method findGetCurrentUserByName(ClassLoader cl) {
        Class<?> uc = XposedHelpers.findClassIfExists(
                "org.telegram.messenger.UserConfig", cl);
        if (uc == null) {
            return null;
        }
        try {
            Method m = uc.getDeclaredMethod("getCurrentUser");
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "findGetCurrentUserByName: no getCurrentUser", e);
            return null;
        }
    }

    /**
     * Returns resolved descriptors, reading the per-versionCode cache first and
     * only invoking DexKit on a miss.
     */
    private DexKitResolver.Resolved resolveWithCache(XC_LoadPackage.LoadPackageParam lpparam) {
        long versionCode = hostVersionCode(lpparam);

        SharedPreferences prefs = null;
        try {
            Object context = getHostContext(lpparam);
            prefs = (SharedPreferences) XposedHelpers.callMethod(
                    context, "getSharedPreferences", PREFS_NAME, 0);
        } catch (Throwable ignored) {
            // Context not reachable this early; fall through to a fresh search.
        }

        if (prefs != null && versionCode != -1
                && prefs.getLong(KEY_VERSION, -1) == versionCode) {
            DexKitResolver.Resolved cached = new DexKitResolver.Resolved(
                    prefs.getString(KEY_FORMAT, null),
                    prefs.getString(KEY_GET_CURRENT_USER, null));
            if (cached.hasFormat()) {
                Log.d(TAG, "resolveWithCache: cache hit for v" + versionCode);
                return cached;
            }
        }

        DexKitResolver.Resolved fresh = DexKitResolver.resolve(lpparam.appInfo.sourceDir);

        if (prefs != null && versionCode != -1 && fresh.hasFormat()) {
            prefs.edit()
                    .putLong(KEY_VERSION, versionCode)
                    .putString(KEY_FORMAT, fresh.formatMethod)
                    .putString(KEY_GET_CURRENT_USER, fresh.getCurrentUserMethod)
                    .apply();
            Log.d(TAG, "resolveWithCache: cached results for v" + versionCode);
        }
        return fresh;
    }

    private static long hostVersionCode(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Object context = getHostContext(lpparam);
            PackageManager pm = (PackageManager) XposedHelpers.callMethod(context, "getPackageManager");
            PackageInfo info = pm.getPackageInfo(lpparam.packageName, 0);
            return info.getLongVersionCode();
        } catch (Throwable t) {
            Log.w(TAG, "hostVersionCode: unavailable", t);
            return -1;
        }
    }

    private static Object getHostContext(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> activityThread = XposedHelpers.findClass(
                "android.app.ActivityThread", lpparam.classLoader);
        return XposedHelpers.callStaticMethod(activityThread, "currentApplication");
    }
}
