/* Copyright (c) 2018 The Wizzeye Authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package app.wizzeye.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.support.v7.app.ActionBar;
import android.view.MenuItem;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends BaseActivity {

    public static final String KEY_VIDEO_QUALITY = "video_quality";
    public static final String KEY_SERVER = "server";
    public static final String KEY_TURN_HOSTNAME = "turn_hostname";
    public static final String KEY_TURN_USERNAME = "turn_username";
    public static final String KEY_TURN_PASSWORD = "turn_password";
    public static final String KEY_ABOUT_VERSION = "about_version";
    public static final String KEY_ABOUT_LEGAL = "about_legal";

    public static final String KEY_LAST_ROOM = "last_room";
    public static final String KEY_LASER_MODE = "laser_mode";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getFragmentManager().beginTransaction()
            .replace(android.R.id.content, new SettingsFragment())
            .commit();

        ActionBar actionBar = getSupportActionBar();
        actionBar.setTitle(R.string.settings_title);
        actionBar.setDisplayHomeAsUpEnabled(true);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case android.R.id.home:
            finish();
            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }

    public static class SettingsFragment extends PreferenceFragment
        implements SharedPreferences.OnSharedPreferenceChangeListener {

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.preferences);
            findPreference(KEY_ABOUT_VERSION).setSummary(getString(R.string.pref_about_version_summary, BuildConfig.VERSION_NAME));
            findPreference(KEY_ABOUT_LEGAL).setOnPreferenceClickListener(mLegalClickListener);
        }

        @Override
        public void onResume() {
            super.onResume();
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
            prefs.registerOnSharedPreferenceChangeListener(this);
            onSharedPreferenceChanged(prefs, KEY_VIDEO_QUALITY);
            onSharedPreferenceChanged(prefs, KEY_SERVER);
            onSharedPreferenceChanged(prefs, KEY_TURN_HOSTNAME);
            onSharedPreferenceChanged(prefs, KEY_TURN_USERNAME);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            Preference pref = findPreference(key);
            switch (key) {
            case KEY_VIDEO_QUALITY:
                pref.setSummary(((ListPreference) pref).getEntry());
                break;
            case KEY_SERVER:
            case KEY_TURN_HOSTNAME:
            case KEY_TURN_USERNAME:
                pref.setSummary(prefs.getString(key, ""));
                break;
            }
        }

        /**
         * Open preference intent, but remove Wizzeye from the list of apps to choose from.
         */
        private final Preference.OnPreferenceClickListener mLegalClickListener = pref -> {
            Uri uri = Uri.withAppendedPath(
                Uri.parse(PreferenceManager.getDefaultSharedPreferences(getContext())
                    .getString(KEY_SERVER, getString(R.string.default_server))),
                "s/legal/");
            Intent baseIntent = new Intent(Intent.ACTION_VIEW, uri);
            PackageManager pm = getActivity().getPackageManager();
            List<ResolveInfo> activities = pm.queryIntentActivities(baseIntent, 0);
            List<Intent> intents = new ArrayList<>();
            for (ResolveInfo info : activities) {
                if (!BuildConfig.APPLICATION_ID.equals(info.activityInfo.packageName)) {
                    Intent intent = new Intent(baseIntent);
                    intent.setPackage(info.activityInfo.packageName);
                    intents.add(intent);
                }
            }
            switch (intents.size()) {
            case 0:
                break; // no browser to handle this intent
            case 1:
                startActivity(intents.get(0));
                break;
            default:
                Intent chooser = Intent.createChooser(intents.remove(0), getString(R.string.intent_chooser_open_with));
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.toArray(new Parcelable[intents.size()]));
                startActivity(chooser);
            }
            return true;
        };
    }

}
