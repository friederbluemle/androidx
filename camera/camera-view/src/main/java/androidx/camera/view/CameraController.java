/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.camera.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.experimental.UseExperimental;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraUnavailableException;
import androidx.camera.core.ExperimentalUseCaseGroup;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.InitializationException;
import androidx.camera.core.Logger;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.core.TorchState;
import androidx.camera.core.UseCase;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.VideoCapture;
import androidx.camera.core.ViewPort;
import androidx.camera.core.ZoomState;
import androidx.camera.core.impl.utils.Threads;
import androidx.camera.core.impl.utils.executor.CameraXExecutors;
import androidx.camera.core.impl.utils.futures.Futures;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.video.ExperimentalVideo;
import androidx.camera.view.video.OnVideoSavedCallback;
import androidx.camera.view.video.OutputFileOptions;
import androidx.camera.view.video.OutputFileResults;
import androidx.core.util.Preconditions;
import androidx.lifecycle.LiveData;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The abstract base camera controller class.
 *
 * <p> This a high level controller that provides most of the CameraX core features
 * in a single class. It handles camera initialization, creates and configures {@link UseCase}s.
 * It also listens to device motion sensor and set the target rotation for the use cases.
 *
 * <p> The controller is required to be used with a {@link PreviewView}. {@link PreviewView}
 * provides the UI elements to display camera preview. The layout of the {@link PreviewView} is
 * used to set the crop rect so the output from other use cases matches the preview display in a
 * WYSIWYG way. The controller also listens to {@link PreviewView}'s touch events to handle
 * tap-to-focus and pinch-to-zoom features.
 *
 * <p> This class provides features of 4 {@link UseCase}s: {@link Preview}, {@link ImageCapture},
 * {@link ImageAnalysis} and video capture. {@link Preview} is required and always enabled.
 * {@link ImageCapture} and {@link ImageAnalysis} are enabled by default. Video capture is
 * disabled by default because it might conflict with other use cases, especially on lower end
 * devices. It might be necessary to disable {@link ImageCapture} and/or {@link ImageAnalysis}
 * before the video feature can be enabled. Disabling/enabling {@link UseCase}s freezes the
 * preview for a short period of time. To avoid the glitch, the {@link UseCase}s need to be
 * enabled/disabled before the controller is set on {@link PreviewView}.
 */
public abstract class CameraController {

    private static final String TAG = "CameraController";

    // Externally visible error messages.
    private static final String CAMERA_NOT_INITIALIZED = "Camera not initialized.";
    private static final String CAMERA_NOT_ATTACHED = "Use cases not attached to camera.";
    private static final String IMAGE_CAPTURE_DISABLED = "ImageCapture disabled.";
    private static final String VIDEO_CAPTURE_DISABLED = "VideoCapture disabled.";

    // Auto focus is 1/6 of the area.
    private static final float AF_SIZE = 1.0f / 6.0f;
    private static final float AE_SIZE = AF_SIZE * 1.5f;

    CameraSelector mCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

    // CameraController and PreviewView hold reference to each other. The 2-way link is managed
    // by PreviewView.
    // Synthetic access
    @SuppressWarnings("WeakerAccess")
    @NonNull
    final Preview mPreview;

    // Synthetic access
    @SuppressWarnings("WeakerAccess")
    @NonNull
    final ImageCapture mImageCapture;

    // ImageCapture is enabled by default.
    private boolean mImageCaptureEnabled = true;

    // ImageAnalysis is enabled by default.
    private boolean mImageAnalysisEnabled = true;

    @Nullable
    private Executor mAnalysisExecutor;

    @Nullable
    private ImageAnalysis.Analyzer mAnalysisAnalyzer;

    @NonNull
    private ImageAnalysis mImageAnalysis;

    // Synthetic access
    @SuppressWarnings("WeakerAccess")
    @NonNull
    final VideoCapture mVideoCapture;

    // VideoCapture is disabled by default.
    private boolean mVideoCaptureEnabled = false;

    // Synthetic access
    @SuppressWarnings("WeakerAccess")
    @NonNull
    final AtomicBoolean mVideoIsRecording = new AtomicBoolean(false);

    // The latest bound camera.
    // Synthetic access
    @SuppressWarnings("WeakerAccess")
    @Nullable
    Camera mCamera;

    // Synthetic access
    @SuppressWarnings("WeakerAccess")
    @Nullable
    ProcessCameraProvider mCameraProvider;

    // Synthetic access
    @SuppressWarnings("WeakerAccess")
    @Nullable
    ViewPort mViewPort;

    // Synthetic access
    @SuppressWarnings("WeakerAccess")
    @Nullable
    Preview.SurfaceProvider mPreviewViewSurfaceProvider;

    // A wrapper around the PreviewView's SurfaceProvider that allows binding before PreviewView
    // is ready.
    @NonNull
    Preview.SurfaceProvider mForwardingSurfaceProvider = request -> {
        if (mPreviewViewSurfaceProvider != null) {
            mPreviewViewSurfaceProvider.onSurfaceRequested(request);
        }
    };

    // Synthetic access
    @SuppressWarnings("WeakerAccess")
    @Nullable
    Display mPreviewDisplay;

    // Synthetic access
    @SuppressWarnings("WeakerAccess")
    @NonNull
    final SensorRotationListener mSensorRotationListener;

    @Nullable
    private final DisplayRotationListener mDisplayRotationListener;

    private boolean mPinchToZoomEnabled = true;
    private boolean mTapToFocusEnabled = true;

    private final ForwardingLiveData<ZoomState> mZoomState = new ForwardingLiveData<>();
    private final ForwardingLiveData<Integer> mTorchState = new ForwardingLiveData<>();

    private final Context mAppContext;

    @NonNull
    private final ListenableFuture<Void> mInitializationFuture;

    CameraController(@NonNull Context context) {
        mAppContext = context.getApplicationContext();
        mPreview = new Preview.Builder().build();
        mImageCapture = new ImageCapture.Builder().build();
        mImageAnalysis = new ImageAnalysis.Builder().build();
        mVideoCapture = new VideoCapture.Builder().build();

        // Wait for camera to be initialized before binding use cases.
        mInitializationFuture = Futures.transform(
                ProcessCameraProvider.getInstance(mAppContext),
                provider -> {
                    mCameraProvider = provider;
                    mPreview.setSurfaceProvider(mForwardingSurfaceProvider);
                    startCameraAndTrackStates();
                    return null;
                }, CameraXExecutors.mainThreadExecutor());

        // Listen to display rotation and set target rotation for Preview.
        mDisplayRotationListener = new DisplayRotationListener();

        // Listen to motion sensor reading and set target rotation for ImageCapture and
        // VideoCapture.
        mSensorRotationListener = new SensorRotationListener(mAppContext) {
            @Override
            public void onRotationChanged(int rotation) {
                mImageCapture.setTargetRotation(rotation);
                mVideoCapture.setTargetRotation(rotation);
            }
        };
    }

    /**
     * Gets a {@link ListenableFuture} that completes when camera initialization completes and
     * use cases are attached.
     *
     * <p> This future may fail with an {@link InitializationException} and associated cause that
     * can be retrieved by {@link Throwable#getCause()). The cause will be a
     * {@link CameraUnavailableException} if it fails to access any camera during initialization.
     *
     * <p> In the rare case that the future fails with {@link CameraUnavailableException}, the
     * camera will become unusable. This could happen for various reasons, for example hardware
     * failure or the camera being held by another process. If the failure is temporary, killing
     * and restarting the app might fix the issue.
     *
     * @see ProcessCameraProvider#getInstance
     */
    @NonNull
    public ListenableFuture<Void> getInitializationFuture() {
        return mInitializationFuture;
    }

    /**
     * Implemented by children to refresh after {@link UseCase} is changed.
     */
    @Nullable
    abstract Camera startCamera();

    private boolean isCameraInitialized() {
        return mCameraProvider != null;
    }

    private boolean isCameraAttached() {
        return mCamera != null;
    }

    // ------------------
    // Preview use case.
    // ------------------

    /**
     * Internal API used by {@link PreviewView} to notify changes.
     */
    @SuppressLint({"MissingPermission", "WrongConstant"})
    @MainThread
    @UseExperimental(markerClass = ExperimentalUseCaseGroup.class)
    void attachPreviewSurface(@NonNull Preview.SurfaceProvider surfaceProvider,
            @NonNull ViewPort viewPort, @NonNull Display display) {
        Threads.checkMainThread();
        if (mPreviewViewSurfaceProvider != surfaceProvider) {
            mPreviewViewSurfaceProvider = surfaceProvider;
        }
        mViewPort = viewPort;
        mPreviewDisplay = display;
        startListeningToRotationEvents();
        startCameraAndTrackStates();
    }

    /**
     * Clear {@link PreviewView} to remove the UI reference.
     */
    @MainThread
    void clearPreviewSurface() {
        Threads.checkMainThread();
        if (mCameraProvider != null) {
            // Preview is required. Unbind everything if Preview is down.
            mCameraProvider.unbindAll();
        }
        mCamera = null;
        mPreviewViewSurfaceProvider = null;
        mViewPort = null;
        mPreviewDisplay = null;
        stopListeningToRotationEvents();
    }

    private void startListeningToRotationEvents() {
        getDisplayManager().registerDisplayListener(mDisplayRotationListener,
                new Handler(Looper.getMainLooper()));
        if (mSensorRotationListener.canDetectOrientation()) {
            mSensorRotationListener.enable();
        }
    }

    private void stopListeningToRotationEvents() {
        getDisplayManager().unregisterDisplayListener(mDisplayRotationListener);
        mSensorRotationListener.disable();
    }

    private DisplayManager getDisplayManager() {
        return (DisplayManager) mAppContext.getSystemService(Context.DISPLAY_SERVICE);
    }

    // ----------------------
    // ImageCapture UseCase.
    // ----------------------

    /**
     * Checks if {@link ImageCapture} is enabled.
     *
     * <p> {@link ImageCapture} is enabled by default. It has to be enabled before
     * {@link #takePicture} can be called.
     *
     * @see ImageCapture
     */
    @MainThread
    public boolean isImageCaptureEnabled() {
        Threads.checkMainThread();
        return mImageCaptureEnabled;
    }

    /**
     * Enables or disables {@link ImageCapture}.
     *
     * <p> {@link ImageCapture} might not work with video capture depending on device
     * limitations. In that case, the method will fail with {@link IllegalStateException}.
     *
     * @throws IllegalArgumentException If the current camera selector is unable to resolve a
     *                                  camera to be used for the enabled use cases.
     * @see ImageCapture
     */
    @MainThread
    public void setImageCaptureEnabled(boolean imageCaptureEnabled) {
        Threads.checkMainThread();
        if (mImageCaptureEnabled == imageCaptureEnabled) {
            return;
        }
        boolean oldImageCaptureEnabled = mImageCaptureEnabled;
        mImageCaptureEnabled = imageCaptureEnabled;
        startCameraAndTrackStates(() -> mImageAnalysisEnabled = oldImageCaptureEnabled);
    }

    /**
     * Gets the flash mode for {@link ImageCapture}.
     *
     * @return the flashMode. Value is {@link ImageCapture#FLASH_MODE_AUTO},
     * {@link ImageCapture#FLASH_MODE_ON}, or {@link ImageCapture#FLASH_MODE_OFF}.
     * @see ImageCapture
     */
    @MainThread
    @ImageCapture.FlashMode
    public int getImageCaptureFlashMode() {
        Threads.checkMainThread();
        return mImageCapture.getFlashMode();
    }

    /**
     * Sets the flash mode for {@link ImageCapture}.
     *
     * <p>If not set, the flash mode will default to {@link ImageCapture#FLASH_MODE_OFF}.
     *
     * @param flashMode the flash mode for {@link ImageCapture}.
     */
    @MainThread
    public void setImageCaptureFlashMode(@ImageCapture.FlashMode int flashMode) {
        Threads.checkMainThread();
        mImageCapture.setFlashMode(flashMode);
    }

    /**
     * Captures a new still image and saves to a file along with application specified metadata.
     *
     * <p> The callback will be called only once for every invocation of this method.
     *
     * <p> By default, the saved image is mirrored to match the output of the preview if front
     * camera is used. To override this behavior, the app needs to explicitly set the flag to
     * {@code false} using {@link ImageCapture.Metadata#setReversedHorizontal} and
     * {@link OutputFileOptions.Builder#setMetadata}.
     *
     * @param outputFileOptions  Options to store the newly captured image.
     * @param executor           The executor in which the callback methods will be run.
     * @param imageSavedCallback Callback to be called for the newly captured image.
     * @see ImageCapture#takePicture(
     *ImageCapture.OutputFileOptions, Executor, ImageCapture.OnImageSavedCallback)
     */
    @MainThread
    public void takePicture(
            @NonNull ImageCapture.OutputFileOptions outputFileOptions,
            @NonNull Executor executor,
            @NonNull ImageCapture.OnImageSavedCallback imageSavedCallback) {
        Threads.checkMainThread();
        Preconditions.checkState(isCameraInitialized(), CAMERA_NOT_INITIALIZED);
        Preconditions.checkState(mImageCaptureEnabled, IMAGE_CAPTURE_DISABLED);

        updateMirroringFlagInOutputFileOptions(outputFileOptions);
        mImageCapture.takePicture(outputFileOptions, executor, imageSavedCallback);
    }

    /**
     * Update {@link ImageCapture.OutputFileOptions} based on config.
     *
     * <p> Mirror the output image if front camera is used and if the flag is not set explicitly by
     * the app.
     *
     * @hide
     */
    @VisibleForTesting
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    void updateMirroringFlagInOutputFileOptions(
            @NonNull ImageCapture.OutputFileOptions outputFileOptions) {
        if (mCameraSelector.getLensFacing() != null
                && !outputFileOptions.getMetadata().isReversedHorizontalSet()) {
            outputFileOptions.getMetadata().setReversedHorizontal(
                    mCameraSelector.getLensFacing() == CameraSelector.LENS_FACING_FRONT);
        }
    }

    /**
     * Captures a new still image for in memory access.
     *
     * <p>The listener is responsible for calling {@link ImageProxy#close()} on the returned image.
     *
     * @param executor The executor in which the callback methods will be run.
     * @param callback Callback to be invoked for the newly captured image
     * @see ImageCapture#takePicture(Executor, ImageCapture.OnImageCapturedCallback)
     */
    @MainThread
    public void takePicture(
            @NonNull Executor executor,
            @NonNull ImageCapture.OnImageCapturedCallback callback) {
        Threads.checkMainThread();
        Preconditions.checkState(isCameraInitialized(), CAMERA_NOT_INITIALIZED);
        Preconditions.checkState(mImageCaptureEnabled, IMAGE_CAPTURE_DISABLED);

        mImageCapture.takePicture(executor, callback);
    }

    // -----------------
    // Image analysis
    // -----------------

    /**
     * Checks if {@link ImageAnalysis} is enabled.
     *
     * @see ImageAnalysis
     */
    @MainThread
    public boolean isImageAnalysisEnabled() {
        Threads.checkMainThread();
        return mImageAnalysisEnabled;
    }

    /**
     * Enables or disables {@link ImageAnalysis} use case.
     *
     * <p> {@link ImageAnalysis} might not work with video capture depending on device
     * limitations. In that case, the method will fail with {@link IllegalStateException}.
     *
     * @throws IllegalStateException If the current camera selector is unable to resolve a
     *                               camera to be used for the enabled use cases.
     * @see ImageAnalysis
     */
    @MainThread
    public void setImageAnalysisEnabled(boolean imageAnalysisEnabled) {
        Threads.checkMainThread();
        if (mImageAnalysisEnabled == imageAnalysisEnabled) {
            return;
        }
        boolean oldImageAnalysisEnabled = mImageAnalysisEnabled;
        mImageAnalysisEnabled = imageAnalysisEnabled;
        startCameraAndTrackStates(() -> mImageAnalysisEnabled = oldImageAnalysisEnabled);
    }

    /**
     * Sets an analyzer to receive and analyze images.
     *
     * <p>Applications can process or copy the image by implementing the
     * {@link ImageAnalysis.Analyzer}. The image needs to be closed by calling
     * {@link ImageProxy#close()} when the analyzing is done.
     *
     * <p>Setting an analyzer function replaces any previous analyzer. Only one analyzer can be
     * set at any time.
     *
     * @param executor The executor in which the
     *                 {@link ImageAnalysis.Analyzer#analyze(ImageProxy)} will be run.
     * @param analyzer of the images.
     * @see ImageAnalysis#setAnalyzer(Executor, ImageAnalysis.Analyzer)
     */
    @MainThread
    public void setImageAnalysisAnalyzer(@NonNull Executor executor,
            @NonNull ImageAnalysis.Analyzer analyzer) {
        Threads.checkMainThread();
        if (mAnalysisAnalyzer == analyzer && mAnalysisExecutor == executor) {
            return;
        }
        mAnalysisExecutor = executor;
        mAnalysisAnalyzer = analyzer;
        mImageAnalysis.setAnalyzer(executor, analyzer);
    }

    /**
     * Removes a previously set analyzer.
     *
     * <p>This will stop data from streaming to the {@link ImageAnalysis}.
     *
     * @see ImageAnalysis#clearAnalyzer().
     */
    @MainThread
    public void clearImageAnalysisAnalyzer() {
        Threads.checkMainThread();
        mAnalysisExecutor = null;
        mAnalysisAnalyzer = null;
        mImageAnalysis.clearAnalyzer();
    }

    /**
     * Returns the mode with which images are acquired.
     *
     * <p> If not set, it defaults to {@link ImageAnalysis#STRATEGY_KEEP_ONLY_LATEST}.
     *
     * @return The backpressure strategy applied to the image producer.
     * @see ImageAnalysis.Builder#getBackpressureStrategy()
     */
    @MainThread
    @ImageAnalysis.BackpressureStrategy
    public int getImageAnalysisBackpressureStrategy() {
        Threads.checkMainThread();
        return mImageAnalysis.getBackpressureStrategy();
    }

    /**
     * Sets the backpressure strategy to apply to the image producer to deal with scenarios
     * where images may be produced faster than they can be analyzed.
     *
     * <p>The available values are {@link ImageAnalysis#STRATEGY_BLOCK_PRODUCER} and
     * {@link ImageAnalysis#STRATEGY_KEEP_ONLY_LATEST}. If not set, the backpressure strategy
     * will default to {@link ImageAnalysis#STRATEGY_KEEP_ONLY_LATEST}.
     *
     * @param strategy The strategy to use.
     * @see ImageAnalysis.Builder#setBackpressureStrategy(int)
     */
    @MainThread
    public void setImageAnalysisBackpressureStrategy(
            @ImageAnalysis.BackpressureStrategy int strategy) {
        Threads.checkMainThread();
        if (mImageAnalysis.getBackpressureStrategy() == strategy) {
            return;
        }

        unbindImageAnalysisAndRecreate(strategy, mImageAnalysis.getImageQueueDepth());
        startCameraAndTrackStates();
    }

    /**
     * Sets the image queue depth of {@link ImageAnalysis}.
     *
     * <p> This sets the number of images available in parallel to {@link ImageAnalysis.Analyzer}
     * . The value is only used if the backpressure strategy is
     * {@link ImageAnalysis.BackpressureStrategy#STRATEGY_BLOCK_PRODUCER}.
     *
     * @param depth The total number of images available.
     * @see ImageAnalysis.Builder#setImageQueueDepth(int)
     */
    @MainThread
    public void setImageAnalysisImageQueueDepth(int depth) {
        Threads.checkMainThread();
        if (mImageAnalysis.getImageQueueDepth() == depth) {
            return;
        }
        unbindImageAnalysisAndRecreate(mImageAnalysis.getBackpressureStrategy(), depth);
        startCameraAndTrackStates();
    }

    /**
     * Gets the image queue depth of {@link ImageAnalysis}.
     *
     * @see ImageAnalysis#getImageQueueDepth()
     */
    @MainThread
    public int getImageAnalysisImageQueueDepth() {
        Threads.checkMainThread();
        return mImageAnalysis.getImageQueueDepth();
    }

    /**
     * Unbinds {@link ImageAnalysis} and recreates with the given parameters.
     *
     * <p> This is necessary because unlike other use cases, {@link ImageAnalysis}'s parameters
     * cannot be updated without recreating the use case.
     */
    private void unbindImageAnalysisAndRecreate(int strategy, int imageQueueDepth) {
        if (isCameraInitialized()) {
            mCameraProvider.unbind(mImageAnalysis);
        }
        mImageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(strategy)
                .setImageQueueDepth(imageQueueDepth)
                .build();
        if (mAnalysisExecutor != null && mAnalysisAnalyzer != null) {
            mImageAnalysis.setAnalyzer(mAnalysisExecutor, mAnalysisAnalyzer);
        }
    }

    // -----------------
    // Video capture
    // -----------------

    /**
     * Checks if video capture is enabled.
     *
     * <p> Video capture is disabled by default. It has to be enabled before
     * {@link #startRecording} can be called.
     */
    @ExperimentalVideo
    @MainThread
    public boolean isVideoCaptureEnabled() {
        Threads.checkMainThread();
        return mVideoCaptureEnabled;
    }

    /**
     * Enables or disables video capture use case.
     *
     * <p> Due to hardware limitations, video capture might not work when {@link ImageAnalysis}
     * and/or {@link ImageCapture} is enabled. In that case, this method will fail with an
     * {@link IllegalStateException}.
     *
     * @throws IllegalStateException If the current camera selector is unable to resolve a
     *                               camera to be used for the enabled use cases.
     */
    @ExperimentalVideo
    @MainThread
    public void setVideoCaptureEnabled(boolean videoCaptureEnabled) {
        Threads.checkMainThread();
        if (mVideoCaptureEnabled == videoCaptureEnabled) {
            return;
        }
        if (!videoCaptureEnabled) {
            stopRecording();
        }
        boolean oldVideoCaptureEnabled = mVideoCaptureEnabled;
        mVideoCaptureEnabled = videoCaptureEnabled;
        startCameraAndTrackStates(() -> mVideoCaptureEnabled = oldVideoCaptureEnabled);
    }

    /**
     * Takes a video and calls the OnVideoSavedCallback when done.
     *
     * @param outputFileOptions Options to store the newly captured video.
     * @param executor          The executor in which the callback methods will be run.
     * @param callback          Callback which will receive success or failure.
     */
    @ExperimentalVideo
    @MainThread
    public void startRecording(@NonNull OutputFileOptions outputFileOptions,
            @NonNull Executor executor, final @NonNull OnVideoSavedCallback callback) {
        Threads.checkMainThread();
        Preconditions.checkState(isCameraInitialized(), CAMERA_NOT_INITIALIZED);
        Preconditions.checkState(mVideoCaptureEnabled, VIDEO_CAPTURE_DISABLED);

        mVideoCapture.startRecording(outputFileOptions.toVideoCaptureOutputFileOptions(), executor,
                new VideoCapture.OnVideoSavedCallback() {
                    @Override
                    public void onVideoSaved(
                            @NonNull VideoCapture.OutputFileResults outputFileResults) {
                        mVideoIsRecording.set(false);
                        callback.onVideoSaved(
                                OutputFileResults.create(outputFileResults.getSavedUri()));
                    }

                    @Override
                    public void onError(int videoCaptureError, @NonNull String message,
                            @Nullable Throwable cause) {
                        mVideoIsRecording.set(false);
                        callback.onError(videoCaptureError, message, cause);
                    }
                });
        mVideoIsRecording.set(true);
    }

    /**
     * Stops a in progress video recording.
     */
    @ExperimentalVideo
    @MainThread
    public void stopRecording() {
        Threads.checkMainThread();
        if (mVideoIsRecording.get()) {
            mVideoCapture.stopRecording();
        }
    }

    /**
     * Returns whether there is a in progress video recording.
     */
    @ExperimentalVideo
    @MainThread
    public boolean isRecording() {
        Threads.checkMainThread();
        return mVideoIsRecording.get();
    }

    // -----------------
    // Camera control
    // -----------------

    /**
     * Sets the {@link CameraSelector}.
     *
     * <p> Calling this method with a {@link CameraSelector} that resolves to a different camera
     * will change the camera being used by the controller.
     *
     * <p>The default value is{@link CameraSelector#DEFAULT_BACK_CAMERA}.
     *
     * @throws IllegalStateException If the provided camera selector is unable to resolve a
     *                               camera to be used for the enabled use cases.
     * @see CameraSelector
     */
    @MainThread
    public void setCameraSelector(@NonNull CameraSelector cameraSelector) {
        Threads.checkMainThread();
        if (mCameraSelector == cameraSelector) {
            return;
        }

        if (mCameraProvider == null) {
            return;
        }
        mCameraProvider.unbindAll();

        CameraSelector oldCameraSelector = mCameraSelector;
        mCameraSelector = cameraSelector;
        startCameraAndTrackStates(() -> mCameraSelector = oldCameraSelector);
    }

    /**
     * Gets the {@link CameraSelector}.
     *
     * <p>The default value is{@link CameraSelector#DEFAULT_BACK_CAMERA}.
     *
     * @see CameraSelector
     */
    @NonNull
    @MainThread
    public CameraSelector getCameraSelector() {
        Threads.checkMainThread();
        return mCameraSelector;
    }

    /**
     * Returns whether pinch-to-zoom is enabled.
     *
     * <p> By default pinch-to-zoom is enabled.
     *
     * @return True if pinch-to-zoom is enabled.
     */
    @MainThread
    public boolean isPinchToZoomEnabled() {
        Threads.checkMainThread();
        return mPinchToZoomEnabled;
    }

    /**
     * Enables/disables pinch-to-zoom.
     *
     * <p>Once enabled, end user can pinch on the {@link PreviewView} to zoom in/out if the bound
     * camera supports zooming.
     *
     * @param enabled True to enable pinch-to-zoom.
     */
    @MainThread
    public void setPinchToZoomEnabled(boolean enabled) {
        Threads.checkMainThread();
        mPinchToZoomEnabled = enabled;
    }

    /**
     * Called by {@link PreviewView} for a pinch-to-zoom event.
     */
    @SuppressWarnings("FutureReturnValueIgnored")
    void onPinchToZoom(float pinchToZoomScale) {
        if (!isCameraAttached()) {
            Logger.w(TAG, CAMERA_NOT_ATTACHED);
            return;
        }
        if (!mPinchToZoomEnabled) {
            Logger.d(TAG, "Pinch to zoom disabled.");
            return;
        }
        Logger.d(TAG, "Pinch to zoom with scale: " + pinchToZoomScale);

        ZoomState zoomState = getZoomState().getValue();
        if (zoomState == null) {
            return;
        }
        float clampedRatio = zoomState.getZoomRatio() * speedUpZoomBy2X(pinchToZoomScale);
        // Clamp the ratio with the zoom range.
        clampedRatio = Math.min(Math.max(clampedRatio, zoomState.getMinZoomRatio()),
                zoomState.getMaxZoomRatio());
        setZoomRatio(clampedRatio);
    }

    private float speedUpZoomBy2X(float scaleFactor) {
        if (scaleFactor > 1f) {
            return 1.0f + (scaleFactor - 1.0f) * 2;
        } else {
            return 1.0f - (1.0f - scaleFactor) * 2;
        }
    }

    /**
     * Called by {@link PreviewView} for a tap-to-focus event.
     */
    @SuppressWarnings("FutureReturnValueIgnored")
    void onTapToFocus(MeteringPointFactory meteringPointFactory, float x, float y) {
        if (!isCameraAttached()) {
            Logger.w(TAG, CAMERA_NOT_ATTACHED);
            return;
        }
        if (!mTapToFocusEnabled) {
            Logger.d(TAG, "Tap to focus disabled. ");
            return;
        }
        Logger.d(TAG, "Tap to focus: " + x + ", " + y);
        MeteringPoint afPoint = meteringPointFactory.createPoint(x, y, AF_SIZE);
        MeteringPoint aePoint = meteringPointFactory.createPoint(x, y, AE_SIZE);
        mCamera.getCameraControl().startFocusAndMetering(new FocusMeteringAction
                .Builder(afPoint, FocusMeteringAction.FLAG_AF)
                .addPoint(aePoint, FocusMeteringAction.FLAG_AE)
                .build());
    }

    /**
     * Returns whether tap-to-focus is enabled.
     *
     * <p> By default tap-to-focus is enabled.
     *
     * @return True if tap-to-focus is enabled.
     */
    @MainThread
    public boolean isTapToFocusEnabled() {
        Threads.checkMainThread();
        return mTapToFocusEnabled;
    }

    /**
     * Enables/disables tap-to-focus.
     *
     * <p>Once enabled, end user can tap on the {@link PreviewView} to set focus point.
     *
     * @param enabled True to enable tap-to-focus.
     */
    @MainThread
    public void setTapToFocusEnabled(boolean enabled) {
        Threads.checkMainThread();
        mTapToFocusEnabled = enabled;
    }

    /**
     * Returns a {@link LiveData} of {@link ZoomState}.
     *
     * <p>The LiveData will be updated whenever the set zoom state has been changed. This can
     * occur when the application updates the zoom via {@link #setZoomRatio(float)}
     * or {@link #setLinearZoom(float)}. The zoom state can also change anytime a
     * camera starts up, for example when {@link #setCameraSelector} is called.
     *
     * @see CameraInfo#getZoomState()
     */
    @NonNull
    @MainThread
    public LiveData<ZoomState> getZoomState() {
        Threads.checkMainThread();
        return mZoomState;
    }

    /**
     * Sets current zoom by ratio.
     *
     * <p>Valid zoom values range from {@link ZoomState#getMinZoomRatio()} to
     * {@link ZoomState#getMaxZoomRatio()}.
     *
     * <p> No-ops if the camera is not ready. The {@link ListenableFuture} completes successfully
     * in this case.
     *
     * @param zoomRatio The requested zoom ratio.
     * @return a {@link ListenableFuture} which is finished when camera is set to the given ratio.
     * It fails with {@link CameraControl.OperationCanceledException} if there is newer value
     * being set or camera is closed. If the ratio is out of range, it fails with
     * {@link IllegalArgumentException}. Cancellation of this future is a no-op.
     * @see #getZoomState()
     * @see CameraControl#setZoomRatio(float)
     */
    @NonNull
    @MainThread
    public ListenableFuture<Void> setZoomRatio(float zoomRatio) {
        Threads.checkMainThread();
        if (!isCameraAttached()) {
            Logger.w(TAG, CAMERA_NOT_ATTACHED);
            return Futures.immediateFuture(null);
        }
        return mCamera.getCameraControl().setZoomRatio(zoomRatio);
    }

    /**
     * Sets current zoom by a linear zoom value ranging from 0f to 1.0f.
     *
     * LinearZoom 0f represents the minimum zoom while linearZoom 1.0f represents the maximum
     * zoom. The advantage of linearZoom is that it ensures the field of view (FOV) varies
     * linearly with the linearZoom value, for use with slider UI elements (while
     * {@link #setZoomRatio(float)} works well for pinch-zoom gestures).
     *
     * <p> No-ops if the camera is not ready. The {@link ListenableFuture} completes successfully
     * in this case.
     *
     * @return a {@link ListenableFuture} which is finished when camera is set to the given ratio.
     * It fails with {@link CameraControl.OperationCanceledException} if there is newer value
     * being set or camera is closed. If the ratio is out of range, it fails with
     * {@link IllegalArgumentException}. Cancellation of this future is a no-op.
     * @see CameraControl#setLinearZoom(float)
     */
    @NonNull
    @MainThread
    public ListenableFuture<Void> setLinearZoom(float linearZoom) {
        Threads.checkMainThread();
        if (!isCameraAttached()) {
            Logger.w(TAG, CAMERA_NOT_ATTACHED);
            return Futures.immediateFuture(null);
        }
        return mCamera.getCameraControl().setLinearZoom(linearZoom);
    }

    /**
     * Returns a {@link LiveData} of current {@link TorchState}.
     *
     * <p>The torch can be turned on and off via {@link #enableTorch(boolean)} which
     * will trigger the change event to the returned {@link LiveData}.
     *
     * @return a {@link LiveData} containing current torch state.
     * @see CameraInfo#getTorchState()
     */
    @NonNull
    @MainThread
    public LiveData<Integer> getTorchState() {
        Threads.checkMainThread();
        return mTorchState;
    }

    /**
     * Enable the torch or disable the torch.
     *
     * <p> No-ops if the camera is not ready. The {@link ListenableFuture} completes successfully
     * in this case.
     *
     * @param torchEnabled true to turn on the torch, false to turn it off.
     * @return A {@link ListenableFuture} which is successful when the torch was changed to the
     * value specified. It fails when it is unable to change the torch state. Cancellation of
     * this future is a no-op.
     * @see CameraControl#enableTorch(boolean)
     */
    @NonNull
    @MainThread
    public ListenableFuture<Void> enableTorch(boolean torchEnabled) {
        Threads.checkMainThread();
        if (!isCameraAttached()) {
            Logger.w(TAG, CAMERA_NOT_ATTACHED);
            return Futures.immediateFuture(null);
        }
        return mCamera.getCameraControl().enableTorch(torchEnabled);
    }

    /**
     * Binds use cases, gets a new {@link Camera} instance and tracks the state of the camera.
     */
    void startCameraAndTrackStates() {
        startCameraAndTrackStates(null);
    }

    /**
     * @param restoreStateRunnable runnable to restore the controller to the previous good state if
     *                             the binding fails.
     * @throws IllegalStateException if binding fails.
     */
    void startCameraAndTrackStates(@Nullable Runnable restoreStateRunnable) {
        try {
            mCamera = startCamera();
        } catch (IllegalArgumentException exception) {
            if (restoreStateRunnable != null) {
                restoreStateRunnable.run();
            }
            // Catches the core exception and throw a more readable one.
            String errorMessage =
                    "The selected camera does not support the enabled use cases. Please "
                            + "disable use case and/or select a different camera. e.g. "
                            + "#setVideoCaptureEnabled(false)";
            throw new IllegalStateException(errorMessage, exception);
        }
        if (!isCameraAttached()) {
            Logger.d(TAG, CAMERA_NOT_ATTACHED);
            return;
        }
        mZoomState.setSource(mCamera.getCameraInfo().getZoomState());
        mTorchState.setSource(mCamera.getCameraInfo().getTorchState());
    }

    /**
     * Creates {@link UseCaseGroup} from all the use cases.
     *
     * <p> Preview is required. If it is null, then controller is not ready. Return null and ignore
     * other use cases.
     *
     * @hide
     */
    @Nullable
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    @UseExperimental(markerClass = ExperimentalUseCaseGroup.class)
    protected UseCaseGroup createUseCaseGroup() {
        if (!isCameraInitialized()) {
            Logger.d(TAG, CAMERA_NOT_INITIALIZED);
            return null;
        }

        UseCaseGroup.Builder builder = new UseCaseGroup.Builder().addUseCase(mPreview);

        if (mImageCaptureEnabled) {
            builder.addUseCase(mImageCapture);
        } else {
            mCameraProvider.unbind(mImageCapture);
        }

        if (mImageAnalysisEnabled) {
            builder.addUseCase(mImageAnalysis);
        } else {
            mCameraProvider.unbind(mImageAnalysis);
        }

        if (mVideoCaptureEnabled) {
            builder.addUseCase(mVideoCapture);
        } else {
            mCameraProvider.unbind(mVideoCapture);
        }

        if (mViewPort != null) {
            builder.setViewPort(mViewPort);
        }
        return builder.build();
    }

    /**
     * Listener for display rotation changes.
     *
     * <p> When the device is rotated 180° from side to side, the activity is not
     * destroyed and recreated, thus {@link #attachPreviewSurface} will not be invoked. This
     * class is necessary to make sure preview's target rotation gets updated when that happens.
     */
    // Synthetic access
    @SuppressWarnings("WeakerAccess")
    class DisplayRotationListener implements DisplayManager.DisplayListener {

        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @SuppressLint("WrongConstant")
        @Override
        @UseExperimental(markerClass = ExperimentalUseCaseGroup.class)
        public void onDisplayChanged(int displayId) {
            if (mPreviewDisplay != null && mPreviewDisplay.getDisplayId() == displayId) {
                mPreview.setTargetRotation(mPreviewDisplay.getRotation());
            }
        }
    }
}
