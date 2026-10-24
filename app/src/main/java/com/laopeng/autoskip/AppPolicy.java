package com.laopeng.autoskip;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

/** 应用列表与运行时共用的系统应用黑名单，避免隐藏后仍执行全局规则。 */
final class AppPolicy {
    private AppPolicy() {}

    static boolean isHiddenSystemApp(ApplicationInfo app) {
        return app != null && (isColorOsPackage(app.packageName)
                || (app.flags & (ApplicationInfo.FLAG_SYSTEM
                | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0);
    }

    static boolean isHiddenSystemApp(PackageManager pm, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (isColorOsPackage(pkg)) return true;
        try {
            return isHiddenSystemApp(pm.getApplicationInfo(pkg, 0));
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static boolean isColorOsPackage(String pkg) {
        return pkg != null && pkg.startsWith("com.coloros.");
    }
}
