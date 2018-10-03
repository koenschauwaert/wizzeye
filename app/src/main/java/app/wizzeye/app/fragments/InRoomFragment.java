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

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import app.wizzeye.app.R;
import app.wizzeye.app.service.Call;

/**
 * Base class for fragments after the room has been chosen.
 * All fragments include a hangup button.
 */
public abstract class InRoomFragment extends BaseFragment {

    @Nullable
    protected Call mCall;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mCall = mService.getCall();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.inroom, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        View hangup = view.findViewById(R.id.hangup);
        if (hangup != null && mCall != null)
            hangup.setOnClickListener(v -> mCall.stop());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mCall == null)
            return super.onOptionsItemSelected(item);
        switch (item.getItemId()) {
        case R.id.share_link:
            mMainActivity.startActivity(new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, mCall.getRoomLink()));
            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public boolean onBackPressed() {
        if (mCall == null)
            return false;
        new AlertDialog.Builder(getContext())
            .setMessage(R.string.back_dialog_message)
            .setPositiveButton(R.string.back_dialog_action_continue, (dialog, which) -> getActivity().finish())
            .setNegativeButton(R.string.hangup, (dialog, which) -> mCall.stop())
            .show();
        return true;
    }

}
