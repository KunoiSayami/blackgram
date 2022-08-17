package com.github.kunoisayami.telegramhiddenphone;

import android.util.Log;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;


public class xposedInit implements IXposedHookLoadPackage {


    public final static List<String> hookPackages = Arrays.asList(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta");

    private static final String TAG = "org.telegram.hidden.phone.xposedInit";


    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {

        if (!hookPackages.contains(lpparam.packageName)) {
            return;
        }

        XposedBridge.log("Loaded app:" + lpparam.packageName);

        //Class<?> DrawerUserCellClass = XposedHelpers.findClassIfExists("org.telegram.ui.Cells.DrawerProfileCell", lpparam.classLoader);
        Class<?> TelegramUser = XposedHelpers.findClassIfExists("org.telegram.tgnet.TLRPC.User", lpparam.classLoader);

        /*if (DrawerUserCellClass != null && TelegramUser != null) {
            XposedBridge.hookMethod(DrawerUserCellClass.getDeclaredMethod("setUser", TelegramUser, boolean.class), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    Field f = TelegramUser.getDeclaredField("phone");
                    f.setAccessible(true);
                    f.set(param.args[0], "12345678901");
                    //String s = (String) f.get(param.thisObject);
                }
            });
            Log.d(TAG, "Hooked slide");
        }*/

        Class<?> UserConfig = XposedHelpers.findClassIfExists("org.telegram.messenger.UserConfig", lpparam.classLoader);
        if (UserConfig != null) {
            XposedBridge.hookMethod(UserConfig.getDeclaredMethod("getCurrentUser"), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    super.afterHookedMethod(param);
                    Object result = param.getResult();
                    if (result == null)
                        return;
                    //Log.e(TAG, "afterHookedMethod: test throw", new RuntimeException());
                    Field f = TelegramUser.getDeclaredField("phone");
                    f.setAccessible(true);
                    f.set(result, "12345678901");
                    param.setResult(result);
                }
            });
            Log.d(TAG, "handleLoadPackage: hooked");
        }

    }
}
