/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.permissioncontroller.permission.ui.auto;

import static com.android.permissioncontroller.Constants.EXTRA_SESSION_ID;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Switch;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SwitchPreference;

import com.android.car.ui.AlertDialogBuilder;
import com.android.permissioncontroller.R;
import com.android.permissioncontroller.auto.AutoSettingsFrameFragment;
import com.android.permissioncontroller.permission.model.AppPermissionGroup;
import com.android.permissioncontroller.permission.model.Permission;
import com.android.permissioncontroller.permission.utils.ArrayUtils;
import com.android.permissioncontroller.permission.utils.PermissionMapping;
import com.android.permissioncontroller.permission.utils.Utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Screen which shows all permissions for a particular app. */
public class AutoAllAppPermissionsFragment extends AutoSettingsFrameFragment {

    private static final String LOG_TAG = "AllAppPermsFrag";
    private static final String KEY_OTHER = "other_perms";

    private List<AppPermissionGroup> mGroups;

    /** Creates an {@link AutoAllAppPermissionsFragment} with no filter. */
    public static AutoAllAppPermissionsFragment newInstance(@NonNull String packageName,
            @NonNull UserHandle userHandle, long sessionId) {
        return newInstance(packageName, /* filterGroup= */ null, userHandle, sessionId);
    }

    /** Creates an {@link AutoAllAppPermissionsFragment} with a specific filter group. */
    public static AutoAllAppPermissionsFragment newInstance(@NonNull String packageName,
            @NonNull String filterGroup, @NonNull UserHandle userHandle, long sessionId) {
        AutoAllAppPermissionsFragment instance = new AutoAllAppPermissionsFragment();
        Bundle arguments = new Bundle();
        arguments.putString(Intent.EXTRA_PACKAGE_NAME, packageName);
        arguments.putString(Intent.EXTRA_PERMISSION_GROUP_NAME, filterGroup);
        arguments.putParcelable(Intent.EXTRA_USER, userHandle);
        arguments.putLong(EXTRA_SESSION_ID, sessionId);
        instance.setArguments(arguments);
        return instance;
    }

    @Override
    public void onCreatePreferences(Bundle bundle, String s) {
        setPreferenceScreen(getPreferenceManager().createPreferenceScreen(getContext()));
    }

    @Override
    public void onStart() {
        super.onStart();

        // If we target a group make this look like app permissions.
        if (getArguments().getString(Intent.EXTRA_PERMISSION_GROUP_NAME) == null) {
            setHeaderLabel(getContext().getString(R.string.all_permissions));
        } else {
            setHeaderLabel(getContext().getString(R.string.app_permissions));
        }

        updateUi();
    }

    @Override
    public void onStop() {
        super.onStop();
        getPreferenceScreen().removeAll();
    }

    private void updateUi() {
        PreferenceGroup otherGroup = new PreferenceCategory(getContext());
        otherGroup.setKey(KEY_OTHER);
        otherGroup.setTitle(R.string.other_permissions);
        getPreferenceScreen().addPreference(otherGroup);
        ArrayList<Preference> prefs = new ArrayList<>(); // Used for sorting.
        prefs.add(otherGroup);
        String pkg = getArguments().getString(Intent.EXTRA_PACKAGE_NAME);
        String filterGroup = getArguments().getString(Intent.EXTRA_PERMISSION_GROUP_NAME);
        UserHandle userHandle = getArguments().getParcelable(Intent.EXTRA_USER);
        otherGroup.removeAll();
        PackageManager pm = getContext().getPackageManager();

        PackageInfo info = AutoPermissionsUtils.getPackageInfo(requireActivity(), pkg, userHandle);
        if (info == null) {
            return;
        }

        ApplicationInfo appInfo = info.applicationInfo;
        Preference header = AutoPermissionsUtils.createHeaderPreference(getContext(), appInfo);
        header.setOrder(0);
        getPreferenceScreen().addPreference(header);

        if (info.requestedPermissions != null) {
            for (int i = 0; i < info.requestedPermissions.length; i++) {
                PermissionInfo perm;
                try {
                    perm = pm.getPermissionInfo(info.requestedPermissions[i], /* flags= */ 0);
                } catch (PackageManager.NameNotFoundException e) {
                    Log.e(LOG_TAG,
                            "Can't get permission info for " + info.requestedPermissions[i], e);
                    continue;
                }

                if ((perm.flags & PermissionInfo.FLAG_INSTALLED) == 0
                        || (perm.flags & PermissionInfo.FLAG_REMOVED) != 0) {
                    continue;
                }

                if (appInfo.isInstantApp()
                        && (perm.protectionLevel & PermissionInfo.PROTECTION_FLAG_INSTANT)
                        == 0) {
                    continue;
                }
                if (appInfo.targetSdkVersion < Build.VERSION_CODES.M
                        && (perm.protectionLevel & PermissionInfo.PROTECTION_FLAG_RUNTIME_ONLY)
                        != 0) {
                    continue;
                }

                if ((perm.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                        == PermissionInfo.PROTECTION_DANGEROUS) {
                    PackageItemInfo group =
                            getGroup(PermissionMapping.getGroupOfPermission(perm), pm);
                    if (group == null) {
                        group = perm;
                    }
                    // If we show a targeted group, then ignore everything else.
                    if (filterGroup != null && !group.name.equals(filterGroup)) {
                        continue;
                    }
                    PreferenceGroup pref = findOrCreate(group, pm, prefs);
                    pref.addPreference(getPreference(info, perm, group, pm));
                } else if (filterGroup == null) {
                    if ((perm.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                            == PermissionInfo.PROTECTION_NORMAL) {
                        PermissionGroupInfo group = getGroup(perm.group, pm);
                        otherGroup.addPreference(getPreference(info,
                                perm, group, pm));
                    }
                }

                // If we show a targeted group, then don't show 'other' permissions.
                if (filterGroup != null) {
                    getPreferenceScreen().removePreference(otherGroup);
                }
            }
        }

        // Sort an ArrayList of the groups and then set the order from the sorting.
        Collections.sort(prefs, (lhs, rhs) -> {
            String lKey = lhs.getKey();
            String rKey = rhs.getKey();
            if (lKey.equals(KEY_OTHER)) {
                return 1;
            } else if (rKey.equals(KEY_OTHER)) {
                return -1;
            } else if (PermissionMapping.isPlatformPermissionGroup(lKey)
                    != PermissionMapping.isPlatformPermissionGroup(rKey)) {
                return PermissionMapping.isPlatformPermissionGroup(lKey) ? -1 : 1;
            }
            return lhs.getTitle().toString().compareTo(rhs.getTitle().toString());
        });
        for (int i = 0; i < prefs.size(); i++) {
            prefs.get(i).setOrder(i + 1);
        }
    }

    private PermissionGroupInfo getGroup(String group, PackageManager pm) {
        try {
            return pm.getPermissionGroupInfo(group, /* flags= */ 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private PreferenceGroup findOrCreate(PackageItemInfo group, PackageManager pm,
            ArrayList<Preference> prefs) {
        PreferenceGroup pref = findPreference(group.name);
        if (pref == null) {
            pref = new PreferenceCategory(getPreferenceManager().getContext());
            pref.setKey(group.name);
            pref.setTitle(group.loadLabel(pm));
            prefs.add(pref);
            getPreferenceScreen().addPreference(pref);
        }
        return pref;
    }

    private Preference getPreference(PackageInfo packageInfo, PermissionInfo perm,
            PackageItemInfo group, PackageManager pm) {
        final Preference pref;
        Context context = getPreferenceManager().getContext();

        // We allow individual permission control for some permissions if review enabled
        final boolean mutable = Utils.isPermissionIndividuallyControlled(getContext(), perm.name);
        if (mutable) {
            pref = new MyMultiTargetSwitchPreference(context, perm.name,
                    getPermissionForegroundGroup(packageInfo, perm.name));
        } else {
            pref = new Preference(context);
        }

        Drawable icon;
        if (perm.icon != 0) {
            icon = perm.loadUnbadgedIcon(pm);
        } else if (group != null && group.icon != 0) {
            icon = group.loadUnbadgedIcon(pm);
        } else {
            icon = context.getDrawable(
                    com.android.permissioncontroller.R.drawable.ic_perm_device_info);
        }
        pref.setIcon(Utils.applyTint(context, icon, android.R.attr.colorControlNormal));
        pref.setTitle(
                perm.loadSafeLabel(pm, /* ellipsizeDip= */ 20000, TextUtils.SAFE_STRING_FLAG_TRIM));
        pref.setSingleLineTitle(false);
        final CharSequence desc = perm.loadDescription(pm);

        pref.setOnPreferenceClickListener((Preference preference) -> {
            new AlertDialogBuilder(getContext())
                    .setMessage(desc)
                    .setPositiveButton(android.R.string.ok, /* listener= */ null)
                    .show();
            return mutable;
        });

        return pref;
    }

    /**
     * Return the (foreground-) {@link AppPermissionGroup group} a permission belongs to.
     *
     * <p>For foreground or non background-foreground permissions this returns the group
     * {@link AppPermissionGroup} the permission is in. For background permisisons this returns
     * the group the matching foreground
     *
     * @param packageInfo Package information about the app
     * @param permission  The permission that belongs to a group
     * @return the group the permissions belongs to
     */
    private AppPermissionGroup getPermissionForegroundGroup(PackageInfo packageInfo,
            String permission) {
        AppPermissionGroup appPermissionGroup = null;
        if (mGroups != null) {
            final int groupCount = mGroups.size();
            for (int i = 0; i < groupCount; i++) {
                AppPermissionGroup currentPermissionGroup = mGroups.get(i);
                if (currentPermissionGroup.hasPermission(permission)) {
                    appPermissionGroup = currentPermissionGroup;
                    break;
                }
                if (currentPermissionGroup.getBackgroundPermissions() != null
                        && currentPermissionGroup.getBackgroundPermissions().hasPermission(
                        permission)) {
                    appPermissionGroup = currentPermissionGroup.getBackgroundPermissions();
                    break;
                }
            }
        }
        if (appPermissionGroup == null) {
            appPermissionGroup = AppPermissionGroup.create(
                    getContext(), packageInfo, permission, /* delayChanges= */ false);
            if (mGroups == null) {
                mGroups = new ArrayList<>();
            }
            mGroups.add(appPermissionGroup);
        }
        return appPermissionGroup;
    }


    private static final class MyMultiTargetSwitchPreference extends SwitchPreference {
        private View.OnClickListener mSwitchOnClickLister;

        MyMultiTargetSwitchPreference(Context context, String permission,
                AppPermissionGroup appPermissionGroup) {
            super(context);

            setChecked(appPermissionGroup.areRuntimePermissionsGranted(
                    new String[]{permission}));

            setSwitchOnClickListener(v -> {
                Switch switchView = (Switch) v;
                if (switchView.isChecked()) {
                    appPermissionGroup.grantRuntimePermissions(false, false,
                            new String[]{permission});
                    // We are granting a permission from a group but since this is an
                    // individual permission control other permissions in the group may
                    // be revoked, hence we need to mark them user fixed to prevent the
                    // app from requesting a non-granted permission and it being granted
                    // because another permission in the group is granted. This applies
                    // only to apps that support runtime permissions.
                    if (appPermissionGroup.doesSupportRuntimePermissions()) {
                        int grantedCount = 0;
                        String[] revokedPermissionsToFix = null;
                        final int permissionCount = appPermissionGroup.getPermissions().size();
                        for (int i = 0; i < permissionCount; i++) {
                            Permission current = appPermissionGroup.getPermissions().get(i);
                            if (!current.isGrantedIncludingAppOp()) {
                                if (!current.isUserFixed()) {
                                    revokedPermissionsToFix = ArrayUtils.appendString(
                                            revokedPermissionsToFix, current.getName());
                                }
                            } else {
                                grantedCount++;
                            }
                        }
                        if (revokedPermissionsToFix != null) {
                            // If some permissions were not granted then they should be fixed.
                            appPermissionGroup.revokeRuntimePermissions(/* fixedByTheUser= */ true,
                                    revokedPermissionsToFix);
                        } else if (appPermissionGroup.getPermissions().size() == grantedCount) {
                            // If all permissions are granted then they should not be fixed.
                            appPermissionGroup.grantRuntimePermissions(true,
                                    /* fixedByTheUser= */ false);
                        }
                    }
                } else {
                    appPermissionGroup.revokeRuntimePermissions(/* fixedByTheUser= */ true,
                            new String[]{permission});
                    // If we just revoked the last permission we need to clear
                    // the user fixed state as now the app should be able to
                    // request them at runtime if supported.
                    if (appPermissionGroup.doesSupportRuntimePermissions()
                            && !appPermissionGroup.areRuntimePermissionsGranted()) {
                        appPermissionGroup.revokeRuntimePermissions(/* fixedByTheUser= */ false);
                    }
                }
            });
        }

        @Override
        public void setChecked(boolean checked) {
            // If double target behavior is enabled do nothing
            if (mSwitchOnClickLister == null) {
                super.setChecked(checked);
            }
        }

        void setSwitchOnClickListener(View.OnClickListener listener) {
            mSwitchOnClickLister = listener;
        }

        @Override
        public void onBindViewHolder(PreferenceViewHolder holder) {
            super.onBindViewHolder(holder);
            Switch switchView = holder.itemView.findViewById(android.R.id.switch_widget);
            if (switchView != null) {
                switchView.setOnClickListener(mSwitchOnClickLister);
            }
        }
    }
}
