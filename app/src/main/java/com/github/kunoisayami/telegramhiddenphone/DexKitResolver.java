package com.github.kunoisayami.telegramhiddenphone;

import android.util.Log;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;
import org.luckypray.dexkit.wrap.DexMethod;

import java.lang.reflect.Method;
import java.util.Collections;

/**
 * Resolves the two obfuscated Telegram methods this module hooks by searching
 * the dex bytecode with structural signatures instead of hardcoded names, so
 * the module keeps working across Telegram releases where R8 renames everything.
 *
 * <p>For a UI-display-only goal, every place Telegram renders a phone number
 * funnels through {@code PhoneFormat.format(String) -> String}. Hooking that one
 * method covers the drawer, settings and profile without ever touching the
 * obfuscated {@code TLRPC.User.phone} field. DexKit locates it by string
 * anchors that R8 preserves:
 * <ul>
 *   <li>the {@code PhoneFormat} class embeds the literals
 *       {@code "PhoneFormats.dat"} (asset name) and {@code "0123456789+*#"};</li>
 *   <li>{@code format} is its {@code (String) -> String} method that uses the
 *       {@code "+"} prefix literal.</li>
 * </ul>
 *
 * <p>{@code getCurrentUser()} is still resolved, only so the hook can learn the
 * user's real number once (to know which formatted strings to rewrite).
 */
public final class DexKitResolver {

    private static final String TAG = "org.telegram.hidden.phone.DexKitResolver";

    /** Asset file loaded by PhoneFormat; a string literal R8 cannot rename. */
    private static final String PHONE_FORMAT_ASSET = "PhoneFormats.dat";
    /** Allowed-char constant inside PhoneFormat; disambiguates the class. */
    private static final String PHONE_FORMAT_CHARS = "0123456789+*#";

    /** UserConfig prefs-name typo literal, used to pin getCurrentUser's class. */
    private static final String USER_CONFIG_STRING = "userconfing";

    /** Descriptors resolved by {@link #resolve(String)}; null fields mean "not found". */
    public static final class Resolved {
        public final String formatMethod;        // DexMethod.serialize()
        public final String getCurrentUserMethod; // DexMethod.serialize()

        Resolved(String formatMethod, String getCurrentUserMethod) {
            this.formatMethod = formatMethod;
            this.getCurrentUserMethod = getCurrentUserMethod;
        }

        /** The format hook alone is enough to hide the number in the UI. */
        public boolean hasFormat() {
            return formatMethod != null;
        }
    }

    private DexKitResolver() {
    }

    /**
     * Runs DexKit over the host APK and resolves the methods. Returns a partial
     * {@link Resolved} (with nulls) rather than throwing when a member can't be
     * found, so the caller can fall back to hardcoded names.
     *
     * @param apkPath host APK path, from {@code lpparam.appInfo.sourceDir}
     */
    public static Resolved resolve(String apkPath) {
        System.loadLibrary("dexkit");

        String formatMethod = null;
        String getCurrentUser = null;

        try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {
            MethodData format = findPhoneFormatMethod(bridge);
            if (format != null) {
                formatMethod = format.toDexMethod().serialize();
            } else {
                Log.w(TAG, "resolve: PhoneFormat.format not found");
            }

            MethodData getUser = findGetCurrentUser(bridge);
            if (getUser != null) {
                getCurrentUser = getUser.toDexMethod().serialize();
            } else {
                Log.w(TAG, "resolve: getCurrentUser() not found");
            }
        } catch (Throwable t) {
            Log.e(TAG, "resolve: DexKit search failed", t);
        }

        return new Resolved(formatMethod, getCurrentUser);
    }

    /**
     * Finds {@code PhoneFormat.format(String) -> String}: a public instance
     * method returning String, taking one String, declared on the class that
     * loads the {@code PhoneFormats.dat} asset.
     */
    private static MethodData findPhoneFormatMethod(DexKitBridge bridge) {
        ClassData phoneFormat = findPhoneFormatClass(bridge);
        if (phoneFormat == null) {
            return null;
        }
        MethodDataList candidates = phoneFormat.findMethod(
                FindMethod.create().matcher(
                        MethodMatcher.create()
                                .returnType("java.lang.String")
                                .paramTypes("java.lang.String")));
        // format() strips/rebuilds around the "+" country-code prefix; that
        // literal separates it from other (String)->String helpers on the class.
        for (MethodData m : candidates) {
            if (methodUsesString(m, "+")) {
                return m;
            }
        }
        // Fall back to the sole (String)->String method if unambiguous.
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static ClassData findPhoneFormatClass(DexKitBridge bridge) {
        // Any method embedding the distinctive PhoneFormat literals belongs to
        // the PhoneFormat class; take its declaring class.
        for (String anchor : new String[]{PHONE_FORMAT_ASSET, PHONE_FORMAT_CHARS}) {
            MethodDataList methods = bridge.findMethod(
                    FindMethod.create().matcher(
                            MethodMatcher.create().usingStrings(
                                    Collections.singletonList(anchor),
                                    StringMatchType.Equals,
                                    true)));
            for (MethodData m : methods) {
                ClassData declared = m.getDeclaredClass();
                if (declared != null) {
                    return declared;
                }
            }
        }
        return null;
    }

    /**
     * Finds {@code UserConfig.getCurrentUser()}: a no-arg method whose declaring
     * class uses the {@code "userconfing"} prefs-name literal.
     */
    private static MethodData findGetCurrentUser(DexKitBridge bridge) {
        MethodDataList methods = bridge.findMethod(
                FindMethod.create().matcher(
                        MethodMatcher.create().usingStrings(
                                Collections.singletonList(USER_CONFIG_STRING),
                                StringMatchType.Contains,
                                true)));
        // Among UserConfig's methods, getCurrentUser is the no-arg one whose
        // return type carries a "phone" String field (i.e. TLRPC.User).
        for (MethodData m : methods) {
            ClassData declared = m.getDeclaredClass();
            if (declared == null) {
                continue;
            }
            MethodData getter = noArgUserGetter(declared);
            if (getter != null) {
                return getter;
            }
        }
        return null;
    }

    private static MethodData noArgUserGetter(ClassData userConfigClass) {
        for (MethodData m : userConfigClass.getMethods()) {
            if (!m.getParamTypeNames().isEmpty()) {
                continue;
            }
            String ret = m.getReturnTypeName();
            if (ret == null || ret.startsWith("java.") || ret.startsWith("android.")) {
                continue;
            }
            ClassData retClass = m.getReturnType();
            if (retClass != null && hasStringField(retClass, "phone")) {
                return m;
            }
        }
        return null;
    }

    private static boolean hasStringField(ClassData clazz, String preferredName) {
        boolean anyString = false;
        try {
            for (org.luckypray.dexkit.result.FieldData f : clazz.getFields()) {
                if (!"java.lang.String".equals(f.getTypeName())) {
                    continue;
                }
                if (preferredName.equals(f.getName())) {
                    return true;
                }
                anyString = true;
            }
        } catch (Throwable ignored) {
        }
        // On obfuscated builds the name is gone; a String field is a weak but
        // acceptable signal since we only use this to pick getCurrentUser.
        return anyString;
    }

    private static boolean methodUsesString(MethodData method, String value) {
        try {
            java.util.List<String> strings = method.getUsingStrings();
            return strings != null && strings.contains(value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Resolves a cached method descriptor back to a reflective {@link Method}. */
    public static Method methodFromDescriptor(String descriptor, ClassLoader cl) {
        try {
            return new DexMethod(descriptor).getMethodInstance(cl);
        } catch (Throwable t) {
            Log.w(TAG, "methodFromDescriptor: " + descriptor, t);
            return null;
        }
    }
}
