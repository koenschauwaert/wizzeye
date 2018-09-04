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
import android.support.design.widget.FloatingActionButton;
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
    private SeekBar mZoom;
    private FloatingActionButton mMore;
    private DrawerLayout mDrawerLayout;
    private NavigationView mOptions;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_call, container, false);
        mVideo = view.findViewById(R.id.video);
        mVideo.init(mService.getEglBase().getEglBaseContext(), null);
        mVideo.setEnableHardwareScaler(true);
        mVideo.setOnClickListener(v -> mService.triggerAF());

        mZoom = view.findViewById(R.id.zoom);
        mZoom.setProgress(mService.getZoom());
        mZoom.setOnSeekBarChangeListener(mZoomListener);

        mDrawerLayout = view.findViewById(R.id.drawer_layout);
        mDrawerLayout.addDrawerListener(mDrawerListener);
        mOptions = view.findViewById(R.id.options);
        mOptions.setNavigationItemSelectedListener(mOptionsListener);
        mMore = view.findViewById(R.id.more);
        mMore.setOnClickListener(v -> mDrawerLayout.openDrawer(mOptions));

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
