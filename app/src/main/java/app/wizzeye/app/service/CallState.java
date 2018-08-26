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
package app.wizzeye.app.service;

import android.support.annotation.NonNull;
import android.support.annotation.StringRes;

import app.wizzeye.app.R;
import app.wizzeye.app.fragments.BaseFragment;
import app.wizzeye.app.fragments.CallFragment;
import app.wizzeye.app.fragments.ConnectingFragment;
import app.wizzeye.app.fragments.ErrorFragment;
import app.wizzeye.app.fragments.RoomSelectionFragment;

public enum CallState {
    IDLE(RoomSelectionFragment.class, R.string.state_idle, 0),
    ERROR(ErrorFragment.class, R.string.state_error, 0),
    WAITING_FOR_NETWORK(ConnectingFragment.class, R.string.state_waiting_for_network, R.string.state_hint_waiting_for_network),
    CONNECTING_TO_SERVER(ConnectingFragment.class, R.string.state_connecting_to_server, 0),
    WAITING_FOR_OBSERVER(ConnectingFragment.class, R.string.state_waiting_for_observer, R.string.state_hint_waiting_for_observer),
    WAITING_FOR_HEADSET(ConnectingFragment.class, R.string.state_waiting_for_headset, R.string.state_hint_waiting_for_headset),
    ESTABLISHING(ConnectingFragment.class, R.string.state_establishing, 0),
    CALL_IN_PROGRESS(CallFragment.class, R.string.state_call_in_progress, 0),
    ;

    @NonNull public final Class<? extends BaseFragment> fragmentClass;
    @StringRes public final int title;
    @StringRes public final int hint;

    CallState(@NonNull Class<? extends BaseFragment> fragmentClass, @StringRes int title, @StringRes int hint) {
        this.fragmentClass = fragmentClass;
        this.title = title;
        this.hint = hint;
    }
}
