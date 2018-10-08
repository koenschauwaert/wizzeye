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

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.iristick.smartglass.core.Headset;
import com.iristick.smartglass.core.IristickConnection;
import com.iristick.smartglass.support.app.IristickApp;
import com.koushikdutta.async.http.AsyncHttpClient;
import com.koushikdutta.async.http.WebSocket;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoSink;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import app.wizzeye.app.SettingsActivity;

public class Call {

    public enum Event {
        STATE_CHANGED,              // arg1 = (CallState) newState
        PARAMETERS_CHANGED,         // empty
        TURBULENCE,                 // arg1 = (boolean) turbulence
    }

    private static final String TAG = "Call";

    private final Context mContext;
    private final EglBase mEglBase;
    private final Uri mUri;
    private final SharedPreferences mPreferences;
    private final ConnectivityManager mConnectivityManager;
    private final Handler mMainThreadHandler;
    private final HandlerThread mThread;
    private final Handler mHandler;
    private final List<Message>[] mMessages;

    private final String mRoomName;
    private final CallQuality mQuality;

    private volatile CallState mState = CallState.IDLE;
    private volatile CallError mError = null;
    private volatile int mZoom = 0;
    private volatile boolean mTorch = false;
    private volatile LaserMode mLaser = LaserMode.AUTO;

    Call(@NonNull Context context, @NonNull EglBase eglBase, @NonNull Uri uri) {
        mContext = context;
        mEglBase = eglBase;
        mUri = uri;
        mPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        mConnectivityManager = context.getSystemService(ConnectivityManager.class);
        mMainThreadHandler = new Handler();
        mThread = new HandlerThread("Call");
        mThread.start();
        mHandler = new Handler(mThread.getLooper(), this::handleMessage);
        //noinspection unchecked
        mMessages = new List[Event.values().length];
        for (int i = 0; i < mMessages.length; i++)
            mMessages[i] = new ArrayList<>();

        String path = uri.getPath();
        if (path != null) {
            mRoomName = path.replaceAll("^/+", "")
                .replaceAll("/+$", "");
        } else {
            mRoomName = "";
        }
        CallQuality quality;
        try {
            quality = CallQuality.valueOf(mPreferences.getString(SettingsActivity.KEY_VIDEO_QUALITY, CallQuality.NORMAL.name()));
        } catch (IllegalArgumentException e) {
            quality = CallQuality.NORMAL;
        }
        mQuality = quality;
        try {
            mLaser = LaserMode.valueOf(mPreferences.getString(SettingsActivity.KEY_LASER_MODE, LaserMode.AUTO.name()));
        } catch (IllegalArgumentException e) {
            // ignore bad preference value
        }
    }

    void dispose() {
        sendMessage(What.STOP, 0, 0, null);
        mThread.quitSafely();
    }

    private synchronized void fireStateChanged(CallState newState) {
        for (Message msg : mMessages[Event.STATE_CHANGED.ordinal()]) {
            Message copy = Message.obtain(msg);
            copy.arg1 = newState.ordinal();
            copy.obj = this;
            copy.sendToTarget();
        }
    }

    private synchronized void fireParametersChanged() {
        for (Message msg : mMessages[Event.PARAMETERS_CHANGED.ordinal()])
            Message.obtain(msg).sendToTarget();
    }

    private synchronized void fireTurbulence(boolean turbulence) {
        for (Message msg : mMessages[Event.TURBULENCE.ordinal()]) {
            Message copy = Message.obtain(msg);
            copy.arg1 = turbulence ? 1 : 0;
            copy.sendToTarget();
        }
    }

    public synchronized void registerMessage(@NonNull Event event, @NonNull Message msg) {
        mMessages[event.ordinal()].add(msg);
    }

    public synchronized void unregisterMessage(@NonNull Message msg) {
        for (List<Message> list : mMessages)
            list.remove(msg);
    }

    @NonNull
    public String getRoomLink() {
        return mUri.toString();
    }

    @NonNull
    public String getRoomName() {
        return mRoomName;
    }

    @NonNull
    public CallQuality getQuality() {
        return mQuality;
    }

    @NonNull
    public CallState getState() {
        return mState;
    }

    @Nullable
    public CallError getError() {
        return mError;
    }

    void start() {
        sendMessage(What.START, 0, 0, null);
    }

    public void stop() {
        sendMessage(What.STOP, 0, 0, null);
    }

    public void restart() {
        sendMessage(What.RESTART, 0, 0, null);
    }

    public void addVideoSink(VideoSink sink) {
        sendMessage(What.ADD_VIDEO_SINK, 0, 0, sink);
    }

    public void removeVideoSink(VideoSink sink) {
        sendMessage(What.REMOVE_VIDEO_SINK, 0, 0, sink);
    }

    public void triggerAF() {
        sendMessage(What.TRIGGER_AF, 0, 0, null);
    }

    public void takePicture() {
        sendMessage(What.TAKE_PICTURE, 0, 0, null);
    }

    public int getZoom() {
        return mZoom;
    }

    public synchronized void setZoom(int zoom) {
        mZoom = zoom;
        fireParametersChanged();
    }

    public boolean getTorch() {
        return mTorch;
    }

    public synchronized void setTorch(boolean torch) {
        mTorch = torch;
        fireParametersChanged();
    }

    @NonNull
    public LaserMode getLaser() {
        return mLaser;
    }

    public synchronized void setLaser(LaserMode laser) {
        mLaser = laser;
        fireParametersChanged();
        mPreferences.edit().putString(SettingsActivity.KEY_LASER_MODE,
            laser == LaserMode.AUTO ? LaserMode.AUTO.name() : LaserMode.OFF.name()).apply();
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////
    // State machine

    // Internal variable only used on this thread
    private NetworkMonitor mNetworkMonitor;
    private Future<WebSocket> mFutureSocket;
    private WebSocketCallback mWebSocketCallback;
    private SignalingProtocol mSignal;
    private IristickCallback mIristickCallback;
    private Headset mHeadset;
    private WebRtcCallback mWebRtcCallback;
    private PeerConnectionFactory mFactory;
    private SurfaceTextureHelper mSurfaceTextureHelper;
    private IristickCapturer mVideoCap;
    private VideoSource mVideoSrc;
    private VideoTrack mVideoTrack;
    private AudioSource mAudioSrc;
    private AudioTrack mAudioTrack;
    private PeerConnection mPC;

    /** Internal message "what" codes */
    private enum What {
        START,                      // empty
        STOP,                       // empty
        RESTART,                    // empty
        NETWORK_AVAILABLE,          // empty
        NETWORK_LOST,               // empty
        WEBSOCKET_CONNECTED,        // obj = (WebSocket)
        WEBSOCKET_FAILED,           // obj = (Exception)
        SIGNALING_DISCONNECTED,     // empty
        SIGNALING_ERROR,            // arg1 = code, obj = (String) text
        SIGNALING_OBSERVER_JOINED,  // empty
        SIGNALING_LEAVE,            // empty
        SIGNALING_RESET,            // empty
        SIGNALING_ANSWER,           // obj = (SessionDescription)
        SIGNALING_ICE_CANDIDATE,    // obj = (IceCandidate)
        HEADSET_CONNECTED,          // obj = (Headset)
        HEADSET_DISCONNECTED,       // empty
        IRISTICK_ERROR,             // arg1 = error
        PC_ICE_CONNECTED,           // empty
        PC_ICE_DISCONNECTED,        // empty
        PC_ICE_FAILED,              // empty
        PC_ICE_CANDIDATE,           // obj = (IceCandidate)
        SDP_CREATE_SUCCESS,         // obj = (SessionDescription)
        SDP_CREATE_FAILURE,         // empty
        SDP_SET_FAILURE,            // empty
        CAMERA_ERROR,               // empty
        ADD_VIDEO_SINK,             // obj = (VideoSink)
        REMOVE_VIDEO_SINK,          // obj = (VideoSink)
        TRIGGER_AF,                 // empty
        TAKE_PICTURE,               // empty
    }

    @SuppressWarnings("SameParameterValue")
    private void sendMessage(What what, int arg1, int arg2, Object obj) {
        mHandler.sendMessage(mHandler.obtainMessage(what.ordinal(), arg1, arg2, obj));
    }

    private void removeMessages(What what) {
        mHandler.removeMessages(what.ordinal());
    }

    private boolean handleMessage(Message msg) {
        What what = What.values()[msg.what];
        Log.d(TAG, "State " + mState + ": message " + what);
        switch (mState) {
        case IDLE:
            switch (what) {
            case START:
                gotoState(CallState.WAITING_FOR_NETWORK);
                return true;
            case STOP:
                return true;
            }
            break;
        case ERROR:
            switch (what) {
            case STOP:
                gotoState(CallState.IDLE);
                return true;
            case RESTART:
                gotoState(CallState.WAITING_FOR_NETWORK);
                return true;
            }
            break;
        case WAITING_FOR_NETWORK:
            switch (what) {
            case NETWORK_AVAILABLE:
                gotoState(CallState.CONNECTING_TO_SERVER);
                return true;
            case STOP:
                gotoState(CallState.IDLE);
                return true;
            }
            break;
        case CONNECTING_TO_SERVER:
            switch (what) {
            case WEBSOCKET_CONNECTED:
                mSignal = new SignalingProtocol((WebSocket) msg.obj);
                gotoState(CallState.WAITING_FOR_OBSERVER);
                return true;
            case WEBSOCKET_FAILED:
                gotoError(CallError.SERVER_UNREACHABLE);
                return true;
            case NETWORK_LOST:
                gotoState(CallState.WAITING_FOR_NETWORK);
                return true;
            case STOP:
                gotoState(CallState.IDLE);
                return true;
            }
            break;
        case WAITING_FOR_OBSERVER:
            switch (what) {
            case SIGNALING_OBSERVER_JOINED:
                gotoState(CallState.WAITING_FOR_HEADSET);
                return true;
            case SIGNALING_DISCONNECTED:
                gotoState(CallState.CONNECTING_TO_SERVER);
                return true;
            case SIGNALING_ERROR:
                handleSignalingError(msg);
                return true;
            case NETWORK_LOST:
                gotoState(CallState.WAITING_FOR_NETWORK);
                return true;
            case STOP:
                gotoState(CallState.IDLE);
                return true;
            }
            break;
        case WAITING_FOR_HEADSET:
            switch (what) {
            case HEADSET_CONNECTED:
                mHeadset = (Headset) msg.obj;
                gotoState(CallState.ESTABLISHING);
                return true;
            case IRISTICK_ERROR:
                switch (msg.arg1) {
                case IristickConnection.ERROR_NOT_INSTALLED:
                    Log.e(TAG, "Iristick Services not installed");
                    gotoError(CallError.SERVICES_NOT_INSTALLED);
                    break;
                case IristickConnection.ERROR_FUTURE_SDK:
                    Log.e(TAG, "Iristick Services are outdated");
                    gotoError(CallError.SERVICES_OUTDATED);
                    break;
                default:
                    Log.e(TAG, "Unknown Iristick Services error " + msg.arg1);
                    gotoError(CallError.SERVICES_UNKNOWN);
                }
                return true;
            case SIGNALING_LEAVE:
                gotoState(CallState.WAITING_FOR_OBSERVER);
                return true;
            case SIGNALING_DISCONNECTED:
                gotoState(CallState.CONNECTING_TO_SERVER);
                return true;
            case SIGNALING_ERROR:
                handleSignalingError(msg);
                return true;
            case NETWORK_LOST:
                gotoState(CallState.WAITING_FOR_NETWORK);
                return true;
            case STOP:
                gotoState(CallState.IDLE);
                return true;
            }
            break;
        case ESTABLISHING:
            switch (what) {
            case SDP_CREATE_SUCCESS:
                Log.d(TAG, "Offer created");
                mPC.setLocalDescription(mWebRtcCallback, (SessionDescription) msg.obj);
                mSignal.offer((SessionDescription) msg.obj, buildIceServers());
                return true;
            case SIGNALING_ANSWER:
                mPC.setRemoteDescription(mWebRtcCallback, (SessionDescription) msg.obj);
                return true;
            case SIGNALING_ICE_CANDIDATE:
                mPC.addIceCandidate((IceCandidate) msg.obj);
                return true;
            case PC_ICE_CANDIDATE:
                mSignal.iceCandidate((IceCandidate) msg.obj);
                return true;
            case PC_ICE_CONNECTED:
                gotoState(CallState.CALL_IN_PROGRESS);
                return true;
            case PC_ICE_FAILED:
                gotoError(CallError.ICE);
                return true;
            case SDP_CREATE_FAILURE:
            case SDP_SET_FAILURE:
                gotoError(CallError.WEBRTC);
                return true;
            case CAMERA_ERROR:
                gotoError(CallError.CAMERA);
                return true;
            case SIGNALING_RESET:
                gotoState(CallState.ESTABLISHING);
                return true;
            case HEADSET_DISCONNECTED:
                gotoState(CallState.WAITING_FOR_HEADSET);
                return true;
            case SIGNALING_LEAVE:
                gotoState(CallState.WAITING_FOR_OBSERVER);
                return true;
            case SIGNALING_DISCONNECTED:
                gotoState(CallState.CONNECTING_TO_SERVER);
                return true;
            case SIGNALING_ERROR:
                handleSignalingError(msg);
                return true;
            case NETWORK_LOST:
                gotoState(CallState.WAITING_FOR_NETWORK);
                return true;
            case STOP:
                gotoState(CallState.IDLE);
                return true;
            }
            break;
        case CALL_IN_PROGRESS:
            switch (what) {
            case ADD_VIDEO_SINK:
                mVideoTrack.addSink((VideoSink) msg.obj);
                return true;
            case REMOVE_VIDEO_SINK:
                mVideoTrack.addSink((VideoSink) msg.obj);
                return true;
            case TRIGGER_AF:
                mVideoCap.triggerAF();
                return true;
            case TAKE_PICTURE:
                mVideoCap.takePicture();
                return true;
            case SIGNALING_ICE_CANDIDATE:
                mPC.addIceCandidate((IceCandidate) msg.obj);
                return true;
            case PC_ICE_CANDIDATE:
                mSignal.iceCandidate((IceCandidate) msg.obj);
                return true;
            case PC_ICE_CONNECTED:
                fireTurbulence(false);
                return true;
            case PC_ICE_DISCONNECTED:
                fireTurbulence(true);
                return true;
            case PC_ICE_FAILED:
                gotoState(CallState.ESTABLISHING);
                return true;
            case CAMERA_ERROR:
                gotoError(CallError.CAMERA);
                return true;
            case SIGNALING_RESET:
                gotoState(CallState.ESTABLISHING);
                return true;
            case HEADSET_DISCONNECTED:
                gotoState(CallState.WAITING_FOR_HEADSET);
                return true;
            case SIGNALING_LEAVE:
                gotoState(CallState.WAITING_FOR_OBSERVER);
                return true;
            case SIGNALING_DISCONNECTED:
                gotoState(CallState.CONNECTING_TO_SERVER);
                return true;
            case SIGNALING_ERROR:
                handleSignalingError(msg);
                return true;
            case NETWORK_LOST:
                gotoState(CallState.WAITING_FOR_NETWORK);
                return true;
            case STOP:
                gotoState(CallState.IDLE);
                return true;
            }
            break;
        }
        // All handled messages end with a "return" statement, so that we can log unhandled
        // messages here
        Log.w(TAG, "Unhandled message " + what + " in state " + mState);
        return false;
    }

    private void handleSignalingError(Message msg) {
        switch (msg.arg1) {
        case SignalingProtocol.ERROR_ROLE_TAKEN:
            Log.i(TAG, "A glass-wearer is already present in the room");
            gotoError(CallError.ROOM_BUSY);
            break;
        case SignalingProtocol.ERROR_BAD_ROOM:
            Log.i(TAG, "Invalid room name");
            gotoError(CallError.INVALID_ROOM);
            break;
        default:
            Log.e(TAG, "Signaling error " + msg.arg1 + ": " + msg.obj);
            gotoError(CallError.SIGNALING);
        }
    }

    private void gotoState(final CallState newState) {
        final CallState oldState = mState;
        Log.d(TAG, oldState + " -> " + newState);

        /* From any state, we can go to a lower state (only deconstruction).
         * However, we can only construct one step at a time, hence we can only move up one state
         * at a time.
         * Special case for state IDLE who is allowed to skip over the ERROR state. */
        if (newState.ordinal() - oldState.ordinal() > (oldState == CallState.IDLE ? 2 : 1))
            throw new IllegalStateException("Invalid state transition: " + oldState + " -> " + newState);

        /* Fire state change events early-on so the UI can adapt itself while we are doing the
         * heavy work below. */
        mState = newState;
        fireStateChanged(newState);

        // Deconstruct state up to and including newState.
        // Fallthrough is intentional.
        switch (oldState) {
        case CALL_IN_PROGRESS:
        case ESTABLISHING:
            if (newState.ordinal() > CallState.ESTABLISHING.ordinal())
                break;
            Log.v(TAG, "Closing PeerConnection");
            mSignal.reset();
            mVideoCap.stopCapture();
            mWebRtcCallback.alive = false;
            mWebRtcCallback = null;
            mPC.close();
            mPC = null;
            mAudioTrack.dispose();
            mAudioTrack = null;
            mAudioSrc.dispose();
            mAudioSrc = null;
            mVideoTrack.dispose();
            mVideoTrack = null;
            mVideoCap.dispose();
            mVideoCap = null;
            mVideoSrc.dispose();
            mVideoSrc = null;
            mSurfaceTextureHelper.dispose();
            mSurfaceTextureHelper = null;
            mFactory.dispose();
            mFactory = null;
        case WAITING_FOR_HEADSET:
            if (newState.ordinal() > CallState.WAITING_FOR_HEADSET.ordinal())
                break;
            Log.v(TAG, "Unregistering Iristick listener");
            mHeadset = null;
            mIristickCallback.alive = false;
            IristickApp.unregisterConnectionListener(mIristickCallback);
            mIristickCallback = null;
            removeMessages(What.HEADSET_CONNECTED);
            removeMessages(What.HEADSET_DISCONNECTED);
        case WAITING_FOR_OBSERVER:
        case CONNECTING_TO_SERVER:
            if (newState.ordinal() > CallState.CONNECTING_TO_SERVER.ordinal())
                break;
            Log.v(TAG, "Closing websocket connection");
            if (mSignal != null) {
                mSignal.leave();
                mSignal.close();
                mSignal = null;
            }
            mWebSocketCallback.alive = false;
            mWebSocketCallback = null;
            mFutureSocket.cancel(true);
            mFutureSocket = null;
            removeMessages(What.SIGNALING_ICE_CANDIDATE);
            removeMessages(What.SIGNALING_ANSWER);
            removeMessages(What.SIGNALING_RESET);
            removeMessages(What.SIGNALING_LEAVE);
            removeMessages(What.SIGNALING_OBSERVER_JOINED);
            removeMessages(What.SIGNALING_ERROR);
            removeMessages(What.SIGNALING_DISCONNECTED);
            removeMessages(What.WEBSOCKET_FAILED);
            removeMessages(What.WEBSOCKET_CONNECTED);
        case WAITING_FOR_NETWORK:
            if (newState.ordinal() > CallState.WAITING_FOR_NETWORK.ordinal())
                break;
            Log.v(TAG, "Stoping network monitor");
            mNetworkMonitor.alive = false;
            mConnectivityManager.unregisterNetworkCallback(mNetworkMonitor);
            mNetworkMonitor = null;
            removeMessages(What.NETWORK_LOST);
            removeMessages(What.NETWORK_AVAILABLE);
        }

        // Construct one step
        switch (newState) {
        case WAITING_FOR_NETWORK:
            Log.v(TAG, "Starting network monitor");
            mNetworkMonitor = new NetworkMonitor();
            mConnectivityManager.requestNetwork(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                mNetworkMonitor);
            break;

        case CONNECTING_TO_SERVER:
            Log.v(TAG, "Connecting to websocket");
            mWebSocketCallback = new WebSocketCallback();
            mFutureSocket = AsyncHttpClient.getDefaultInstance().websocket(
                mUri.buildUpon().path("/ws").build().toString(),
                SignalingProtocol.VERSION, mWebSocketCallback);
            break;

        case WAITING_FOR_OBSERVER:
            if (mSignal == null) {
                Log.e(TAG, "No signaling server while constructing " + newState);
                break;
            }
            if (oldState.ordinal() < CallState.WAITING_FOR_OBSERVER.ordinal()) {
                Log.v(TAG, "Joining room " + mRoomName);
                mSignal.join(mRoomName);
            }
            break;

        case WAITING_FOR_HEADSET:
            mIristickCallback = new IristickCallback();
            IristickApp.registerConnectionListener(mIristickCallback, mMainThreadHandler);
            break;

        case ESTABLISHING:
            if (mHeadset == null) {
                Log.e(TAG, "No headset while construction " + newState);
                break;
            }
            Log.v(TAG, "Creating PeerConnection");
            mWebRtcCallback = new WebRtcCallback();

            /* Create PeerConnection factory */
            PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
            options.networkIgnoreMask = 16; // ADAPTER_TYPE_LOOPBACK
            options.disableNetworkMonitor = true;
            mFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(mEglBase.getEglBaseContext(), false, false))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(mEglBase.getEglBaseContext()))
                .createPeerConnectionFactory();

            /* Set up video source */
            mSurfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", mEglBase.getEglBaseContext());
            mVideoSrc = mFactory.createVideoSource(false);
            mVideoCap = new IristickCapturer(this, mHeadset, mWebRtcCallback);
            mVideoCap.initialize(mSurfaceTextureHelper, mContext, mVideoSrc.getCapturerObserver());
            mVideoCap.startCapture(mQuality.frameSize.getWidth(), mQuality.frameSize.getHeight(), 30);
            mVideoTrack = mFactory.createVideoTrack("Wizzeye_v0", mVideoSrc);
            mVideoTrack.setEnabled(true);

            /* Set up audio source */
            mAudioSrc = mFactory.createAudioSource(new MediaConstraints());
            mAudioTrack = mFactory.createAudioTrack("Wizzeye_a0", mAudioSrc);
            mAudioTrack.setEnabled(true);

            /* Create PeerConnection */
            PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(buildIceServers());
            mPC = mFactory.createPeerConnection(config, mWebRtcCallback);

            /* Create local media stream */
            MediaStream localStream = mFactory.createLocalMediaStream("Wizzeye");
            localStream.addTrack(mVideoTrack);
            localStream.addTrack(mAudioTrack);
            mPC.addStream(localStream);

            /* Create offer */
            MediaConstraints sdpcstr = new MediaConstraints();
            sdpcstr.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
            sdpcstr.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
            mPC.createOffer(mWebRtcCallback, sdpcstr);
            break;
        }
    }

    private void gotoError(CallError error) {
        mError = error;
        gotoState(CallState.ERROR);
    }

    @NonNull
    private List<PeerConnection.IceServer> buildIceServers() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();

        String stunHost = mPreferences.getString(SettingsActivity.KEY_STUN_HOSTNAME, "");
        if (!stunHost.isEmpty())
            iceServers.add(PeerConnection.IceServer.builder("stun:" + stunHost)
                .createIceServer());

        String turnHost = mPreferences.getString(SettingsActivity.KEY_TURN_HOSTNAME, "");
        if (!turnHost.isEmpty()) {
            iceServers.add(PeerConnection.IceServer.builder("turn:" + turnHost + "?transport=udp")
                .setUsername(mPreferences.getString(SettingsActivity.KEY_TURN_USERNAME, ""))
                .setPassword(mPreferences.getString(SettingsActivity.KEY_TURN_PASSWORD, ""))
                .createIceServer());
            iceServers.add(PeerConnection.IceServer.builder("turn:" + turnHost + "?transport=tcp")
                .setUsername(mPreferences.getString(SettingsActivity.KEY_TURN_USERNAME, ""))
                .setPassword(mPreferences.getString(SettingsActivity.KEY_TURN_PASSWORD, ""))
                .createIceServer());
        }

        return iceServers;
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Listeners

    private class NetworkMonitor extends ConnectivityManager.NetworkCallback {
        volatile boolean alive = true;
        @Override
        public void onAvailable(Network network) {
            if (alive)
                sendMessage(What.NETWORK_AVAILABLE, 0, 0, null);
        }
        @Override
        public void onLost(Network network) {
            if (alive)
                sendMessage(What.NETWORK_LOST, 0, 0, null);
        }
    }

    private class WebSocketCallback implements AsyncHttpClient.WebSocketConnectCallback {
        volatile boolean alive = true;
        @Override
        public void onCompleted(Exception ex, WebSocket webSocket) {
            if (!alive)
                return;
            if (ex == null)
                sendMessage(What.WEBSOCKET_CONNECTED, 0, 0, webSocket);
            else
                sendMessage(What.WEBSOCKET_FAILED, 0, 0, ex);
        }
    }

    private class IristickCallback implements IristickConnection {
        volatile boolean alive = true;
        @Override
        public void onHeadsetConnected(Headset headset) {
            if (alive)
                sendMessage(What.HEADSET_CONNECTED, 0, 0, headset);
        }
        @Override
        public void onHeadsetDisconnected(Headset headset) {
            if (alive)
                sendMessage(What.HEADSET_DISCONNECTED, 0, 0, null);
        }
        @Override
        public void onIristickServiceInitialized() {
        }
        @Override
        public void onIristickServiceError(int error) {
            if (alive)
                sendMessage(What.IRISTICK_ERROR, error, 0, null);
        }
    }

    private class WebRtcCallback
        implements PeerConnection.Observer, SdpObserver, CameraVideoCapturer.CameraEventsHandler {
        volatile boolean alive = true;
        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {
        }
        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
            if (!alive)
                return;
            switch (iceConnectionState) {
            case CONNECTED:
                sendMessage(What.PC_ICE_CONNECTED, 0, 0, null);
                break;
            case FAILED:
                sendMessage(What.PC_ICE_FAILED, 0, 0, null);
                break;
            case DISCONNECTED:
                sendMessage(What.PC_ICE_DISCONNECTED, 0, 0, null);
                break;
            }
        }
        @Override
        public void onIceConnectionReceivingChange(boolean b) {
        }
        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
        }
        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
            if (alive && iceCandidate != null) {
                Log.d(TAG, "Got ICE candidate: " + iceCandidate);
                sendMessage(What.PC_ICE_CANDIDATE, 0, 0, iceCandidate);
            }
        }
        @Override
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
        }
        @Override
        public void onAddStream(MediaStream mediaStream) {
        }
        @Override
        public void onRemoveStream(MediaStream mediaStream) {
        }
        @Override
        public void onDataChannel(DataChannel dataChannel) {
        }
        @Override
        public void onRenegotiationNeeded() {
        }
        @Override
        public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
        }
        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {
            if (alive)
                sendMessage(What.SDP_CREATE_SUCCESS, 0, 0, sessionDescription);
        }
        @Override
        public void onSetSuccess() {
        }
        @Override
        public void onCreateFailure(String s) {
            if (alive) {
                Log.e(TAG, "Failed to create offer: " + s);
                sendMessage(What.SDP_CREATE_FAILURE, 0, 0, null);
            }
        }
        @Override
        public void onSetFailure(String s) {
            if (alive) {
                Log.e(TAG, "SetDescription failed: " + s);
                sendMessage(What.SDP_SET_FAILURE, 0, 0, null);
            }
        }
        @Override
        public void onCameraError(String msg) {
            if (alive) {
                Log.e(TAG, "Camera error: " + msg);
                sendMessage(What.CAMERA_ERROR, 0, 0, null);
            }
        }
        @Override
        public void onCameraDisconnected() {
        }
        @Override
        public void onCameraFreezed(String s) {
        }
        @Override
        public void onCameraOpening(String s) {
        }
        @Override
        public void onFirstFrameAvailable() {
        }
        @Override
        public void onCameraClosed() {
        }
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Signaling protocol

    private class SignalingProtocol {
        static final String VERSION = "v1.signaling.wizzeye.app";

        @SuppressWarnings("unused") static final int ERROR_UNKNOWN = 1;
        @SuppressWarnings("unused") static final int ERROR_BAD_MESSAGE = 2;
        @SuppressWarnings("unused") static final int ERROR_NO_ROOM = 3;
        @SuppressWarnings("unused") static final int ERROR_ROLE_TAKEN = 4;
        @SuppressWarnings("unused") static final int ERROR_BAD_ROOM = 5;

        private final WebSocket mSocket;
        private volatile boolean mClosed = false;

        SignalingProtocol(WebSocket socket) {
            mSocket = socket;
            socket.setClosedCallback(this::onClosed);
            socket.setStringCallback(this::onMessage);
        }

        void close() {
            mClosed = true;
            mSocket.close();
        }

        private void onClosed(Exception ex) {
            if (mClosed)
                return;
            Log.d(TAG, "Socket closed: " + ex.getMessage());
            sendMessage(What.SIGNALING_DISCONNECTED, 0, 0, null);
        }

        private void onMessage(String s) {
            if (mClosed)
                return;
            try {
                Log.d(TAG, ">> " + s);
                JSONObject msg = new JSONObject(s);
                String type = msg.getString("type");
                switch (type) {
                case "error":
                    sendMessage(What.SIGNALING_ERROR, msg.getInt("code"), 0, msg.getString("text"));
                    break;
                case "join":
                    if ("observer".equals(msg.getString("role")))
                        sendMessage(What.SIGNALING_OBSERVER_JOINED, 0, 0, null);
                    break;
                case "leave":
                    sendMessage(What.SIGNALING_LEAVE, 0, 0, null);
                    break;
                case "reset":
                    sendMessage(What.SIGNALING_RESET, 0, 0, null);
                    break;
                case "answer":
                    sendMessage(What.SIGNALING_ANSWER, 0, 0,
                        new SessionDescription(SessionDescription.Type.ANSWER,
                            msg.getJSONObject("payload").getString("sdp")));
                    break;
                case "ice-candidate":
                    JSONObject c = msg.getJSONObject("payload");
                    sendMessage(What.SIGNALING_ICE_CANDIDATE, 0, 0, new IceCandidate(
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

        void reset() {
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", "reset");
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

}
