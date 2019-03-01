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
import com.iristick.smartglass.core.IristickBinding;
import com.iristick.smartglass.core.IristickConnection;
import com.iristick.smartglass.support.app.IristickApp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
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

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.wizzeye.app.SettingsActivity;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class Call {

    public enum Event {
        STATE_CHANGED,              // arg1 = (CallState) newState
        PARAMETERS_CHANGED,         // empty
        TURBULENCE,                 // arg1 = (boolean) turbulence
    }

    private static final String TAG = "Call";

    private final CallService mService;
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
    private volatile long mErrorTimestamp;
    private volatile int mZoom = 0;
    private volatile boolean mTorch = false;
    private volatile LaserMode mLaser = LaserMode.AUTO;

    Call(@NonNull CallService service, @NonNull Uri uri) {
        mService = service;
        mUri = uri;
        mPreferences = PreferenceManager.getDefaultSharedPreferences(mService);
        mConnectivityManager = mService.getSystemService(ConnectivityManager.class);
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
        sendMessage(What.STOP, 0, 0, null, 0);
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
        sendMessage(What.PARAMETERS_CHANGED, 0, 0, null, 0);
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

    public long getErrorTimestamp() {
        return mErrorTimestamp;
    }

    void start() {
        sendMessage(What.START, 0, 0, null, 0);
    }

    public void stop() {
        sendMessage(What.STOP, 0, 0, null, 0);
    }

    public void restart() {
        sendMessage(What.RESTART, 0, 0, null, 0);
    }

    public void addVideoSink(VideoSink sink) {
        sendMessage(What.ADD_VIDEO_SINK, 0, 0, sink, 0);
    }

    public void removeVideoSink(VideoSink sink) {
        sendMessage(What.REMOVE_VIDEO_SINK, 0, 0, sink, 0);
    }

    public void triggerAF() {
        sendMessage(What.TRIGGER_AF, 0, 0, null, 0);
    }

    public void takePicture() {
        sendMessage(What.TAKE_PICTURE, 0, 0, null, 0);
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
    private List<PeerConnection.IceServer> mIceServers;
    private NetworkMonitor mNetworkMonitor;
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
        WEBSOCKET_CONNECTED,        // empty
        WEBSOCKET_CLOSED,           // empty
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
        PARAMETERS_CHANGED,         // empty
        TRIGGER_AF,                 // empty
        TAKE_PICTURE,               // empty
    }

    @SuppressWarnings("SameParameterValue")
    private void sendMessage(What what, int arg1, int arg2, Object obj, long delayMs) {
        mHandler.sendMessageDelayed(mHandler.obtainMessage(what.ordinal(), arg1, arg2, obj), delayMs);
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
                try {
                    mIceServers = buildIceServers();
                    gotoState(CallState.WAITING_FOR_NETWORK);
                } catch (URISyntaxException e) {
                    gotoError(CallError.INVALID_ICE_SERVERS);
                }
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
                removeMessages(What.RESTART);
                try {
                    mIceServers = buildIceServers();
                    gotoState(CallState.WAITING_FOR_NETWORK);
                } catch (URISyntaxException e) {
                    gotoError(CallError.INVALID_ICE_SERVERS);
                }
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
                gotoState(CallState.WAITING_FOR_OBSERVER);
                return true;
            case WEBSOCKET_CLOSED:
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
            case SIGNALING_ERROR:
                handleSignalingError(msg);
                return true;
            case WEBSOCKET_CLOSED:
                gotoState(CallState.CONNECTING_TO_SERVER);
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
                case IristickConnection.ERROR_DEPRECATED_SDK:
                    Log.e(TAG, "Iristick SDK used to build Wizzeye is deprecated");
                    gotoError(CallError.WIZZEYE_OUTDATED);
                    break;
                default:
                    Log.e(TAG, "Unknown Iristick Services error " + msg.arg1);
                    gotoError(CallError.SERVICES_UNKNOWN);
                }
                return true;
            case SIGNALING_LEAVE:
                gotoState(CallState.WAITING_FOR_OBSERVER);
                return true;
            case SIGNALING_ERROR:
                handleSignalingError(msg);
                return true;
            case WEBSOCKET_CLOSED:
                gotoState(CallState.CONNECTING_TO_SERVER);
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
                mSignal.offer((SessionDescription) msg.obj, mIceServers);
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
            case SIGNALING_ERROR:
                handleSignalingError(msg);
                return true;
            case WEBSOCKET_CLOSED:
                gotoState(CallState.CONNECTING_TO_SERVER);
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
            case PARAMETERS_CHANGED:
                applyParameters();
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
            case SIGNALING_ERROR:
                handleSignalingError(msg);
                return true;
            case WEBSOCKET_CLOSED:
                gotoState(CallState.CONNECTING_TO_SERVER);
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
            Log.v(TAG, "Turning off torch and laser pointer");
            mHeadset.setTorchMode(false);
            mHeadset.setLaserPointer(false);
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
            mSignal.leave();
            mSignal.close();
            mSignal = null;
            removeMessages(What.SIGNALING_ICE_CANDIDATE);
            removeMessages(What.SIGNALING_ANSWER);
            removeMessages(What.SIGNALING_RESET);
            removeMessages(What.SIGNALING_LEAVE);
            removeMessages(What.SIGNALING_OBSERVER_JOINED);
            removeMessages(What.SIGNALING_ERROR);
            removeMessages(What.WEBSOCKET_CLOSED);
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
        case ERROR:
            if (newState.ordinal() > CallState.ERROR.ordinal())
                break;
            removeMessages(What.RESTART);
        }

        // Construct one step
        switch (newState) {
        case ERROR:
            if (mError.retryTimeout > 0) {
                Log.v(TAG, "Restarting in " + mError.retryTimeout + "s");
                sendMessage(What.RESTART, 0, 0, null, mError.retryTimeout * 1000);
            }
            break;

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
            mSignal = new SignalingProtocol(mUri.buildUpon().path("/ws").build().toString());
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
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(mService.mEglBase.getEglBaseContext(), false, false))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(mService.mEglBase.getEglBaseContext()))
                .createPeerConnectionFactory();

            /* Set up video source */
            mSurfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", mService.mEglBase.getEglBaseContext());
            mVideoSrc = mFactory.createVideoSource(false);
            mVideoCap = new IristickCapturer(mHeadset, mWebRtcCallback, mZoom);
            mVideoCap.initialize(mSurfaceTextureHelper, mService, mVideoSrc.getCapturerObserver());
            mVideoCap.startCapture(mQuality.frameSize.getWidth(), mQuality.frameSize.getHeight(), 30);
            mVideoTrack = mFactory.createVideoTrack("Wizzeye_v0", mVideoSrc);
            mVideoTrack.setEnabled(true);

            /* Set up audio source */
            mAudioSrc = mFactory.createAudioSource(new MediaConstraints());
            mAudioTrack = mFactory.createAudioTrack("Wizzeye_a0", mAudioSrc);
            mAudioTrack.setEnabled(true);

            /* Create PeerConnection */
            PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(mIceServers);
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

        case CALL_IN_PROGRESS:
            Log.v(TAG, "Applying call parameters");
            applyParameters();
            break;
        }
    }

    private void gotoError(CallError error) {
        mError = error;
        mErrorTimestamp = System.currentTimeMillis();
        gotoState(CallState.ERROR);
    }

    private void applyParameters() {
        final int zoom = mZoom;
        final boolean torch = mTorch;
        final LaserMode laser = mLaser;

        mVideoCap.setZoom(zoom);
        mHeadset.setTorchMode(torch);
        switch (laser) {
        case OFF:
            mHeadset.setLaserPointer(false);
            break;
        case ON:
            mHeadset.setLaserPointer(true);
            break;
        case AUTO:
            mHeadset.setLaserPointer(mZoom > 0);
            break;
        }
    }

    private static final Pattern HOSTNAME_PATTERN = Pattern.compile(
            "([a-zA-Z]+://)?" + // scheme
            "([a-zA-Z0-9][-a-zA-Z0-9]*(?:\\.[a-zA-Z0-9][-a-zA-Z0-9]*)*" + // hostname
            "|[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}" + // ipv4 address
            "|\\[[a-zA-Z0-9]+(?:::?[a-zA-Z0-9]+)\\])" + // ipv6 address
            "(:[0-9]+)?/?"); // port number

    @NonNull
    private List<PeerConnection.IceServer> buildIceServers() throws URISyntaxException {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();

        String stunHost = mPreferences.getString(SettingsActivity.KEY_STUN_HOSTNAME, "");
        if (!stunHost.isEmpty()) {
            Matcher m = HOSTNAME_PATTERN.matcher(stunHost);
            if (!m.matches())
                throw new URISyntaxException(stunHost, "Invalid STUN hostname");
            String host = m.group(2);
            String port = m.group(3);
            if (port != null)
                host += ":" + port;
            iceServers.add(PeerConnection.IceServer.builder("stun:" + host)
                .createIceServer());
        }

        String turnHost = mPreferences.getString(SettingsActivity.KEY_TURN_HOSTNAME, "");
        if (!turnHost.isEmpty()) {
            String user = mPreferences.getString(SettingsActivity.KEY_TURN_USERNAME, "");
            String pass = mPreferences.getString(SettingsActivity.KEY_TURN_PASSWORD, "");
            Matcher m = HOSTNAME_PATTERN.matcher(turnHost);
            if (!m.matches())
                throw new URISyntaxException(turnHost, "Invalid TURN hostname");
            String host = m.group(2);
            String port = m.group(3);
            if (port != null)
                host += ":" + port;
            iceServers.add(PeerConnection.IceServer.builder("turn:" + host + "?transport=udp")
                .setUsername(user).setPassword(pass).createIceServer());
            if (port == null)
                iceServers.add(PeerConnection.IceServer.builder("turn:" + host + ":80?transport=udp")
                    .setUsername(user).setPassword(pass).createIceServer());
            iceServers.add(PeerConnection.IceServer.builder("turn:" + host + "?transport=tcp")
                .setUsername(user).setPassword(pass).createIceServer());
            if (port == null)
                iceServers.add(PeerConnection.IceServer.builder("turn:" + host + ":80?transport=tcp")
                    .setUsername(user).setPassword(pass).createIceServer());
            iceServers.add(PeerConnection.IceServer.builder("turns:" + host + "?transport=tcp")
                .setUsername(user).setPassword(pass).createIceServer());
            if (port == null)
                iceServers.add(PeerConnection.IceServer.builder("turns:" + host + ":443?transport=tcp")
                    .setUsername(user).setPassword(pass).createIceServer());
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
                sendMessage(What.NETWORK_AVAILABLE, 0, 0, null, 0);
        }
        @Override
        public void onLost(Network network) {
            if (alive)
                sendMessage(What.NETWORK_LOST, 0, 0, null, 0);
        }
    }

    private class IristickCallback implements IristickConnection {
        volatile boolean alive = true;
        @Override
        public void onHeadsetConnected(Headset headset) {
            if (alive)
                sendMessage(What.HEADSET_CONNECTED, 0, 0, headset, 0);
        }
        @Override
        public void onHeadsetDisconnected(Headset headset) {
            if (alive)
                sendMessage(What.HEADSET_DISCONNECTED, 0, 0, null, 0);
        }
        @Override
        public void onIristickServiceInitialized(IristickBinding binding) {
        }
        @Override
        public void onIristickServiceError(int error) {
            if (alive)
                sendMessage(What.IRISTICK_ERROR, error, 0, null, 0);
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
                sendMessage(What.PC_ICE_CONNECTED, 0, 0, null, 0);
                break;
            case FAILED:
                sendMessage(What.PC_ICE_FAILED, 0, 0, null, 0);
                break;
            case DISCONNECTED:
                sendMessage(What.PC_ICE_DISCONNECTED, 0, 0, null, 0);
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
                sendMessage(What.PC_ICE_CANDIDATE, 0, 0, iceCandidate, 0);
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
                sendMessage(What.SDP_CREATE_SUCCESS, 0, 0, sessionDescription, 0);
        }
        @Override
        public void onSetSuccess() {
        }
        @Override
        public void onCreateFailure(String s) {
            if (alive) {
                Log.e(TAG, "Failed to create offer: " + s);
                sendMessage(What.SDP_CREATE_FAILURE, 0, 0, null, 0);
            }
        }
        @Override
        public void onSetFailure(String s) {
            if (alive) {
                Log.e(TAG, "SetDescription failed: " + s);
                sendMessage(What.SDP_SET_FAILURE, 0, 0, null, 0);
            }
        }
        @Override
        public void onCameraError(String msg) {
            if (alive) {
                Log.e(TAG, "Camera error: " + msg);
                sendMessage(What.CAMERA_ERROR, 0, 0, null, 0);
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

    private class SignalingProtocol extends WebSocketListener {
        static final String VERSION = "v1.signaling.wizzeye.app";

        @SuppressWarnings("unused") static final int ERROR_UNKNOWN = 1;
        @SuppressWarnings("unused") static final int ERROR_BAD_MESSAGE = 2;
        @SuppressWarnings("unused") static final int ERROR_NO_ROOM = 3;
        @SuppressWarnings("unused") static final int ERROR_ROLE_TAKEN = 4;
        @SuppressWarnings("unused") static final int ERROR_BAD_ROOM = 5;

        private static final int WEBSOCKET_CLOSE_GOING_AWAY = 1001;

        private final WebSocket mSocket;
        private volatile boolean mClosed = false;

        SignalingProtocol(String uri) {
            Request request = new Request.Builder()
                .url(uri)
                .header("Sec-WebSocket-Protocol", VERSION)
                .build();
            mSocket = mService.mHttpClient.newWebSocket(request, this);
        }

        void close() {
            mClosed = true;
            mSocket.close(WEBSOCKET_CLOSE_GOING_AWAY, null);
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            if (mClosed)
                return;
            sendMessage(What.WEBSOCKET_CONNECTED, 0, 0, null, 0);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response) {
            if (mClosed)
                return;
            Log.e(TAG, "Websocket failure", t);
            sendMessage(What.WEBSOCKET_CLOSED, 0, 0, null, 0);
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            if (mClosed)
                return;
            Log.d(TAG, "Websocket closing, code: " + code + ", reason: " + reason);
            mSocket.close(code, null);
            sendMessage(What.WEBSOCKET_CLOSED, 0, 0, null, 0);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            if (mClosed)
                return;
            try {
                Log.d(TAG, ">> " + text);
                JSONObject msg = new JSONObject(text);
                String type = msg.getString("type");
                switch (type) {
                case "error":
                    sendMessage(What.SIGNALING_ERROR, msg.getInt("code"), 0, msg.getString("text"), 0);
                    break;
                case "join":
                    if ("observer".equals(msg.getString("role")))
                        sendMessage(What.SIGNALING_OBSERVER_JOINED, 0, 0, null, 0);
                    break;
                case "leave":
                    sendMessage(What.SIGNALING_LEAVE, 0, 0, null, 0);
                    break;
                case "reset":
                    sendMessage(What.SIGNALING_RESET, 0, 0, null, 0);
                    break;
                case "answer":
                    sendMessage(What.SIGNALING_ANSWER, 0, 0,
                        new SessionDescription(SessionDescription.Type.ANSWER,
                            msg.getJSONObject("payload").getString("sdp")), 0);
                    break;
                case "ice-candidate":
                    JSONObject c = msg.getJSONObject("payload");
                    sendMessage(What.SIGNALING_ICE_CANDIDATE, 0, 0, new IceCandidate(
                            c.getString("sdpMid"),
                            c.getInt("sdpMLineIndex"),
                            c.getString("candidate")
                    ), 0);
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
