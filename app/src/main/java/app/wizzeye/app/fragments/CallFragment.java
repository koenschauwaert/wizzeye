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
package app.wizzeye.app.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.NavigationView;
import android.support.v4.widget.DrawerLayout;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Toast;

import com.iristick.smartglass.core.Headset;
import com.iristick.smartglass.support.app.IristickApp;

import org.webrtc.SurfaceViewRenderer;

import app.wizzeye.app.R;
import app.wizzeye.app.service.Call;
import app.wizzeye.app.service.LaserMode;

public class CallFragment extends InRoomFragment {

    private static final String STATE_FOCUS_HINT_SHOWN = "focus_hint_shown";

    private static final int MSG_PARAMETERS_CHANGED = 0;
    private static final int MSG_TURBULENCE = 1;

    private SurfaceViewRenderer mVideo;
    private ImageView mTurbulence;
    private SeekBar mZoom;
    private FloatingActionButton mMore;
    private DrawerLayout mDrawerLayout;
    private NavigationView mOptions;
    private Message mParametersChangedMessage;
    private Message mTurbulenceMessage;

    private boolean mFocusHintShown = false;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_call, container, false);
        mVideo = view.findViewById(R.id.video);
        mVideo.init(mService.getEglBase().getEglBaseContext(), null);
        mVideo.setEnableHardwareScaler(true);
        mVideo.setOnClickListener(v -> refocus());

        mTurbulence = view.findViewById(R.id.turbulence);

        mZoom = view.findViewById(R.id.zoom);
        if (mCall != null)
            mZoom.setMax(mCall.getQuality().maxZoom);
        mZoom.setOnSeekBarChangeListener(mZoomListener);
        mZoom.setOnClickListener(v -> refocus());

        mDrawerLayout = view.findViewById(R.id.drawer_layout);
        mDrawerLayout.addDrawerListener(mDrawerListener);
        mOptions = view.findViewById(R.id.options);
        mOptions.setNavigationItemSelectedListener(mOptionsListener);
        mMore = view.findViewById(R.id.more);
        mMore.setOnClickListener(v -> mDrawerLayout.openDrawer(mOptions));

        Handler handler = new Handler(this::handleMessage);
        mParametersChangedMessage = handler.obtainMessage(MSG_PARAMETERS_CHANGED);
        mTurbulenceMessage = handler.obtainMessage(MSG_TURBULENCE);
        if (mCall != null) {
            mCall.registerMessage(Call.Event.PARAMETERS_CHANGED, mParametersChangedMessage);
            handler.sendEmptyMessage(MSG_PARAMETERS_CHANGED);
            mCall.registerMessage(Call.Event.TURBULENCE, mTurbulenceMessage);
        }

        if (savedInstanceState != null)
            mFocusHintShown = savedInstanceState.getBoolean(STATE_FOCUS_HINT_SHOWN, false);

        return view;
    }

    @Override
    public void onDestroy() {
        if (mCall != null) {
            mCall.unregisterMessage(mParametersChangedMessage);
            mCall.unregisterMessage(mTurbulenceMessage);
        }
        mParametersChangedMessage.recycle();
        mTurbulenceMessage.recycle();
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_FOCUS_HINT_SHOWN, mFocusHintShown);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mCall != null)
            mCall.addVideoSink(mVideo);
    }

    @Override
    public void onStop() {
        if (mCall != null)
            mCall.removeVideoSink(mVideo);
        mVideo.release();
        super.onStop();
    }

    @Override
    public void onResume() {
        super.onResume();
        Headset headset = IristickApp.getHeadset();
        if (headset != null)
            headset.configureVoiceCommands(Headset.VOICE_FLAG_INHIBIT_COMMAND_DISCOVERY |
                                           Headset.VOICE_FLAG_INHIBIT_GO_BACK);
    }

    @Override
    public void onPause() {
        Headset headset = IristickApp.getHeadset();
        if (headset != null)
            headset.configureVoiceCommands(0);
        super.onPause();
    }

    @Override
    public boolean onBackPressed() {
        if (mDrawerLayout.isDrawerOpen(mOptions)) {
            mDrawerLayout.closeDrawers();
            return true;
        }
        return super.onBackPressed();
    }

    private void refocus() {
        if (mCall == null)
            return;
        if (mCall.getZoom() >= mZoom.getMax())
            Toast.makeText(getContext(), R.string.call_toast_focus_forbidden, Toast.LENGTH_SHORT).show();
        else
            mCall.triggerAF();
    }

    private boolean handleMessage(Message msg) {
        if (mCall == null)
            return false;
        switch (msg.what) {
        case MSG_PARAMETERS_CHANGED:
            mZoom.setProgress(mCall.getZoom());
            mOptions.getMenu().findItem(R.id.torch).setChecked(mCall.getTorch());
            mOptions.getMenu().findItem(R.id.laser).setChecked(mCall.getLaser() != LaserMode.OFF);
            mOptions.getMenu().findItem(R.id.laser).setIcon(mCall.getLaser().icon);
            return true;
        case MSG_TURBULENCE:
            mTurbulence.setVisibility(msg.arg1 == 1 ? View.VISIBLE : View.GONE);
            return true;
        }
        return false;
    }

    private final SeekBar.OnSeekBarChangeListener mZoomListener = new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (!fromUser || mCall == null)
                return;
            if (IristickApp.getInteractionMode() == Headset.INTERACTION_MODE_HUD &&
                !mFocusHintShown && progress > 0) {
                Toast.makeText(getContext(), R.string.call_hint_focus, Toast.LENGTH_LONG).show();
                mFocusHintShown = true;
            }
            mCall.setZoom(progress);
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    };

    private final DrawerLayout.DrawerListener mDrawerListener = new DrawerLayout.SimpleDrawerListener() {
        @Override
        public void onDrawerSlide(View drawerView, float slideOffset) {
            float alpha = 1.0f - Math.min(1.0f, slideOffset * 2);
            mZoom.setAlpha(alpha);
            mMore.setAlpha(alpha);
            int visibility = (alpha == 0.0f ? View.GONE : View.VISIBLE);
            mZoom.setVisibility(visibility);
            mMore.setVisibility(visibility);
        }
    };

    private final NavigationView.OnNavigationItemSelectedListener mOptionsListener = item -> {
        if (mCall == null)
            return false;
        switch (item.getItemId()) {
        case R.id.torch:
            boolean newTorch = !mCall.getTorch();
            mCall.setTorch(newTorch);
            break;
        case R.id.laser:
            LaserMode newLaser = mCall.getLaser().next();
            mCall.setLaser(newLaser);
            break;
        case R.id.take_picture:
            mCall.takePicture();
            mDrawerLayout.closeDrawers();
            break;
        case R.id.hangup:
            mCall.stop();
            mDrawerLayout.closeDrawers();
            break;
        }
        return false;
    };

}
