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
package app.wizzeye.app.call;

import android.util.Log;

import com.koushikdutta.async.http.WebSocket;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

class SignalingProtocol {

    interface Listener {
        int ERROR_UNKNOWN = 1;
        int ERROR_BAD_MESSAGE = 2;
        int ERROR_NO_ROOM = 3;
        int ERROR_ROLE_TAKEN = 4;

        void onError(int code, String text);
        void onJoin(String room, String role);
        void onLeave(String room, String role);
        void onAnswer(SessionDescription answer);
        void onIceCandidate(IceCandidate candidate);
    }

    private static final String TAG = "SignalingProtocol";

    private final WebSocket mSocket;

    SignalingProtocol(WebSocket socket, final Listener listener) {
        mSocket = socket;
        socket.setStringCallback(s -> {
            try {
                Log.d(TAG, ">> " + s);
                JSONObject msg = new JSONObject(s);
                String type = msg.getString("type");
                switch (type) {
                case "error":
                    listener.onError(msg.getInt("code"), msg.getString("text"));
                    break;
                case "join":
                    listener.onJoin(msg.getString("room"), msg.getString("role"));
                    break;
                case "leave":
                    listener.onLeave(msg.getString("room"), msg.getString("role"));
                    break;
                case "broadcast":
                    msg = msg.getJSONObject("data");
                    type = msg.getString("type");
                    switch (type) {
                    case "answer":
                        listener.onAnswer(new SessionDescription(SessionDescription.Type.ANSWER,
                            msg.getString("sdp")));
                        break;
                    case "ice-candidate":
                        JSONObject c = msg.getJSONObject("candidate");
                        listener.onIceCandidate(new IceCandidate(
                            c.getString("sdpMid"),
                            c.getInt("sdpMLineIndex"),
                            c.getString("candidate")
                        ));
                        break;
                    default:
                        Log.w(TAG, "Got unknown broadcast of type " + type);
                    }
                    break;
                default:
                    Log.w(TAG, "Got unknown message of type " + type);
                }
            } catch (JSONException e) {
                Log.e(TAG, "Received invalid JSON message", e);
            }
        });
    }

    void close() {
        mSocket.close();
    }

    private void send(JSONObject msg) {
        String s = msg.toString();
        Log.d(TAG, "<< " + s);
        mSocket.send(s);
    }

    void join(String room) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "join");
            msg.put("room", room);
            msg.put("role", "glass-wearer");
            send(msg);
        } catch (JSONException e) {
            Log.e(TAG, "Misformatted JSON", e);
        }
    }

    void leave() {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "leave");
            send(msg);
        } catch (JSONException e) {
            Log.e(TAG, "Misformatted JSON", e);
        }
    }

    void offer(SessionDescription offer) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "broadcast");
            JSONObject data = new JSONObject();
            data.put("type", "offer");
            data.put("sdp", offer.description);
            msg.put("data", data);
            send(msg);
        } catch (JSONException e) {
            Log.e(TAG, "Misformatted JSON", e);
        }
    }

    void iceCandidate(IceCandidate candidate) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "broadcast");
            JSONObject data = new JSONObject();
            data.put("type", "ice-candidate");
            JSONObject c = new JSONObject();
            c.put("candidate", candidate.sdp);
            c.put("sdpMid", candidate.sdpMid);
            c.put("sdpMLineIndex", candidate.sdpMLineIndex);
            data.put("candidate", c);
            msg.put("data", data);
            send(msg);
        } catch (JSONException e) {
            Log.e(TAG, "Misformatted JSON", e);
        }
    }

}
