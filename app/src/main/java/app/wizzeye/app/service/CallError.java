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

import android.support.annotation.StringRes;

import app.wizzeye.app.R;

public enum CallError {
    WIZZEYE_OUTDATED(R.string.error_wizzeye_outdated, 0),
    INVALID_ICE_SERVERS(R.string.error_invalid_ice_servers, 0),
    SERVER_UNREACHABLE(R.string.error_server_unreachable, 10),
    SERVICES_NOT_INSTALLED(R.string.error_services_not_installed, 0),
    SERVICES_OUTDATED(R.string.error_services_outdated, 0),
    SERVICES_UNKNOWN(R.string.error_services_unknown, 30),
    INVALID_ROOM(R.string.error_invalid_room, 0),
    ROOM_BUSY(R.string.error_room_busy, 0),
    SIGNALING(R.string.error_signaling, 30),
    WEBRTC(R.string.error_webrtc, 30),
    ICE(R.string.error_ice, 10),
    CAMERA(R.string.error_camera, 30),
    ;

    @StringRes public final int message;
    public final int retryTimeout;

    CallError(@StringRes int message, int retryTimeout) {
        this.message = message;
        this.retryTimeout = retryTimeout;
    }
}
