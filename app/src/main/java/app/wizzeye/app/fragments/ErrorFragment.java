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

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import app.wizzeye.app.R;
import app.wizzeye.app.service.CallError;

public class ErrorFragment extends InRoomFragment {

    private CallError mError;
    private long mErrorTimestamp;

    private Button mRestart;
    private Handler mHandler;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (mCall != null) {
            mError = mCall.getError();
            mErrorTimestamp = mCall.getErrorTimestamp();
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_error, container, false);
        TextView error = view.findViewById(R.id.error);
        if (mError != null)
            error.setText(mError.message);
        error.setMovementMethod(LinkMovementMethod.getInstance());
        mRestart = view.findViewById(R.id.restart);
        if (mCall != null)
            mRestart.setOnClickListener(v -> mCall.restart());
        mHandler = new Handler();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        mHandler.post(mUpdateTimeoutRunnable);
    }

    @Override
    public void onPause() {
        mHandler.removeCallbacks(mUpdateTimeoutRunnable);
        super.onPause();
    }

    private final Runnable mUpdateTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (mError != null && mError.retryTimeout > 0) {
                long deadline = mErrorTimestamp + mError.retryTimeout * 1000;
                long seconds = Math.max(0, (deadline - System.currentTimeMillis()) / 1000);
                mRestart.setText(getString(R.string.error_try_again_countdown, seconds));
                mHandler.postDelayed(this, 1000);
            }
        }
    };

}
