package com.lipisoft.toyshark;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

import java.io.Serializable;

public class AppDescriptor implements Comparable<AppDescriptor>, Serializable {
    private final String mName;
    private final String mPackageName;
    private final int mUid;
    private final boolean mIsSystem;
    private Drawable mIcon;
    private final DrawableLoader mIconLoader;
    private String mDescription;

    // NULL for virtual apps
    PackageManager mPm;
    PackageInfo mPackageInfo;

    public AppDescriptor(String name, DrawableLoader icon_loader, String package_name, int uid, boolean is_system) {
        this.mName = name;
        this.mIcon = null;
        this.mIconLoader = icon_loader;
        this.mPackageName = package_name;
        this.mUid = uid;
        this.mIsSystem = is_system;
        this.mDescription = "";
    }

    public AppDescriptor(PackageManager pm, PackageInfo pkgInfo) {
        this(pkgInfo.applicationInfo.loadLabel(pm).toString(), null,
                pkgInfo.applicationInfo.packageName, pkgInfo.applicationInfo.uid,
                (pkgInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);

        mPm = pm;
        mPackageInfo = pkgInfo;
    }

    public AppDescriptor setDescription(String dsc) {
        mDescription = dsc;
        return this;
    }

    public String getDescription() {
        return mDescription;
    }

    public String getName() {
        return mName;
    }

    public @Nullable
    Drawable getIcon() {
        if(mIcon != null)
            return mIcon;

        if(mIconLoader != null) {
            mIcon = mIconLoader.getDrawable();
            return mIcon;
        }

        if((mPackageInfo == null) || (mPm == null))
            return null;

        // NOTE: this call is expensive
        mIcon = mPackageInfo.applicationInfo.loadIcon(mPm);
        //Log.d("Icon size", mIcon.getIntrinsicWidth() + "x" + mIcon.getIntrinsicHeight());

        return mIcon;
    }

    public String getPackageName() {
        return mPackageName;
    }

    public int getUid() {
        return mUid;
    }

    public boolean isSystem() { return mIsSystem; }

    // the app does not have a package name (e.g. uid 0 is android system)
    public boolean isVirtual() { return (mPackageInfo == null); }

    public @Nullable PackageInfo getPackageInfo() { return mPackageInfo; }

    @Override
    public int compareTo(AppDescriptor o) {
        int rv = getName().toLowerCase().compareTo(o.getName().toLowerCase());

        if(rv == 0)
            rv = getPackageName().compareTo(o.getPackageName());

        return rv;
    }

    @Override
    public String toString() {
        return "AppDescriptor{" +
                "mName='" + mName + '\'' +
                ", mPackageName='" + mPackageName + '\'' +
                ", mUid=" + mUid +
                ", mIsSystem=" + mIsSystem +
                ", mIcon=" + mIcon +
                ", mIconLoader=" + mIconLoader +
                ", mDescription='" + mDescription + '\'' +
                ", mPm=" + mPm +
                ", mPackageInfo=" + mPackageInfo +
                '}';
    }
}