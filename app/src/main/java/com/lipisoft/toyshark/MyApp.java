package com.lipisoft.toyshark;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;


import androidx.collection.ArraySet;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

public class MyApp extends Application {
    private static MyApp instance;
    private List<AppDescriptor> appsList;

    public MyApp() {
        instance = this;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        int uid;
        try {
            ApplicationInfo info = getApplicationContext().getPackageManager().getApplicationInfo(
                    getApplicationContext().getPackageName(), 0);
            uid = info.uid;
        } catch (PackageManager.NameNotFoundException e) {
            uid = -1;
        }
        Log.e("TAGDEBUG", "UID = " + uid);
        appsList = asyncLoadAppsInfo();
    }

    public static MyApp getInstance() {
        return instance;
    }


    public AppDescriptor getAppByUid(int uid) {
        if(appsList != null && appsList.size() > 0) {
            for (AppDescriptor app: appsList) {
                if(app.getUid() == uid) {
                    return app;
                }
            }
        }
        return null;
    }

    public List<AppDescriptor> getAppsList() {
        return appsList;
    }

    private ArrayList<AppDescriptor> asyncLoadAppsInfo() {
        Context mContext = getApplicationContext();
        final PackageManager pm = mContext.getPackageManager();
        ArrayList<AppDescriptor> apps = new ArrayList<>();
        ArraySet<Integer> uids = new ArraySet<>();

        Log.d("TAGDEBUG", "Loading APPs...");
        @SuppressLint("QueryPermissionsNeeded") List<PackageInfo> packs = pm.getInstalledPackages(0);
        String app_package = mContext.getApplicationContext().getPackageName();

        Log.d("TAGDEBUG", "num apps (system+user): " + packs.size());
        long tstart = getNow();

        // NOTE: a single uid can correspond to multiple packages, only take the first package found.
        // The VPNService in android works with UID, so this choice is not restrictive.
        for (int i = 0; i < packs.size(); i++) {
            PackageInfo p = packs.get(i);
            String package_name = p.applicationInfo.packageName;

            if(!uids.contains(p.applicationInfo.uid) && !package_name.equals(app_package)) {
                int uid = p.applicationInfo.uid;
                AppDescriptor app = new AppDescriptor(pm, p);

                apps.add(app);
                uids.add(uid);

                //Log.d(TAG, appName + " - " + package_name + " [" + uid + "]" + (is_system ? " - SYS" : " - USR"));
            }
        }

        Collections.sort(apps);

        Log.d("TAGDEBUG", packs.size() + " apps loaded in " + (getNow() - tstart) +" seconds");
        return apps;
    }

    public long getNow() {
        Calendar calendar = Calendar.getInstance();
        return(calendar.getTimeInMillis() / 1000);
    }
}
