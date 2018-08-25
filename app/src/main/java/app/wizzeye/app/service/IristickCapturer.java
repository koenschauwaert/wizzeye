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
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.view.Surface;

import com.iristick.smartglass.core.Headset;
import com.iristick.smartglass.core.camera.CameraDevice;
import com.iristick.smartglass.core.camera.CaptureRequest;
import com.iristick.smartglass.core.camera.CaptureSession;

import org.webrtc.CameraVideoCapturer;
import org.webrtc.Logging;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoFrame;

import java.util.ArrayList;
import java.util.List;

class IristickCapturer implements CameraVideoCapturer {

    private static final String TAG = "IristickCapturer";

    /* Initialized by constructor */
    private final Headset mHeadset;
    private final CameraEventsHandler mEvents;
    private final String[] mCameraNames;

    /* Initialized by initialize() */
    private SurfaceTextureHelper mSurfaceHelper;
    private Context mAppContext;
    private CapturerObserver mObserver;
    private Handler mCameraThreadHandler;

    /* State objects guarded by mStateLock */
    private final Object mStateLock = new Object();
    private boolean mSessionOpening;
    private boolean mStopping;
    private int mFailureCount;
    private int mCameraIdx = 0;
    private int mWidth;
    private int mHeight;
    private int mFramerate;
    private CameraDevice mCamera;
    private Surface mSurface;
    private CaptureRequest.Builder mRequestBuilder;
    private CaptureSession mCaptureSession;
    private boolean mFirstFrameObserved;
    private int mZoom = 0;
    private boolean mTorch = false;

    IristickCapturer(Headset headset, CameraEventsHandler eventsHandler) {
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
        mCameraNames = headset.getCameraIdList();
    }

    @Override
    public void initialize(SurfaceTextureHelper surfaceTextureHelper, Context applicationContext,
                           CapturerObserver capturerObserver) {
        mSurfaceHelper = surfaceTextureHelper;
        mAppContext = applicationContext;
        mObserver = capturerObserver;
        mCameraThreadHandler = surfaceTextureHelper.getHandler();
    }

    @Override
    public void startCapture(int width, int height, int framerate) {
        Logging.d(TAG, "startCapture: " + width + "x" + height + "@" + framerate);

        if (mAppContext == null)
            throw new IllegalStateException("CameraCapturer must be initialized before calling startCapture");

        synchronized (mStateLock) {
            if (mSessionOpening || mCaptureSession != null) {
                Logging.w(TAG, "Capture already started");
                return;
            }

            mWidth = width;
            mHeight = height;
            mFramerate = framerate;

            openCamera(true);
        }
    }

    @Override
    public void stopCapture() {
        Logging.d(TAG, "stopCapture");

        synchronized (mStateLock) {
            mStopping = true;
            while (mSessionOpening) {
                Logging.d(TAG, "stopCapture: Waiting for session to open");
                try {
                    mStateLock.wait();
                } catch (InterruptedException e) {
                    Logging.w(TAG, "stopCapture: Interrupted while waiting for session to open");
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            if (mCaptureSession != null) {
                closeCamera();
                mObserver.onCapturerStopped();
            } else {
                Logging.d(TAG, "stopCapture: No session open");
            }
            mStopping = false;
        }

        Logging.d(TAG, "stopCapture: Done");
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

    public int getZoom() {
        return mZoom;
    }

    public void setZoom(final int zoom) {
        mCameraThreadHandler.post(() -> {
            synchronized (mStateLock) {
                mZoom = Math.max(0, Math.min(mCameraNames.length >= 2 ? 3 : 0, zoom));
                applyParametersInternal();
            }
        });
    }

    public boolean getTroch() {
        return mTorch;
    }

    public void setTorch(final boolean torch) {
        mCameraThreadHandler.post(() -> {
            synchronized (mStateLock) {
                mTorch = torch;
                applyParametersInternal();
            }
        });
    }

    public void triggerAF() {
        mCameraThreadHandler.post(this::triggerAFInternal);
    }

    private void openCamera(boolean resetFailures) {
        synchronized (mStateLock) {
            if (resetFailures)
                mFailureCount = 0;
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
                    camera.close();
                } catch (IllegalStateException e) {
                    // ignore
                }
                surface.release();
            });
            mCaptureSession = null;
            mSurface = null;
            mCamera = null;
        }
    }

    private void checkIsOnCameraThread() {
        if(Thread.currentThread() != mCameraThreadHandler.getLooper().getThread()) {
            Logging.e(TAG, "Check is on camera thread failed.");
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

    private void applyParametersInternal() {
        Logging.d(TAG, "applyParametersInternal");
        checkIsOnCameraThread();
        synchronized (mStateLock) {
            if (mSessionOpening || mStopping || mCaptureSession == null)
                return;

            if ((mZoom == 0 && mCameraIdx != 0) || (mZoom > 0 && mCameraIdx != 1)) {
                Logging.d(TAG, "Switching cameras");
                closeCamera();
                mCameraIdx = (mCameraIdx + 1) % 2;
                mFailureCount = 0;
                openCamera(true);
            } else {
                mRequestBuilder.set(CaptureRequest.SCALER_ZOOM, (float)(1 << Math.max(0, mZoom - 1)));
                mRequestBuilder.set(CaptureRequest.FLASH_MODE, mTorch ? CaptureRequest.FLASH_MODE_ON : CaptureRequest.FLASH_MODE_OFF);
                if (mCameraIdx == 1)
                    mRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                mCaptureSession.setRepeatingRequest(mRequestBuilder.build(), null, null);
            }
        }
    }

    private void triggerAFInternal() {
        Logging.d(TAG, "triggerAFInternal");
        checkIsOnCameraThread();
        synchronized (mStateLock) {
            if (mCaptureSession != null && mCameraIdx == 1) {
                mRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                CaptureRequest request = mRequestBuilder.build();
                mRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                mCaptureSession.capture(request, null, null);
            }
        }
    }

    /* Called on camera thread */
    private CameraDevice.Listener mCameraListener = new CameraDevice.Listener() {
        @Override
        public void onOpened(CameraDevice device) {
            checkIsOnCameraThread();
            synchronized (mStateLock) {
                mCamera = device;

                final SurfaceTexture surfaceTexture = mSurfaceHelper.getSurfaceTexture();
                surfaceTexture.setDefaultBufferSize(mWidth, mHeight);
                mSurface = new Surface(surfaceTexture);

                mRequestBuilder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                mRequestBuilder.addTarget(mSurface);
                mRequestBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, 1000000000L / mFramerate);

                List<Surface> outputs = new ArrayList<>();
                outputs.add(mSurface);
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
                    Logging.w(TAG, "onDisconnected from another CameraDevice");
            }
        }

        @Override
        public void onError(CameraDevice device, int error) {
            checkIsOnCameraThread();
            synchronized (mStateLock) {
                if (mCamera == device || mCamera == null)
                    handleFailure("Camera device error " + error);
                else
                    Logging.w(TAG, "onError from another CameraDevice");
            }
        }
    };

    private CaptureSession.Listener mCaptureSessionListener = new CaptureSession.Listener() {
        @Override
        public void onConfigured(CaptureSession session) {
            checkIsOnCameraThread();
            synchronized (mStateLock) {
                mSurfaceHelper.startListening(mFrameAvailableListener);
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
        public void onConfigureFailed(CaptureSession session) {
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

    private SurfaceTextureHelper.OnTextureFrameAvailableListener mFrameAvailableListener = new SurfaceTextureHelper.OnTextureFrameAvailableListener() {
        @Override
        public void onTextureFrameAvailable(int oesTextureId, float[] transformMatrix, long timestampNs) {
            checkIsOnCameraThread();
            synchronized (mStateLock) {
                if (mCaptureSession == null) {
                    mSurfaceHelper.returnTextureFrame();
                } else {
                    if (!mFirstFrameObserved) {
                        mEvents.onFirstFrameAvailable();
                        mFirstFrameObserved = true;
                    }
                    VideoFrame.Buffer buffer = mSurfaceHelper.createTextureBuffer(mWidth, mHeight,
                            RendererCommon.convertMatrixToAndroidGraphicsMatrix(transformMatrix));
                    final VideoFrame frame = new VideoFrame(buffer, 0, timestampNs);
                    mObserver.onFrameCaptured(frame);
                    frame.release();
                }
            }
        }
    };
}
