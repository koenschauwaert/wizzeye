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
import android.support.design.widget.NavigationView;
import android.support.v4.widget.DrawerLayout;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;

import org.webrtc.SurfaceViewRenderer;

import app.wizzeye.app.R;

public class CallFragment extends InRoomFragment {

    private SurfaceViewRenderer mVideo;
    private DrawerLayout mDrawerLayout;
    private NavigationView mOptions;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_call, container, false);
        mVideo = view.findViewById(R.id.video);
        mVideo.init(mService.getEglBase().getEglBaseContext(), null);
        mVideo.setEnableHardwareScaler(true);
        mVideo.setOnClickListener(v -> mService.triggerAF());

        SeekBar zoom = view.findViewById(R.id.zoom);
        zoom.setProgress(mService.getZoom());
        zoom.setOnSeekBarChangeListener(mZoomListener);

        mDrawerLayout = view.findViewById(R.id.drawer_layout);
        mOptions = view.findViewById(R.id.options);
        mOptions.setNavigationItemSelectedListener(mOptionsListener);
        view.findViewById(R.id.more).setOnClickListener(v -> mDrawerLayout.openDrawer(mOptions));

        mOptions.getMenu().findItem(R.id.torch).setChecked(mService.getTorch());

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        mService.addVideoSink(mVideo);
    }

    @Override
    public void onStop() {
        mService.removeVideoSink(mVideo);
        mVideo.release();
        super.onStop();
    }

    @Override
    public boolean onBackPressed() {
        if (mDrawerLayout.isDrawerOpen(mOptions)) {
            mDrawerLayout.closeDrawers();
            return true;
        }
        return super.onBackPressed();
    }

    private final SeekBar.OnSeekBarChangeListener mZoomListener = new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            mService.setZoom(progress);
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    };

    private final NavigationView.OnNavigationItemSelectedListener mOptionsListener = item -> {
        switch (item.getItemId()) {
        case R.id.torch:
            boolean newTorch = !mService.getTorch();
            mService.setTorch(newTorch);
            item.setChecked(newTorch);
            break;
        case R.id.hangup:
            mService.hangup();
            mDrawerLayout.closeDrawers();
            break;
        }
        return false;
    };

}
