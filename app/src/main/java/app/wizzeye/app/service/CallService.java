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
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.util.Log;

import org.webrtc.EglBase;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.voiceengine.WebRtcAudioManager;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import app.wizzeye.app.BuildConfig;
import app.wizzeye.app.MainActivity;
import app.wizzeye.app.R;
import app.wizzeye.app.SettingsActivity;
import okhttp3.OkHttpClient;

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

    private static final int MSG_CALL_STATE_CHANGED = 0;

    private final LocalBinder mBinder = new LocalBinder();
    private final List<Listener> mListeners = new LinkedList<>();

    private SharedPreferences mPreferences;
    private NotificationManager mNotificationManager;
    OkHttpClient mHttpClient;
    EglBase mEglBase;

    private Call mCall;
    private Message mCallStateMessage;

    @Override
    public void onCreate() {
        super.onCreate();
        mPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        mNotificationManager = getSystemService(NotificationManager.class);
        Handler handler = new Handler(this::handleMessage);
        mCallStateMessage = handler.obtainMessage(MSG_CALL_STATE_CHANGED);

        mHttpClient = new OkHttpClient.Builder()
            .pingInterval(BuildConfig.PING_INTERVAL, TimeUnit.SECONDS)
            .build();

        mEglBase = EglBase.create();
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
            .builder(this)
            .createInitializationOptions());
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
        if (mCall != null) {
            mCall.unregisterMessage(mCallStateMessage);
            mCall.dispose();
            mCall = null;
        }
        mCallStateMessage.recycle();
        PeerConnectionFactory.shutdownInternalTracer();
        mEglBase.release();
        super.onDestroy();
    }

    public void registerListener(Listener listener) {
        mListeners.add(listener);
        listener.onCallStateChanged(mCall != null ? mCall.getState() : CallState.IDLE);
    }

    public void unregisterListener(Listener listener) {
        mListeners.remove(listener);
    }

    @Nullable
    public Call getCall() {
        return mCall;
    }

    public EglBase getEglBase() {
        return mEglBase;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
        Uri uri = intent.getData();
        Log.i(TAG, "onStart(a=" + action + ", d=" + uri + ")");

        if (ACTION_HANGUP.equals(action)) {
            if (mCall != null)
                mCall.stop();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (mCall != null) {
            Log.w(TAG, "Call in progress, ignoring start request");
            return START_NOT_STICKY;
        }
        if (uri == null || uri.getPathSegments().isEmpty()) {
            Log.e(TAG, "Missing room name");
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());
        mCall = new Call(this, uri);
        mCall.registerMessage(Call.Event.STATE_CHANGED, mCallStateMessage);
        mPreferences.edit().putString(SettingsActivity.KEY_LAST_ROOM, mCall.getRoomName()).apply();
        mCall.start();
        return START_REDELIVER_INTENT;
    }

    private boolean handleMessage(Message msg) {
        switch (msg.what) {
        case MSG_CALL_STATE_CHANGED:
            if (msg.obj != mCall)
                return true;
            CallState newState = CallState.values()[msg.arg1];
            if (newState == CallState.IDLE) {
                mCall.unregisterMessage(mCallStateMessage);
                mCall.dispose();
                mCall = null;
                stopForeground(true);
                stopSelf();
            } else {
                mNotificationManager.notify(NOTIFICATION_ID, buildNotification());
            }
            for (Listener l : mListeners)
                l.onCallStateChanged(newState);
            return true;
        }
        return false;
    }

    private Notification buildNotification() {
        CallState state = mCall != null ? mCall.getState() : CallState.IDLE;
        CallError error = mCall != null ? mCall.getError() : null;
        Notification.Builder builder = new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.notification);
        builder.setColor(getColor(R.color.primary));
        builder.setContentTitle(getString(state.title));
        if (state == CallState.ERROR && error != null) {
            builder.setContentText(getString(error.message));
            builder.setStyle(new Notification.BigTextStyle());
        }
        if (mCall != null)
            builder.setSubText(mCall.getRoomName());
        builder.setContentIntent(PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class), 0));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(NOTIFICATION_CHANNEL);
        }
        if (state.ordinal() > CallState.IDLE.ordinal()) {
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

}
