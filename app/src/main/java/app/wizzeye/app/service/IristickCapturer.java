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
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.Surface;
import android.widget.Toast;

import com.iristick.smartglass.core.Headset;
import com.iristick.smartglass.core.camera.CameraCharacteristics;
import com.iristick.smartglass.core.camera.CameraDevice;
import com.iristick.smartglass.core.camera.CaptureFailure;
import com.iristick.smartglass.core.camera.CaptureListener;
import com.iristick.smartglass.core.camera.CaptureRequest;
import com.iristick.smartglass.core.camera.CaptureResult;
import com.iristick.smartglass.core.camera.CaptureSession;

import org.webrtc.CameraVideoCapturer;
import org.webrtc.CapturerObserver;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import app.wizzeye.app.R;

class IristickCapturer implements CameraVideoCapturer {

    private static final String TAG = "IristickCapturer";

    private static final DateFormat PICTURE_FILENAME = new SimpleDateFormat("'IMG_'yyyyMMdd_HHmmssSSS'.jpg'", Locale.US);

    private static final int MSG_SET_ZOOM = 0;          // arg1 = zoom level
    private static final int MSG_TRIGGER_AF = 1;        // empty
    private static final int MSG_TAKE_PICTURE = 2;      // empty

    /* Initialized by constructor */
    private final Headset mHeadset;
    private final CameraEventsHandler mEvents;
    private final String[] mCameraNames;

    /* Initialized by initialize() */
    private SurfaceTextureHelper mSurfaceHelper;
    private Context mContext;
    private CapturerObserver mObserver;
    private Handler mCameraThreadHandler;
    private Handler mMessageHandler;
    private ImageReader mImageReader;

    /* State objects guarded by mStateLock */
    private final Object mStateLock = new Object();
    private boolean mSessionOpening;
    private boolean mStopping;
    private int mFailureCount;
    private int mZoom;
    private int mCameraIdx;
    private int mWidth;
    private int mHeight;
    private int mFramerate;
    private CameraDevice mCamera;
    private Surface mSurface;
    private CaptureSession mCaptureSession;
    private boolean mFirstFrameObserved;

    IristickCapturer(@NonNull Headset headset, @Nullable CameraEventsHandler eventsHandler, int zoom) {
        if (eventsHandler == null) {
            eventsHandler = new CameraEventsHandler() {
                @Override
                public void onCameraError(String s) {}
                @Override
                public void onCameraDisconnected() {}
                @Override
                public void onCameraFreezed(String s) {}
                @Override
                public void onCameraOpening(String s) {}
                @Override
                public void onFirstFrameAvailable() {}
                @Override
                public void onCameraClosed() {}
            };
        }
        mHeadset = headset;
        mEvents = eventsHandler;
        mZoom = zoom;
        mCameraNames = headset.getCameraIdList();
    }

    @Override
    public void initialize(SurfaceTextureHelper surfaceTextureHelper, Context context, CapturerObserver capturerObserver) {
        mSurfaceHelper = surfaceTextureHelper;
        mContext = context;
        mObserver = capturerObserver;
        mCameraThreadHandler = surfaceTextureHelper.getHandler();
        mMessageHandler = new Handler(mCameraThreadHandler.getLooper(), mMessageCallback);

        Point[] sizes = mHeadset.getCameraCharacteristics(mCameraNames[0])
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            .getSizes(CaptureRequest.FORMAT_JPEG);
        mImageReader = ImageReader.newInstance(sizes[0].x, sizes[0].y,
            ImageFormat.JPEG, 2);
        mImageReader.setOnImageAvailableListener(mImageReaderListener, mCameraThreadHandler);
    }

    @Override
    public void startCapture(int width, int height, int framerate) {
        Log.d(TAG, "startCapture: " + width + "x" + height + "@" + framerate);

        if (mContext == null)
            throw new IllegalStateException("CameraCapturer must be initialized before calling startCapture");

        synchronized (mStateLock) {
            if (mSessionOpening || mCaptureSession != null) {
                Log.w(TAG, "Capture already started");
                return;
            }

            mCameraIdx = (mCameraNames.length >= 2 && mZoom > 0 ? 1 : 0);
            mWidth = width;
            mHeight = height;
            mFramerate = framerate;

            openCamera(true);
        }
    }

    @Override
    public void stopCapture() {
        Log.d(TAG, "stopCapture");

        synchronized (mStateLock) {
            mStopping = true;
            while (mSessionOpening) {
                Log.d(TAG, "stopCapture: Waiting for session to open");
                try {
                    mStateLock.wait();
                } catch (InterruptedException e) {
                    Log.w(TAG, "stopCapture: Interrupted while waiting for session to open");
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            if (mCaptureSession != null) {
                closeCamera();
                mObserver.onCapturerStopped();
            } else {
                Log.d(TAG, "stopCapture: No session open");
            }
            mStopping = false;
        }

        Log.d(TAG, "stopCapture: Done");
    }

    @Override
    public void changeCaptureFormat(int width, int height, int framerate) {
        synchronized (mStateLock) {
            stopCapture();
            startCapture(width, height, framerate);
        }
    }

    @Override
    public void dispose() {
        stopCapture();
    }

    @Override
    public boolean isScreencast() {
        return false;
    }

    @Override
    public void switchCamera(final CameraSwitchHandler cameraSwitchHandler) {
        throw new UnsupportedOperationException();
    }

    void setZoom(int zoom) {
        mMessageHandler.obtainMessage(MSG_SET_ZOOM, zoom, 0).sendToTarget();
    }

    void triggerAF() {
        mMessageHandler.obtainMessage(MSG_TRIGGER_AF).sendToTarget();
    }

    void takePicture() {
        mMessageHandler.obtainMessage(MSG_TAKE_PICTURE).sendToTarget();
    }

    private void openCamera(boolean resetFailures) {
        synchronized (mStateLock) {
            if (resetFailures)
                mFailureCount = 0;
            closeCamera();
            mSessionOpening = true;
            mCameraThreadHandler.post(() -> {
                synchronized (mStateLock) {
                    if (mCameraIdx >= mCameraNames.length) {
                        mEvents.onCameraError("Headset has no camera index " + mCameraIdx);
                        return;
                    }
                    final String name = mCameraNames[mCameraIdx];
                    mEvents.onCameraOpening(name);
                    try {
                        mHeadset.openCamera(name, mCameraListener, mCameraThreadHandler);
                    } catch (IllegalArgumentException e) {
                        mEvents.onCameraError("Unknown camera: " + name);
                    }
                }
            });
        }
    }

    private void closeCamera() {
        synchronized (mStateLock) {
            final CameraDevice camera = mCamera;
            final Surface surface = mSurface;
            mCameraThreadHandler.post(() -> {
                mSurfaceHelper.stopListening();
                try {
                    if (camera != null)
                        camera.close();
                } catch (IllegalStateException e) {
                    // ignore
                }
                if (surface != null)
                    surface.release();
            });
            mCaptureSession = null;
            mSurface = null;
            mCamera = null;
        }
    }

    private void checkIsOnCameraThread() {
        if(Thread.currentThread() != mCameraThreadHandler.getLooper().getThread()) {
            Log.e(TAG, "Check is on camera thread failed.");
            throw new RuntimeException("Not on camera thread.");
        }
    }

    private void handleFailure(String error) {
        checkIsOnCameraThread();
        synchronized (mStateLock) {
            if (mSessionOpening) {
                if (mCamera != null) {
                    mCamera.close();
                    mCamera = null;
                    mSurface.release();
                    mSurface = null;
                }
                mObserver.onCapturerStarted(false);
                mSessionOpening = false;
                mStateLock.notifyAll();
            }
            if ("Disconnected".equals(error)) {
                mEvents.onCameraDisconnected();
                if (!mStopping)
                    stopCapture();
            } else if (mFailureCount < 3 && !mStopping) {
                mFailureCount++;
                mCameraThreadHandler.postDelayed(() -> openCamera(false), 200);
            } else {
                mEvents.onCameraError(error);
                if (!mStopping)
                    stopCapture();
            }
        }
    }

    private void setupCaptureRequest(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.SCALER_ZOOM, (float)(1 << Math.max(0, mZoom - 1)));
        if (mCameraIdx == 1)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
    }

    private void applyParametersInternal() {
        Log.d(TAG, "applyParametersInternal");
        checkIsOnCameraThread();
        synchronized (mStateLock) {
            if (mSessionOpening || mStopping || mCaptureSession == null)
                return;

            if (mCameraNames.length >= 2 &&
                    ((mZoom == 0 && mCameraIdx != 0) || (mZoom > 0 && mCameraIdx != 1))) {
                Log.d(TAG, "Switching cameras");
                mCameraIdx = (mCameraIdx + 1) % 2;
                openCamera(true);
            } else {
                CaptureRequest.Builder builder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                builder.addTarget(mSurface);
                builder.set(CaptureRequest.SENSOR_FRAME_DURATION, 1000000000L / mFramerate);
                setupCaptureRequest(builder);
                mCaptureSession.setRepeatingRequest(builder.build(), null, null);
            }
        }
    }

    private void triggerAFInternal() {
        Log.d(TAG, "triggerAFInternal");
        checkIsOnCameraThread();
        synchronized (mStateLock) {
            if (mCameraIdx != 1 || mSessionOpening || mStopping || mCaptureSession == null)
                return;

            CaptureRequest.Builder builder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(mSurface);
            setupCaptureRequest(builder);
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            mCaptureSession.capture(builder.build(), null, null);
        }
    }

    private void takePictureInternal() {
        Log.d(TAG, "takePictureInternal");
        checkIsOnCameraThread();
        synchronized (mStateLock) {
            if (mSessionOpening || mStopping || mCaptureSession == null)
                return;

            CaptureRequest.Builder builder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(mImageReader.getSurface());
            setupCaptureRequest(builder);
            mCaptureSession.capture(builder.build(), mCaptureListener, mCameraThreadHandler);
        }
    }

    /* Called on camera thread */

    private final Handler.Callback mMessageCallback = msg -> {
        switch (msg.what) {
        case MSG_SET_ZOOM:
            synchronized (mStateLock) {
                mZoom = msg.arg1;
                applyParametersInternal();
            }
            return true;
        case MSG_TRIGGER_AF:
            triggerAFInternal();
            return true;
        case MSG_TAKE_PICTURE:
            takePictureInternal();
            return true;
        }
        return false;
    };

    private final CameraDevice.Listener mCameraListener = new CameraDevice.Listener() {
        @Override
        public void onOpened(CameraDevice device) {
            checkIsOnCameraThread();
            synchronized (mStateLock) {
                mCamera = device;

                mSurfaceHelper.setTextureSize(mWidth, mHeight);
                mSurface = new Surface(mSurfaceHelper.getSurfaceTexture());

                List<Surface> outputs = new ArrayList<>();
                outputs.add(mSurface);
                outputs.add(mImageReader.getSurface());
                mCamera.createCaptureSession(outputs, mCaptureSessionListener, mCameraThreadHandler);
            }
        }

        @Override
        public void onClosed(CameraDevice device) {}

        @Override
        public void onDisconnected(CameraDevice device) {
            checkIsOnCameraThread();
            synchronized (mStateLock) {
                if (mCamera == device || mCamera == null)
                    handleFailure("Disconnected");
                else
                    Log.w(TAG, "onDisconnected from another CameraDevice");
            }
        }

        @Override
        public void onError(CameraDevice device, int error) {
            checkIsOnCameraThread();
            synchronized (mStateLock) {
                if (mCamera == device || mCamera == null)
                    handleFailure("Camera device error " + error);
                else
                    Log.w(TAG, "onError from another CameraDevice");
            }
        }
    };

    private final CaptureSession.Listener mCaptureSessionListener = new CaptureSession.Listener() {
        @Override
        public void onConfigured(CaptureSession session) {
            checkIsOnCameraThread();
            synchronized (mStateLock) {
                mSurfaceHelper.startListening(mSink);
                mObserver.onCapturerStarted(true);
                mSessionOpening = false;
                mCaptureSession = session;
                mFirstFrameObserved = false;
                mStateLock.notifyAll();
                if (mCameraIdx == 1) {
                    mCameraThreadHandler.removeCallbacks(IristickCapturer.this::triggerAFInternal);
                    mCameraThreadHandler.postDelayed(IristickCapturer.this::triggerAFInternal, 500);
                }
                applyParametersInternal();
            }
        }

        @Override
        public void onConfigureFailed(CaptureSession session, int error) {
            handleFailure("Error while creating capture session");
        }

        @Override
        public void onClosed(CaptureSession session) {}

        @Override
        public void onActive(CaptureSession session) {}

        @Override
        public void onCaptureQueueEmpty(CaptureSession session) {}

        @Override
        public void onReady(CaptureSession session) {}
    };

    private final VideoSink mSink = new VideoSink() {
        @Override
        public void onFrame(VideoFrame frame) {
            checkIsOnCameraThread();
            synchronized (mStateLock) {
                if (mCaptureSession == null)
                    return;
                if (!mFirstFrameObserved) {
                    mEvents.onFirstFrameAvailable();
                    mFirstFrameObserved = true;
                }
                mObserver.onFrameCaptured(frame);
            }
        }
    };

    private final ImageReader.OnImageAvailableListener mImageReaderListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Log.d(TAG, "onImageAvailable");
            try (final Image image = reader.acquireLatestImage()) {
                if (image == null) {
                    Log.w(TAG, "No image available in callback");
                    return;
                }

                File dir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    mContext.getString(R.string.app_name));
                if (!dir.exists()) {
                    if (!dir.mkdirs()) {
                        Log.e(TAG, "Failed to create directory " + dir.getPath());
                        Toast.makeText(mContext, R.string.call_toast_picture_fail, Toast.LENGTH_SHORT).show();
                        return;
                    }
                }

                File file = new File(dir, PICTURE_FILENAME.format(new Date()));
                try (OutputStream os = new FileOutputStream(file)) {
                    Channels.newChannel(os).write(image.getPlanes()[0].getBuffer());
                } catch (IOException e) {
                    Log.e(TAG, "Failed to write capture to " + file.getPath(), e);
                    Toast.makeText(mContext, R.string.call_toast_picture_fail, Toast.LENGTH_SHORT).show();
                    return;
                }
                MediaScannerConnection.scanFile(mContext, new String[] { file.toString() }, null, null);
                Toast.makeText(mContext, R.string.call_toast_picture_taken, Toast.LENGTH_SHORT).show();
            }
        }
    };

    private final CaptureListener mCaptureListener = new CaptureListener() {
        @Override
        public void onCaptureStarted(CaptureSession session, CaptureRequest request, long timestamp, long frameNumber) {
        }

        @Override
        public void onCaptureBufferLost(CaptureSession session, CaptureRequest request, Surface surface, long frameNumber) {
        }

        @Override
        public void onCaptureCompleted(CaptureSession session, CaptureRequest request, CaptureResult result) {
        }

        @Override
        public void onCaptureFailed(CaptureSession session, CaptureRequest request, CaptureFailure failure) {
            Toast.makeText(mContext, R.string.call_toast_picture_fail, Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onCaptureSequenceCompleted(CaptureSession session, int sequenceId, long frameNumber) {
        }

        @Override
        public void onCaptureSequenceAborted(CaptureSession session, int sequenceId) {
        }
    };
}
