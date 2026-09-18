package com.github.kunoisayami.telegramhiddenphone;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import com.github.kunoisayami.telegramhiddenphone.resolve.TelegramSymbols;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;


/**
 * Hides the current account's own phone number in Telegram by masking the
 * {@code phone} field on the object returned from {@code UserConfig.getCurrentUser()}.
 *
 * <p>The {@code TLRPC$User} type and its {@code phone} field are obfuscated in recent
 * Telegram releases; they are recovered at runtime by {@link TelegramSymbols}
 * (reflection first, DexKit only as a fallback) rather than by hardcoded names.
 */
public class xposedInit implements IXposedHookLoadPackage {

    public final static List<String> hookPackages = Arrays.asList(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta");

    private static final String TAG = "TelegramHiddenPhone";

    private static final String FAKE_PHONE = "12345678901";

    private TelegramSymbols symbols;

    /** Resolved lazily on the first getCurrentUser() result. */
    private volatile Field phoneField;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!hookPackages.contains(lpparam.packageName)) {
            return;
        }
        XposedBridge.log("Loaded app:" + lpparam.packageName);

        // Resolution needs a Context for the per-version signature cache. It is not
        // available this early, so defer setup until the app has a Context.
        XposedHelpers.findAndHookMethod(
                "android.app.Application",
                lpparam.classLoader,
                "attach",
                Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Context context = (Context) param.args[0];
                            if (context == null || symbols != null) return;
                            symbols = new TelegramSymbols(lpparam.classLoader, context);
                            hookHidePhone(lpparam);
                        } catch (Throwable t) {
                            Log.e(TAG, "handleLoadPackage: setup failed", t);
                        }
                    }
                });
    }

    private void hookHidePhone(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> userConfig = XposedHelpers.findClassIfExists(
                "org.telegram.messenger.UserConfig", lpparam.classLoader);
        // TLRPC$User is obfuscated; recovered from getCurrentUser()'s return type.
        Class<?> telegramUser = symbols.userClass();

        if (userConfig == null || telegramUser == null) {
            Log.w(TAG, "hookHidePhone: UserConfig or User class not resolved");
            return;
        }

        try {
            XposedBridge.hookMethod(userConfig.getDeclaredMethod("getCurrentUser"), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (result == null) return;
                    try {
                        // The field name is obfuscated to a single letter, and matching it
                        // needs a live user object, so resolve lazily on the first call.
                        if (phoneField == null) {
                            phoneField = symbols.phoneField(result);
                            if (phoneField == null) {
                                Log.w(TAG, "hookHidePhone: phone field unresolved, passing through");
                                return;
                            }
                        }
                        phoneField.set(result, FAKE_PHONE);
                        param.setResult(result);
                    } catch (Throwable t) {
                        Log.e(TAG, "hookHidePhone: could not mask phone", t);
                    }
                }
            });
            Log.d(TAG, "hookHidePhone: hooked UserConfig.getCurrentUser");
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "hookHidePhone: getCurrentUser not found", e);
        }
    }
}
