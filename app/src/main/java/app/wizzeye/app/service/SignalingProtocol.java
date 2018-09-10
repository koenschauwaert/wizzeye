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

import android.os.Handler;
import android.util.Log;

import com.koushikdutta.async.http.WebSocket;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;
import org.webrtc.SessionDescription;

import java.util.List;

class SignalingProtocol {

    interface Listener {
        int ERROR_UNKNOWN = 1;
        int ERROR_BAD_MESSAGE = 2;
        int ERROR_NO_ROOM = 3;
        int ERROR_ROLE_TAKEN = 4;
        int ERROR_BAD_ROOM = 5;

        void onError(int code, String text);
        void onJoin(String room, String role);
        void onLeave(String room, String role);
        void onAnswer(SessionDescription answer);
        void onIceCandidate(IceCandidate candidate);
    }

    public static final String VERSION = "v1.signaling.wizzeye.app";

    private static final String TAG = "SignalingProtocol";

    private final WebSocket mSocket;
    private final Listener mListener;
    private final Handler mHandler;

    SignalingProtocol(WebSocket socket, final Listener listener, final Handler handler) {
        mSocket = socket;
        mListener = listener;
        mHandler = handler;
        socket.setStringCallback(this::onMessage);
    }

    void close() {
        mSocket.close();
    }

    private void onMessage(final String s) {
        mHandler.post(() -> {
            try {
                Log.d(TAG, ">> " + s);
                JSONObject msg = new JSONObject(s);
                String type = msg.getString("type");
                switch (type) {
                case "error":
                    mListener.onError(msg.getInt("code"), msg.getString("text"));
                    break;
                case "join":
                    mListener.onJoin(msg.getString("room"), msg.getString("role"));
                    break;
                case "leave":
                    mListener.onLeave(msg.getString("room"), msg.getString("role"));
                    break;
                case "answer":
                    mListener.onAnswer(new SessionDescription(SessionDescription.Type.ANSWER,
                        msg.getJSONObject("payload").getString("sdp")));
                    break;
                case "ice-candidate":
                    JSONObject c = msg.getJSONObject("payload");
                    mListener.onIceCandidate(new IceCandidate(
                        c.getString("sdpMid"),
                        c.getInt("sdpMLineIndex"),
                        c.getString("candidate")
                    ));
                    break;
                default:
                    Log.w(TAG, "Got unknown message of type " + type);
                }
            } catch (JSONException e) {
                Log.e(TAG, "Received invalid JSON message", e);
            }
        });
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

    void offer(SessionDescription offer, List<PeerConnection.IceServer> iceServers) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "offer");
            JSONObject payload = new JSONObject();
            payload.put("type", "offer");
            payload.put("sdp", offer.description);
            msg.put("payload", payload);
            if (iceServers != null) {
                JSONArray array = new JSONArray();
                for (PeerConnection.IceServer server : iceServers) {
                    JSONObject obj = new JSONObject();
                    JSONArray urls = new JSONArray();
                    for (String url : server.urls)
                        urls.put(url);
                    obj.put("urls", urls);
                    if (!server.username.isEmpty())
                        obj.put("username", server.username);
                    if (!server.password.isEmpty())
                        obj.put("credential", server.password);
                    array.put(obj);
                }
                msg.put("iceServers", array);
            }
            send(msg);
        } catch (JSONException e) {
            Log.e(TAG, "Misformatted JSON", e);
        }
    }

    void iceCandidate(IceCandidate candidate) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "ice-candidate");
            JSONObject payload = new JSONObject();
            payload.put("candidate", candidate.sdp);
            payload.put("sdpMid", candidate.sdpMid);
            payload.put("sdpMLineIndex", candidate.sdpMLineIndex);
            msg.put("payload", payload);
            send(msg);
        } catch (JSONException e) {
            Log.e(TAG, "Misformatted JSON", e);
        }
    }

}
