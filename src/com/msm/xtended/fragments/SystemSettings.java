package com.msm.xtended.fragments;

import com.android.internal.logging.nano.MetricsProto;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.SELinux;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragment;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.preference.Preference.OnPreferenceChangeListener;
import android.provider.Settings;
import com.android.settings.R;
import android.util.Log;

import com.msm.xtended.preferences.PackageListAdapter;
import com.msm.xtended.preferences.PackageListAdapter.PackageItem;
import com.msm.xtended.utils.SuTask;
import com.msm.xtended.utils.SuShell;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.android.settings.SettingsPreferenceFragment;

public class SystemSettings extends SettingsPreferenceFragment implements
        OnPreferenceChangeListener, Preference.OnPreferenceClickListener {

    private static final int DIALOG_BLOCKED_APPS = 1;
    private static final String SENSOR_BLOCK = "sensor_block";
    private static final String TAG = "SystemSettings";
    private static final String SELINUX_CATEGORY = "selinux";
    private static final String PREF_SELINUX_MODE = "selinux_mode";
    private static final String PREF_SELINUX_PERSISTENCE = "selinux_persistence";

    private PackageListAdapter mPackageAdapter;
    private PackageManager mPackageManager;
    private PreferenceGroup mSensorBlockPrefList;
    private Preference mAddSensorBlockPref;
    private SwitchPreference mSelinuxMode;
    private SwitchPreference mSelinuxPersistence;

    private String mBlockedPackageList;
    private Map<String, Package> mBlockedPackages;
    private Context mContext;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        addPreferencesFromResource(R.xml.x_settings_system);

        mFooterPreferenceMixin.createFooterPreference().setTitle(R.string.add_sensor_block_package_summary);

        final PreferenceScreen prefScreen = getPreferenceScreen();

        mPackageManager = getPackageManager();
        mPackageAdapter = new PackageListAdapter(getActivity());

        boolean enableSmartPixels = getContext().getResources().
                getBoolean(com.android.internal.R.bool.config_enableSmartPixels);
        Preference SmartPixels = findPreference("smart_pixels");
        if (!enableSmartPixels){
            getPreferenceScreen().removePreference(SmartPixels);
        }

        mSensorBlockPrefList = (PreferenceGroup) findPreference("sensor_block_applications");
        mSensorBlockPrefList.setOrderingAsAdded(false);

        mBlockedPackages = new HashMap<String, Package>();

        mAddSensorBlockPref = findPreference("add_sensor_block_packages");

        mAddSensorBlockPref.setOnPreferenceClickListener(this);

        mContext = getActivity().getApplicationContext();
	
	// SELinux
        Preference selinuxCategory = findPreference(SELINUX_CATEGORY);
        mSelinuxMode = (SwitchPreference) findPreference(PREF_SELINUX_MODE);
        mSelinuxMode.setChecked(SELinux.isSELinuxEnforced());
        mSelinuxMode.setOnPreferenceChangeListener(this);

        mSelinuxPersistence =
        (SwitchPreference) findPreference(PREF_SELINUX_PERSISTENCE);
        mSelinuxPersistence.setOnPreferenceChangeListener(this);
        mSelinuxPersistence.setChecked(getContext()
          .getSharedPreferences("selinux_pref", Context.MODE_PRIVATE)
          .contains(PREF_SELINUX_MODE));
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshCustomApplicationPrefs();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object objValue) {
      if (preference == mSelinuxMode) {
        boolean enabled = (Boolean) objValue;
        new SwitchSelinuxTask(getActivity()).execute(enabled);
        setSelinuxEnabled(enabled, mSelinuxPersistence.isChecked());
        return true;
      } else if (preference == mSelinuxPersistence) {
        setSelinuxEnabled(mSelinuxMode.isChecked(), (Boolean) objValue);
        return true;
      }
        return false;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.XTENSIONS;
    }

    @Override
    public int getDialogMetricsCategory(int dialogId) {
        if (dialogId == DIALOG_BLOCKED_APPS) {
            return MetricsProto.MetricsEvent.XTENSIONS;
        }
        return 0;
    }

    /**
     * Utility classes and supporting methods
     */
    @Override
    public Dialog onCreateDialog(int id) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        final Dialog dialog;
        final ListView list = new ListView(getActivity());
        list.setAdapter(mPackageAdapter);
        list.setDivider(null);

        builder.setTitle(R.string.profile_choose_app);
        builder.setView(list);
        dialog = builder.create();

        switch (id) {
            case DIALOG_BLOCKED_APPS:
                list.setOnItemClickListener(new OnItemClickListener() {
                    @Override
                    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                        // Add empty application definition, the user will be able to edit it later
                        PackageItem info = (PackageItem) parent.getItemAtPosition(position);
                        addCustomApplicationPref(info.packageName, mBlockedPackages);
                        dialog.cancel();
                    }
                });
        }
        return dialog;
    }

    public static void reset(Context mContext) {
        ContentResolver resolver = mContext.getContentResolver();
        Settings.System.putIntForUser(resolver,
                Settings.System.SENSOR_BLOCK, 1, UserHandle.USER_CURRENT);
        Settings.System.putString(resolver,
                Settings.System.SENSOR_BLOCKED_APP, null);
    }

    /**
     * Application class
     */
    private static class Package {
        public String name;
        /**
         * Stores all the application values in one call
         * @param name
         */
        public Package(String name) {
            this.name = name;
        }

        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append(name);
            return builder.toString();
        }

        public static Package fromString(String value) {
            if (TextUtils.isEmpty(value)) {
                return null;
            }

            try {
                Package item = new Package(value);
                return item;
            } catch (NumberFormatException e) {
                return null;
            }
        }

    };

    private void refreshCustomApplicationPrefs() {
        if (!parsePackageList()) {
            return;
        }

        // Add the Application Preferences
        if (mSensorBlockPrefList != null) {
            mSensorBlockPrefList.removeAll();

            for (Package pkg : mBlockedPackages.values()) {
                try {
                    Preference pref = createPreferenceFromInfo(pkg);
                    mSensorBlockPrefList.addPreference(pref);
                } catch (PackageManager.NameNotFoundException e) {
                    // Do nothing
                }
            }

            // Keep these at the top
            mAddSensorBlockPref.setOrder(0);
            // Add 'add' options
            mSensorBlockPrefList.addPreference(mAddSensorBlockPref);
        }
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        if (preference == mAddSensorBlockPref) {
            showDialog(DIALOG_BLOCKED_APPS);
        } else {
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.dialog_delete_title)
                    .setMessage(R.string.dialog_delete_message)
                    .setIconAttribute(android.R.attr.alertDialogIcon)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (preference == mSensorBlockPrefList.findPreference(preference.getKey())) {
                                removeApplicationPref(preference.getKey(), mBlockedPackages);
                            }
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null);

            builder.show();
        }
        return true;
    }

    private void addCustomApplicationPref(String packageName, Map<String,Package> map) {
        Package pkg = map.get(packageName);
        if (pkg == null) {
            pkg = new Package(packageName);
            map.put(packageName, pkg);
            savePackageList(false, map);
            refreshCustomApplicationPrefs();
        }
    }

    private Preference createPreferenceFromInfo(Package pkg)
            throws PackageManager.NameNotFoundException {
        PackageInfo info = mPackageManager.getPackageInfo(pkg.name,
                PackageManager.GET_META_DATA);
        Preference pref =
                new Preference(getActivity());

        pref.setKey(pkg.name);
        pref.setTitle(info.applicationInfo.loadLabel(mPackageManager));
        pref.setIcon(info.applicationInfo.loadIcon(mPackageManager));
        pref.setPersistent(false);
        pref.setOnPreferenceClickListener(this);
        return pref;
    }

    private void removeApplicationPref(String packageName, Map<String,Package> map) {
        if (map.remove(packageName) != null) {
            savePackageList(false, map);
            refreshCustomApplicationPrefs();
        }
    }

    private boolean parsePackageList() {
        boolean parsed = false;

        String sensorBlockString = Settings.System.getString(getContentResolver(),
                Settings.System.SENSOR_BLOCKED_APP);

        if (sensorBlockString != null &&
                !TextUtils.equals(mBlockedPackageList, sensorBlockString)) {
            mBlockedPackageList = sensorBlockString;
            mBlockedPackages.clear();
            parseAndAddToMap(sensorBlockString, mBlockedPackages);
            parsed = true;
        }

        return parsed;
    }

    private void parseAndAddToMap(String baseString, Map<String,Package> map) {
        if (baseString == null) {
            return;
        }

        final String[] array = TextUtils.split(baseString, "\\|");
        for (String item : array) {
            if (TextUtils.isEmpty(item)) {
                continue;
            }
            Package pkg = Package.fromString(item);
            map.put(pkg.name, pkg);
        }
    }

    private void savePackageList(boolean preferencesUpdated, Map<String,Package> map) {
        String setting = map == mBlockedPackages ? Settings.System.SENSOR_BLOCKED_APP : Settings.System.SENSOR_BLOCKED_APP_DUMMY;

        List<String> settings = new ArrayList<String>();
        for (Package app : map.values()) {
            settings.add(app.toString());
        }
        final String value = TextUtils.join("|", settings);
        if (preferencesUpdated) {
            if (TextUtils.equals(setting, Settings.System.SENSOR_BLOCKED_APP)) {
                mBlockedPackageList = value;
            }
        }
        Settings.System.putString(getContentResolver(),
                setting, value);
    }

            private void setSelinuxEnabled(boolean status, boolean persistent) {
      SharedPreferences.Editor editor = getContext()
          .getSharedPreferences("selinux_pref", Context.MODE_PRIVATE).edit();
      if (persistent) {
        editor.putBoolean(PREF_SELINUX_MODE, status);
      } else {
        editor.remove(PREF_SELINUX_MODE);
      }
      editor.apply();
      mSelinuxMode.setChecked(status);
    }

    private class SwitchSelinuxTask extends SuTask<Boolean> {
      public SwitchSelinuxTask(Context context) {
        super(context);
      }
      @Override
      protected void sudoInBackground(Boolean... params) throws SuShell.SuDeniedException {
        if (params.length != 1) {
          Log.e(TAG, "SwitchSelinuxTask: invalid params count");
          return;
        }
        if (params[0]) {
          SuShell.runWithSuCheck("setenforce 1");
        } else {
          SuShell.runWithSuCheck("setenforce 0");
        }
      }

      @Override
      protected void onPostExecute(Boolean result) {
        super.onPostExecute(result);
        if (!result) {
          // Did not work, so restore actual value
          setSelinuxEnabled(SELinux.isSELinuxEnforced(), mSelinuxPersistence.isChecked());
        }
      }
    }
}
