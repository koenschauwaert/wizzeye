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

import android.Manifest;
import android.app.Fragment;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v7.app.ActionBar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;

import com.iristick.smartglass.core.Headset;
import com.iristick.smartglass.support.app.IristickApp;

import app.wizzeye.app.fragments.BaseFragment;
import app.wizzeye.app.fragments.PermissionsFragment;
import app.wizzeye.app.service.Call;
import app.wizzeye.app.service.CallService;
import app.wizzeye.app.service.CallState;

public class MainActivity extends BaseActivity implements ServiceConnection, CallService.Listener {

    public static final String[] PERMISSIONS = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    };

    private static final String TAG = "MainActivity";

    private static final String STATE_LAST_FRAGMENT_TYPE = "lastFragmentType";
    private static final String STATE_LAST_FRAGMENT_STATE = "lastFragmentState";

    private String mLastFragmentType;
    private Fragment.SavedState mLastFragmentState;
    private boolean mBound;
    private CallService mCallService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            savedInstanceState.remove("android:fragments"); // do not restore fragments
            mLastFragmentType = savedInstanceState.getString(STATE_LAST_FRAGMENT_TYPE);
            mLastFragmentState = savedInstanceState.getParcelable(STATE_LAST_FRAGMENT_STATE);
        }
        super.onCreate(savedInstanceState);
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    private boolean hasAllPermissions() {
        for (String p : PERMISSIONS) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED)
                return false;
        }
        return true;
    }

    private void handleIntent(Intent intent) {
        if (intent != null &&
                Intent.ACTION_VIEW.equals(intent.getAction()) &&
                hasAllPermissions()) {
            startService(new Intent(this, CallService.class)
                .setData(intent.getData()));
        }
    }

    private void showFragment(@NonNull BaseFragment fragment) {
        if (mLastFragmentType != null && mLastFragmentType.equals(fragment.getClass().getName())) {
            fragment.setInitialSavedState(mLastFragmentState);
        }
        mLastFragmentType = null;
        mLastFragmentState = null;
        getFragmentManager().beginTransaction()
            .replace(android.R.id.content, fragment)
            .commit();
    }

    @Override
    protected void onStart() {
        super.onStart();
        checkPermissions();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        Fragment f = getFragmentManager().findFragmentById(android.R.id.content);
        if (f != null) {
            outState.putString(STATE_LAST_FRAGMENT_TYPE, f.getClass().getName());
            outState.putParcelable(STATE_LAST_FRAGMENT_STATE, getFragmentManager().saveFragmentInstanceState(f));
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onStop() {
        if (mCallService != null) {
            mCallService.unregisterListener(this);
            mCallService = null;
        }
        if (mBound) {
            unbindService(this);
            mBound = false;
        }
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        BaseFragment f = (BaseFragment) getFragmentManager().findFragmentById(android.R.id.content);
        if (f == null || !f.onBackPressed())
            super.onBackPressed();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case R.id.settings:
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        case R.id.logs:
            startActivity(new Intent(this, LogsActivity.class));
            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }

    /**
     * Check that we got all permissions we need.
     * If we have all permissions, proceed with startup, else show {@link PermissionsFragment}.
     */
    public void checkPermissions() {
        if (!hasAllPermissions()) {
            // Show permissions fragment
            showFragment(new PermissionsFragment());
        } else if (!mBound) {
            // All permissions have been granted, proceed.
            bindService(new Intent(this, CallService.class), this, BIND_AUTO_CREATE);
            mBound = true;
        }
    }

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
        mCallService = ((CallService.LocalBinder) iBinder).getService();
        mCallService.registerListener(this);
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
        mCallService = null;
    }

    public CallService getCallService() {
        return mCallService;
    }

    @Override
    public void onCallStateChanged(CallState newState) {
        try {
            showFragment(newState.fragmentClass.newInstance());
        } catch (InstantiationException | IllegalAccessException e) {
            Log.e(TAG, "Could not create fragment for state " + newState, e);
        }
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            Call call = getCallService().getCall();
            if (call == null)
                actionBar.setTitle(R.string.app_name);
            else
                actionBar.setTitle(call.getRoomName());
        }
        getWindow().setFlags(newState == CallState.IDLE || newState == CallState.ERROR ? 0 :
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (IristickApp.getInteractionMode() == Headset.INTERACTION_MODE_HUD) {
            switch (newState) {
            case CALL_IN_PROGRESS:
                getSupportActionBar().hide();
                break;
            default:
                getSupportActionBar().show();
            }
        }
    }

}
