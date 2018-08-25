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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import com.iristick.smartglass.core.Headset;
import com.iristick.smartglass.core.IristickConnection;
import com.iristick.smartglass.core.IristickManager;
import com.koushikdutta.async.http.AsyncHttpClient;
import com.koushikdutta.async.http.WebSocket;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoSink;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.voiceengine.WebRtcAudioManager;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Future;

import app.wizzeye.app.MainActivity;
import app.wizzeye.app.R;
import app.wizzeye.app.SettingsActivity;

public class CallService extends Service {

    public class LocalBinder extends Binder {
        public CallService getService() {
            return CallService.this;
        }
    }

    public interface Listener {
        void onCallStateChanged(CallState newState);
    }

    private static final String TAG = "CallService";
    private static final String NOTIFICATION_CHANNEL = "ongoing";
    private static final int NOTIFICATION_ID = 1;
    private static final String ACTION_HANGUP = "app.wizzeye.action.HANGUP";

    private final LocalBinder mBinder = new LocalBinder();
    private final List<Listener> mListeners = new LinkedList<>();

    private SharedPreferences mPreferences;
    private NotificationManager mNotificationManager;
    private ConnectivityManager mConnectivityManager;
    private Handler mHandler;
    private EglBase mEglBase;

    private CallState mState = CallState.IDLE;
    private CallError mError;

    private Uri mUri;
    private ConnectivityManager.NetworkCallback mNetworkMonitor;
    private Future<WebSocket> mFutureSocket;
    private SignalingProtocol mSignal;
    private Headset mHeadset;
    private PeerConnectionFactory mFactory;
    private IristickCapturer mVideoCap;
    private VideoSource mVideoSrc;
    private VideoTrack mVideoTrack;
    private AudioSource mAudioSrc;
    private AudioTrack mAudioTrack;
    private PeerConnection mPC;


    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Public API

    public void registerListener(Listener listener) {
        mListeners.add(listener);
        listener.onCallStateChanged(mState);
    }

    public void unregisterListener(Listener listener) {
        mListeners.remove(listener);
    }

    public CallState getState() {
        return mState;
    }

    public CallError getError() {
        return mError;
    }

    public String getRoomLink() {
        return mUri != null ? mUri.toString() : "";
    }

    public String getRoomName() {
        if (mUri == null)
            return "";
        String name = mUri.getPath();
        name = name.replaceAll("^/+", "");
        name = name.replaceAll("/+$", "");
        return name;
    }

    public EglBase getEglBase() {
        return mEglBase;
    }

    public void hangup() {
        if (mState.ordinal() > CallState.IDLE.ordinal()) {
            disconnect(CallState.IDLE);
            setState(CallState.IDLE);
        }
    }

    public void addVideoSink(VideoSink sink) {
        if (mVideoTrack != null) {
            mVideoTrack.addSink(sink);
        }
    }

    public void removeVideoSink(VideoSink sink) {
        if (mVideoTrack != null) {
            mVideoTrack.removeSink(sink);
        }
    }

    public int getZoom() {
        return mVideoCap != null ? mVideoCap.getZoom() : 0;
    }

    public void setZoom(int zoom) {
        if (mVideoCap != null)
            mVideoCap.setZoom(zoom);
    }

    public boolean getTorch() {
        return mVideoCap != null && mVideoCap.getTroch();
    }

    public void setTorch(boolean torch) {
        if (mVideoCap != null)
            mVideoCap.setTorch(torch);
    }

    public void triggerAF() {
        if (mVideoCap != null)
            mVideoCap.triggerAF();
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Helpers

    private void setState(CallState newState) {
        Log.i(TAG, "Switching to state " + newState);
        mState = newState;
        if (mState.ordinal() > CallState.IDLE.ordinal())
            mNotificationManager.notify(NOTIFICATION_ID, buildNotification());
        for (Listener l : mListeners)
            l.onCallStateChanged(newState);
    }

    private Notification buildNotification() {
        Notification.Builder builder = new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.notification);
        builder.setColor(getColor(R.color.primary));
        builder.setContentTitle(getString(mState.title));
        if (mState == CallState.ERROR)
            builder.setContentText(getString(mError.message));
        builder.setSubText(getRoomName());
        builder.setContentIntent(PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class), 0));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(NOTIFICATION_CHANNEL);
        }
        if (mState.ordinal() > CallState.IDLE.ordinal()) {
            builder.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.hangup),
                    getString(R.string.hangup),
                    PendingIntent.getService(this, 0,
                        new Intent(this, getClass())
                            .setAction(ACTION_HANGUP),
                        0))
                .build());
        }
        return builder.build();
    }

    private List<PeerConnection.IceServer> getIceServers() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();

        // Add default STUN server
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
            .createIceServer());

        String turnHost = mPreferences.getString(SettingsActivity.KEY_TURN_HOSTNAME, "");
        if (!turnHost.isEmpty()) {
            iceServers.add(PeerConnection.IceServer.builder("turn:" + turnHost)
                .setUsername(mPreferences.getString(SettingsActivity.KEY_TURN_USERNAME, ""))
                .setPassword(mPreferences.getString(SettingsActivity.KEY_TURN_PASSWORD, ""))
                .createIceServer());
        }

        return iceServers;
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Service implementation

    @Override
    public void onCreate() {
        super.onCreate();
        mPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        mNotificationManager = getSystemService(NotificationManager.class);
        mConnectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        mHandler = new Handler();

        mEglBase = EglBase.create();
        PeerConnectionFactory.InitializationOptions.Builder builder = PeerConnectionFactory.InitializationOptions.builder(this);
        builder.setEnableVideoHwAcceleration(true);
        PeerConnectionFactory.initialize(builder.createInitializationOptions());
        WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.notif_channel_description));
            mNotificationManager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        disconnect(CallState.IDLE);
        PeerConnectionFactory.shutdownInternalTracer();
        mEglBase.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private void disconnect(CallState limit) {
        Log.d(TAG, "Destroying up to " + limit);

        if (mVideoCap != null)
            mVideoCap.stopCapture();
        if (mPC != null) {
            mPC.close();
            mPC = null;
        }
        if (mAudioTrack != null) {
            mAudioTrack.dispose();
            mAudioTrack = null;
        }
        if (mAudioSrc != null) {
            mAudioSrc.dispose();
            mAudioSrc = null;
        }
        if (mVideoTrack != null) {
            mVideoTrack.dispose();
            mVideoTrack = null;
        }
        if (mVideoSrc != null) {
            mVideoSrc.dispose();
            mVideoSrc = null;
        }
        if (mVideoCap != null) {
            mVideoCap.dispose();
            mVideoCap = null;
        }
        if (mFactory != null) {
            mFactory.dispose();
            mFactory = null;
        }

        if (limit.ordinal() >= CallState.WAITING_FOR_OBSERVER.ordinal())
            return;

        if (mSignal != null)
            mSignal.leave();
        mHeadset = null;

        if (limit.ordinal() >= CallState.WAITING_FOR_HEADSET.ordinal())
            return;

        IristickManager.getInstance().unbind(mIristickConnection);
        if (mSignal != null) {
            mSignal.close();
            mSignal = null;
        }

        if (limit.ordinal() >= CallState.CONNECTING_TO_SERVER.ordinal())
            return;

        if (mFutureSocket != null) {
            mFutureSocket.cancel(true);
            mFutureSocket = null;
        }

        if (limit.ordinal() >= CallState.WAITING_FOR_NETWORK.ordinal())
            return;

        if (mNetworkMonitor != null) {
            mConnectivityManager.unregisterNetworkCallback(mNetworkMonitor);
            mNetworkMonitor = null;
        }
        mUri = null;

        if (limit.ordinal() >= CallState.ERROR.ordinal())
            return;

        stopForeground(true);
        stopSelf();
    }

    private void setError(CallError error) {
        disconnect(CallState.ERROR);
        mError = error;
        setState(CallState.ERROR);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
        Uri uri = intent.getData();
        Log.i(TAG, "onStart(a=" + action + ", d=" + uri + ")");

        if (ACTION_HANGUP.equals(action)) {
            hangup();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (mState != CallState.IDLE) {
            Log.w(TAG, "Call in progress, ignoring start request");
            return START_NOT_STICKY;
        }
        if (uri == null || uri.getPathSegments().isEmpty()) {
            Log.e(TAG, "Missing room name");
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());

        mUri = uri;
        startNetworkMonitor();
        setState(CallState.WAITING_FOR_NETWORK);

        return START_REDELIVER_INTENT;
    }

    private void startNetworkMonitor() {
        if (mNetworkMonitor != null) {
            mConnectivityManager.unregisterNetworkCallback(mNetworkMonitor);
            mNetworkMonitor = null;
        }
        mNetworkMonitor = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(final Network network) {
                mHandler.post(() -> {
                    if (mState != CallState.WAITING_FOR_NETWORK)
                        return;

                    // Connect to server through websocket
                    mFutureSocket = AsyncHttpClient.getDefaultInstance().websocket(
                        mUri.buildUpon().path("/ws").build().toString(),
                        "v1", mSocketConnectCallback);

                    setState(CallState.CONNECTING_TO_SERVER);
                });
            }

            @Override
            public void onLost(final Network network) {
                mHandler.post(() -> {
                    if (mState.ordinal() > CallState.WAITING_FOR_NETWORK.ordinal()) {
                        disconnect(CallState.WAITING_FOR_NETWORK);
                        startNetworkMonitor();
                        setState(CallState.WAITING_FOR_NETWORK);
                    }
                });
            }
        };
        mConnectivityManager.requestNetwork(new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            mNetworkMonitor);
    }

    private final AsyncHttpClient.WebSocketConnectCallback mSocketConnectCallback = new AsyncHttpClient.WebSocketConnectCallback() {
        @Override
        public void onCompleted(final Exception ex, final WebSocket webSocket) {
            mHandler.post(() -> {
                mFutureSocket = null;
                if (mState.ordinal() < CallState.CONNECTING_TO_SERVER.ordinal()) {
                    Log.d(TAG, "Discarding obsolete websocket connection");
                    if (webSocket != null)
                        webSocket.close();
                } else if (ex == null) {
                    mSignal = new SignalingProtocol(webSocket, mSignalingCallback, mHandler);
                    IristickManager.getInstance().bind(mIristickConnection, CallService.this, mHandler);
                    setState(CallState.WAITING_FOR_HEADSET);
                } else {
                    Log.e(TAG, "Failed to connect to server", ex);
                    setError(CallError.SERVER_UNREACHABLE);
                }
            });
        }
    };

    private final IristickConnection mIristickConnection = new IristickConnection() {
        @Override
        public void onHeadsetConnected(Headset headset) {
            mHeadset = headset;
            mSignal.join(getRoomName());
            setState(CallState.WAITING_FOR_OBSERVER);
        }

        @Override
        public void onHeadsetDisconnected(Headset headset) {
            // Make this asynchronous, as onHeadsetDisconnected gets called when we unbind from
            // Iristick Services.
            mHandler.post(() -> {
               if (mState.ordinal() > CallState.WAITING_FOR_HEADSET.ordinal()) {
                   disconnect(CallState.WAITING_FOR_HEADSET);
                   setState(CallState.WAITING_FOR_HEADSET);
               }
            });
        }

        @Override
        public void onIristickServiceInitialized() {
        }

        @Override
        public void onIristickServiceError(int error) {
            if (mState.ordinal() < CallState.WAITING_FOR_HEADSET.ordinal())
                return;
            switch (error) {
            case ERROR_NOT_INSTALLED:
                Log.e(TAG, "Iristick Services not installed");
                setError(CallError.SERVICES_NOT_INSTALLED);
                break;
            case ERROR_FUTURE_SDK:
                Log.e(TAG, "Iristick Services are outdated");
                setError(CallError.SERVICES_OUTDATED);
                break;
            default:
                Log.e(TAG, "Unknown Iristick Services error " + error);
                setError(CallError.SERVICES_UNKNOWN);
            }
        }
    };

    private final SignalingProtocol.Listener mSignalingCallback = new SignalingProtocol.Listener() {
        @Override
        public void onError(int code, String text) {
            if (mState.ordinal() <= CallState.IDLE.ordinal())
                return;
            switch (code) {
            case ERROR_ROLE_TAKEN:
                Log.i(TAG, "A glass-wearer is already present in the room");
                setError(CallError.ROOM_BUSY);
                break;
            case ERROR_BAD_ROOM:
                Log.i(TAG, "Invalid room name");
                setError(CallError.INVALID_ROOM);
                break;
            default:
                Log.e(TAG, "Signaling error " + code + ": " + text);
                setError(CallError.SIGNALING);
            }
        }

        @Override
        public void onJoin(String room, String role) {
            if (!"observer".equals(role) || mState.ordinal() < CallState.WAITING_FOR_OBSERVER.ordinal())
                return;

            disconnect(CallState.WAITING_FOR_OBSERVER);

            /* Create PeerConnection factory */
            PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
            options.networkIgnoreMask = 16; // ADAPTER_TYPE_LOOPBACK
            options.disableNetworkMonitor = true;
            mFactory = PeerConnectionFactory.builder().setOptions(options).createPeerConnectionFactory();
            mFactory.setVideoHwAccelerationOptions(mEglBase.getEglBaseContext(), mEglBase.getEglBaseContext());

            /* Set up video source */
            mVideoCap = new IristickCapturer(mHeadset, mCameraCallback);
            mVideoSrc = mFactory.createVideoSource(mVideoCap);
            mVideoCap.startCapture(640, 480, 30);
            mVideoTrack = mFactory.createVideoTrack("Wizzeye_v0", mVideoSrc);
            mVideoTrack.setEnabled(true);

            /* Set up audio source */
            mAudioSrc = mFactory.createAudioSource(new MediaConstraints());
            mAudioTrack = mFactory.createAudioTrack("Wizzeye_a0", mAudioSrc);
            mAudioTrack.setEnabled(true);

            /* Create PeerConnection */
            PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(getIceServers());
            mPC = mFactory.createPeerConnection(config, mPCObserver);

            /* Create local media stream */
            MediaStream localStream = mFactory.createLocalMediaStream("Wizzeye");
            localStream.addTrack(mVideoTrack);
            localStream.addTrack(mAudioTrack);
            mPC.addStream(localStream);

            /* Create offer */
            MediaConstraints sdpcstr = new MediaConstraints();
            sdpcstr.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
            sdpcstr.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
            mPC.createOffer(mSdpObserver, sdpcstr);

            setState(CallState.ESTABLISHING);
        }

        @Override
        public void onLeave(String room, String role) {
            if (mState.ordinal() > CallState.WAITING_FOR_OBSERVER.ordinal()) {
                disconnect(CallState.WAITING_FOR_OBSERVER);
                setState(CallState.WAITING_FOR_OBSERVER);
            }
        }

        @Override
        public void onAnswer(SessionDescription answer) {
            if (mPC != null) {
                mPC.setRemoteDescription(mSdpObserver, answer);
                setState(CallState.CALL_IN_PROGRESS);
            }
        }

        @Override
        public void onIceCandidate(IceCandidate candidate) {
            if (mPC != null)
                mPC.addIceCandidate(candidate);
        }
    };

    private final PeerConnection.Observer mPCObserver = new PeerConnection.Observer() {
        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
        }

        @Override
        public void onIceConnectionReceivingChange(boolean b) {
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
        }

        @Override
        public void onIceCandidate(final IceCandidate iceCandidate) {
            if (iceCandidate != null) {
                Log.d(TAG, "Got ICE candidate: " + iceCandidate);
                mHandler.post(() -> {
                    if (mSignal != null)
                        mSignal.iceCandidate(iceCandidate);
                });
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
    };

    private final SdpObserver mSdpObserver = new SdpObserver() {
        @Override
        public void onCreateSuccess(final SessionDescription sessionDescription) {
            mHandler.post(() -> {
                if (mPC != null) {
                    Log.d(TAG, "Offer created");
                    mPC.setLocalDescription(mSdpObserver, sessionDescription);
                    mSignal.offer(sessionDescription);
                }
            });
        }

        @Override
        public void onSetSuccess() {
        }

        @Override
        public void onCreateFailure(String s) {
            Log.e(TAG, "Failed to create offer: " + s);
            mHandler.post(() -> {
                if (mState.ordinal() >= CallState.ESTABLISHING.ordinal())
                    setError(CallError.WEBRTC);
            });
        }

        @Override
        public void onSetFailure(String s) {
            Log.e(TAG, "SetDescription failed: " + s);
            mHandler.post(() -> {
                if (mState.ordinal() >= CallState.ESTABLISHING.ordinal())
                    setError(CallError.WEBRTC);
            });
        }
    };

    private final CameraVideoCapturer.CameraEventsHandler mCameraCallback = new CameraVideoCapturer.CameraEventsHandler() {
        @Override
        public void onCameraError(String msg) {
            Log.e(TAG, "Camera error: " + msg);
            mHandler.post(() -> {
                if (mState.ordinal() >= CallState.ESTABLISHING.ordinal())
                    setError(CallError.CAMERA);
            });
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
    };
}
