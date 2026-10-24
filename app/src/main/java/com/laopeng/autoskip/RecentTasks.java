package com.laopeng.autoskip;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

/** 使用 Android 的任务接口控制最近任务卡片，不改变后台服务状态。 */
final class RecentTasks {
    private RecentTasks() {}

    static boolean apply(Context context, boolean hidden) {
        try {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            if (manager == null) return false;
            boolean success = true;
            for (ActivityManager.AppTask task : manager.getAppTasks()) {
                try {
                    task.setExcludeFromRecents(hidden);
                } catch (RuntimeException e) {
                    Log.w("AutoSkip", "Unable to update recent task visibility", e);
                    success = false;
                }
            }
            return success;
        } catch (RuntimeException e) {
            Log.w("AutoSkip", "Unable to read recent tasks", e);
            return false;
        }
    }
}
