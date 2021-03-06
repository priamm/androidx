/*
 * Copyright 2021 The Android Open Source Project
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

package androidx.car.app.aaos;

import static android.content.pm.PackageManager.NameNotFoundException;

import static java.util.Objects.requireNonNull;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.car.app.aaos.renderer.IBackButtonListener;
import androidx.car.app.aaos.renderer.ICarAppActivity;
import androidx.car.app.aaos.renderer.IInputConnectionListener;
import androidx.car.app.aaos.renderer.ILifecycleListener;
import androidx.car.app.aaos.renderer.IRendererService;
import androidx.car.app.aaos.renderer.IRotaryEventListener;
import androidx.car.app.aaos.renderer.surface.ISurfaceListener;
import androidx.car.app.aaos.renderer.surface.SurfaceHolderListener;
import androidx.car.app.aaos.renderer.surface.SurfacePackageCompat;
import androidx.car.app.aaos.renderer.surface.SurfaceWrapperProvider;
import androidx.car.app.aaos.renderer.surface.TemplateSurfaceView;
import androidx.car.app.utils.ThreadUtils;

import java.util.List;

/**
 * The class representing all the car app activities. This class is responsible for binding to the
 * host and rendering the content given by the car app service.
 *
 * <p> The apps that wish to show their content in AAOS, should define an activity-alias for the
 * {@link  CarAppActivity} and provide the car app service associated with the activity using a
 * metadata tag.
 */
//TODO(b/179146927) update javadoc
@SuppressLint({"ForbiddenSuperClass"})
public final class CarAppActivity extends Activity {
    @VisibleForTesting
    static final String SERVICE_METADATA_KEY = "car-app-service";
    private static final String TAG = "TemplateActivity";

    // TODO(b/177448399): Update after service intent action is added to car-lib.
    @SuppressLint({"ActionValue"})
    @VisibleForTesting
    static final String ACTION_RENDER = "android.car.template.host.action.RENDER";

    private ComponentName mServiceComponentName;
    TemplateSurfaceView mSurfaceView;
    SurfaceHolderListener mSurfaceHolderListener;
    ActivityLifecycleDelegate mActivityLifecycleDelegate;
    @Nullable
    IBackButtonListener mBackButtonListener;
    @Nullable
    IRendererService mRendererService;
    private int mDisplayId;

    /**
     * {@link ICarAppActivity} implementation that allows the {@link IRendererService} to
     * communicate with this {@link CarAppActivity}.
     */
    private final ICarAppActivity.Stub mCarActivity = new ICarAppActivity.Stub() {
        @Override
        public void setSurfacePackage(@NonNull SurfacePackageCompat surfacePackage) {
            requireNonNull(surfacePackage);
            ThreadUtils.runOnMain(() -> mSurfaceView.setSurfacePackage(surfacePackage));
        }

        @Override
        public void setSurfaceListener(@NonNull ISurfaceListener listener) {
            requireNonNull(listener);
            ThreadUtils.runOnMain(() -> mSurfaceHolderListener.setSurfaceListener(listener));
        }

        @Override
        public void setLifecycleListener(@NonNull ILifecycleListener listener) {
            requireNonNull(listener);
            ThreadUtils.runOnMain(() -> mActivityLifecycleDelegate.setLifecycleListener(listener));
        }

        @Override
        public void setBackButtonListener(@NonNull IBackButtonListener listener) {
            mBackButtonListener = requireNonNull(listener);
            mSurfaceView.setBackButtonListener(listener);
        }

        @Override
        public void onStartInput() {
            ThreadUtils.runOnMain(() -> mSurfaceView.onStartInput());
        }

        @Override
        public void onStopInput() {
            ThreadUtils.runOnMain(() -> mSurfaceView.onStopInput());
        }

        @Override
        public void setInputConnectionListener(@NonNull IInputConnectionListener listener) {
            mSurfaceView.setInputConnectionListener(requireNonNull(listener));
        }

        @Override
        public void setRotaryEventListener(@NonNull IRotaryEventListener listener) {
            mSurfaceView.setRotaryEventListener(requireNonNull(listener));
        }

        @Override
        public void startCarApp(@NonNull Intent intent) {
            startActivity(intent);
        }

        @Override
        public void finishCarApp() {
            finish();
        }
    };

    /** The service connection for the renderer service. */
    private ServiceConnection mServiceConnectionImpl = new ServiceConnection() {
        @Override
        public void onServiceConnected(@NonNull ComponentName name,
                @NonNull IBinder service) {
            requireNonNull(name);
            requireNonNull(service);
            IRendererService rendererService = IRendererService.Stub.asInterface(service);
            if (rendererService == null) {
                onServiceConnectionError(String.format("Failed to get IRenderService binder from "
                        + "host: %s", name.flattenToShortString()));
                return;
            }

            verifyServiceVersion(rendererService);
            initializeService(rendererService);
            updateIntent(rendererService);
            CarAppActivity.this.mRendererService = rendererService;
        }

        @Override
        public void onServiceDisconnected(@NonNull ComponentName name) {
            onServiceConnectionError(
                    String.format("Host service %s is disconnected", requireNonNull(name)));
        }
    };

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_template);

        mServiceComponentName = serviceComponentName();
        if (mServiceComponentName == null) {
            Log.e(TAG, "Unspecified service class name");
            finish();
            return;
        }

        mActivityLifecycleDelegate = new ActivityLifecycleDelegate();
        registerActivityLifecycleCallbacks(mActivityLifecycleDelegate);

        mSurfaceView = requireViewById(R.id.template_view_surface);

        // Set the z-order to receive the UI events on the surface.
        mSurfaceView.setZOrderOnTop(true);

        mSurfaceHolderListener = new SurfaceHolderListener(
                new SurfaceWrapperProvider(mSurfaceView));
        mSurfaceView.getHolder().addCallback(mSurfaceHolderListener);
        mDisplayId = getWindowManager().getDefaultDisplay().getDisplayId();
        bindService();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mSurfaceView != null) {
            mSurfaceView.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mSurfaceView != null) {
            mSurfaceView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            unbindService();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mBackButtonListener != null) {
            try {
                mBackButtonListener.onBackPressed();
            } catch (RemoteException e) {
                onServiceConnectionError(
                        "Failed to send onBackPressed event to renderer: " + e.getMessage());
            }
        }
    }

    @Override
    protected void onNewIntent(@Nullable Intent intent) {
        super.onNewIntent(intent);
        if (mRendererService == null) {
            bindService();
        } else {
            updateIntent(mRendererService);
        }
    }

    @VisibleForTesting
    ServiceConnection getServiceConnection() {
        return mServiceConnectionImpl;
    }

    @VisibleForTesting
    void setServiceConnection(ServiceConnection serviceConnection) {
        mServiceConnectionImpl = serviceConnection;
    }

    @VisibleForTesting
    int getDisplayId() {
        return mDisplayId;
    }

    @Nullable
    private ComponentName serviceComponentName() {
        ActivityInfo activityInfo = null;
        try {
            activityInfo = getPackageManager().getActivityInfo(getComponentName(),
                    PackageManager.GET_META_DATA);
        } catch (NameNotFoundException e) {
            Log.e(LogTags.TAG_AAOS_HOST, "Unable to find component: " + getComponentName(), e);
        }

        if (activityInfo == null) {
            return null;
        }

        String serviceName = activityInfo.metaData.getString(SERVICE_METADATA_KEY);
        if (serviceName == null) {
            Log.e(LogTags.TAG_AAOS_HOST, String.format("Unable to find required metadata tag with "
                            + "name %s. App manifest must include metadata tag with name %s and "
                            + "the name of the car app service as the value",
                    SERVICE_METADATA_KEY,
                    SERVICE_METADATA_KEY));
            return null;
        }

        return new ComponentName(this, serviceName);
    }

    /** Binds to the renderer service. */
    private void bindService() {
        Intent rendererIntent = new Intent(ACTION_RENDER);
        List<ResolveInfo> resolveInfoList = getPackageManager().queryIntentServices(rendererIntent,
                PackageManager.GET_META_DATA);
        if (resolveInfoList.size() == 1) {
            rendererIntent.setPackage(resolveInfoList.get(0).serviceInfo.packageName);
        } else if (resolveInfoList.isEmpty()) {
            onServiceConnectionError("Host was not found");
            //TODO("b/161744611: Unavailable host fallback is not implemented")
        } else {

            StringBuilder logMessage = new StringBuilder("Multiple hosts found, only one is "
                    + "allowed");
            for (ResolveInfo resolveInfo : resolveInfoList) {
                logMessage.append(String.format("\nFound host %s",
                        resolveInfo.serviceInfo.packageName));
            }
            onServiceConnectionError(logMessage.toString());
            //TODO("b/177083268: Multiple hosts support is not implemented")
        }

        if (!bindService(rendererIntent, mServiceConnectionImpl, Context.BIND_AUTO_CREATE)) {
            onServiceConnectionError(
                    "Cannot bind to the renderer host with intent: " + rendererIntent);
        }
    }

    /**
     * Handles the service connection errors by unbinding from the service and finishing the
     * activity.
     *
     * @param errorMessage the error message to be shown in the logs
     */
    void onServiceConnectionError(@Nullable String errorMessage) {
        // TODO(b/171085325): Add Rendering error handling
        if (errorMessage != null) {
            Log.e(TAG, errorMessage);
        }
        // Remove the lifecycle listener since there is no need to communicate the state with
        // the host.
        mActivityLifecycleDelegate.setLifecycleListener(null);
        finish();
    }

    /**
     * Verifies that the renderer service supports the current version.
     *
     * @param rendererService the renderer service which should verify the version
     */
    void verifyServiceVersion(IRendererService rendererService) {
        // TODO(169604451) Add version support logic
    }

    /**
     * Initializes the {@code rendererService} for the current activity with {@code carActivity},
     * {@code serviceComponentName} and {@code displayId}.
     *
     * @param rendererService the renderer service that needs to be initialized
     */
    void initializeService(@NonNull IRendererService rendererService) {
        requireNonNull(rendererService);
        try {
            if (!rendererService.initialize(mCarActivity, mServiceComponentName,
                    mDisplayId)) {
                throw new IllegalArgumentException(
                        "Cannot create renderer for" + mServiceComponentName);
            }
        } catch (RemoteException e) {
            onServiceConnectionError(
                    "Failed to call onCreateActivity on renderer: " + e.getMessage());
        }
    }

    /**
     * Closes the connection to the connected {@code rendererService} if any.
     */
    private void unbindService() {
        mSurfaceView.getHolder().removeCallback(mSurfaceHolderListener);
        // If host has already disconnected, there is no need for an unbind.
        if (mRendererService == null) {
            return;
        }
        try {
            mRendererService.terminate(mServiceComponentName);
        } catch (RemoteException e) {
            // We are already unbinding (maybe because the host has already cut the connection)
            // Let's not log more errors unnecessarily.
            //TODO(179506019): Revisit calls to unbindService()
        }

        unbindService(mServiceConnectionImpl);
        mRendererService = null;
    }

    /**
     * Updates the activity intent for the {@code rendererService}.
     *
     * @param rendererService the renderer service that needs to handle the new intent
     */
    void updateIntent(@NonNull IRendererService rendererService) {
        requireNonNull(rendererService);
        Intent intent = getIntent();
        try {
            if (!rendererService.onNewIntent(intent, mServiceComponentName, mDisplayId)) {
                throw new IllegalArgumentException("Renderer cannot handle the intent: " + intent);
            }
        } catch (RemoteException e) {
            onServiceConnectionError(
                    "Failed to send new intent to renderer: " + e.getMessage());
        }
    }
}
