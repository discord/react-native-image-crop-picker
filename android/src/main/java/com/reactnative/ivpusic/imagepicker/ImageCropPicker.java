package com.reactnative.ivpusic.imagepicker;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;

import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.PromiseImpl;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.modules.core.PermissionAwareActivity;
import com.facebook.react.modules.core.PermissionListener;
import com.yalantis.ucrop.UCrop;
import com.yalantis.ucrop.UCropActivity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;


class ImageCropPicker implements ActivityEventListener {
    static final String NAME = "RNCImageCropPicker";

    private static final int IMAGE_PICKER_REQUEST = 61110;
    private static final int CAMERA_PICKER_REQUEST = 61111;
    private static final String E_ACTIVITY_DOES_NOT_EXIST = "E_ACTIVITY_DOES_NOT_EXIST";

    /**
     * These colors are special because they used to control colors of `uCrop`
     * (https://github.com/Yalantis/uCrop) that are not exposed to the public API.
     * 
     * As such we use a lifecycle callback to apply the colors to the `uCrop` activity 
     * once it is created via lifecycle callbacks.
     */
    private static String pendingCropperControlsColor = null;
    private static String pendingCropperControlsBarColor = null;
    private static String pendingCropperActiveWidgetColor = null;
    private static String pendingCropperInactiveWidgetColor = null;
    private static Application.ActivityLifecycleCallbacks ucropLifecycleCallbacks = null;

    private static final String E_PICKER_CANCELLED_KEY = "E_PICKER_CANCELLED";
    private static final String E_PICKER_CANCELLED_MSG = "User cancelled image selection";

    private static final String E_CALLBACK_ERROR = "E_CALLBACK_ERROR";
    private static final String E_FAILED_TO_SHOW_PICKER = "E_FAILED_TO_SHOW_PICKER";
    private static final String E_FAILED_TO_OPEN_CAMERA = "E_FAILED_TO_OPEN_CAMERA";
    private static final String E_NO_IMAGE_DATA_FOUND = "E_NO_IMAGE_DATA_FOUND";
    private static final String E_CAMERA_IS_NOT_AVAILABLE = "E_CAMERA_IS_NOT_AVAILABLE";
    private static final String E_CANNOT_LAUNCH_CAMERA = "E_CANNOT_LAUNCH_CAMERA";
    private static final String E_ERROR_WHILE_CLEANING_FILES = "E_ERROR_WHILE_CLEANING_FILES";
    private static final String E_LOW_MEMORY_ERROR = "E_LOW_MEMORY_ERROR";

    private static final String E_NO_LIBRARY_PERMISSION_KEY = "E_NO_LIBRARY_PERMISSION";
    private static final String E_NO_LIBRARY_PERMISSION_MSG = "User did not grant library permission.";
    private static final String E_NO_CAMERA_PERMISSION_KEY = "E_NO_CAMERA_PERMISSION";
    private static final String E_NO_CAMERA_PERMISSION_MSG = "User did not grant camera permission.";

    private String mediaType = "any";
    private boolean multiple = false;
    private boolean includeBase64 = false;
    private boolean includeExif = false;
    private boolean cropping = false;
    private boolean cropperCircleOverlay = false;
    private boolean freeStyleCropEnabled = false;
    private boolean showCropGuidelines = true;
    private boolean showCropFrame = true;
    private boolean hideBottomControls = false;
    private boolean enableRotationGesture = false;
    private boolean disableCropperColorSetters = false;
    private boolean useFrontCamera = false;
    private boolean cropperStatusBarLight = true;
    private boolean cropperNavigationBarLight = false;
    private ReadableMap options;

    private String cropperActiveWidgetColor = null;
    private String cropperInactiveWidgetColor = null;
    private String cropperToolbarColor = null;
    private String cropperToolbarTitle = null;
    private String cropperToolbarWidgetColor = null;
    private String cropperControlsColor = null;
    private String cropperControlsBarColor = null;

    private int width = 0;
    private int height = 0;

    private int maxFiles = 5;

    private Uri mCameraCaptureURI;
    private String mCurrentMediaPath;
    private ResultCollector resultCollector = new ResultCollector();
    private Compression compression = new Compression();
    private ReactApplicationContext reactContext;

    ImageCropPicker(ReactApplicationContext reactContext) {
        this.reactContext = reactContext;
        reactContext.addActivityEventListener(this);
    }

    /**
     * Registers a lifecycle callback to customize UCropActivity's controls background color.
     * This uses runtime view finding to adjust colors of drawables, backgrounds, and text views.
     * 
     * This approach is somewhat fragile and could break if `uCrop` version changes. A future 
     * improvement could be to modify `uCrop` to expose these colors to the public API.
     */
    private void registerUCropLifecycleCallback(Activity activity) {
        // Check if any custom colors need to be applied
        boolean hasCustomColors = cropperControlsColor != null 
            || cropperControlsBarColor != null
            || cropperActiveWidgetColor != null
            || cropperInactiveWidgetColor != null;
            
        if (!hasCustomColors) {
            return;
        }

        // Store the colors for the callback to use
        pendingCropperControlsColor = cropperControlsColor;
        pendingCropperControlsBarColor = cropperControlsBarColor;
        pendingCropperActiveWidgetColor = cropperActiveWidgetColor;
        pendingCropperInactiveWidgetColor = cropperInactiveWidgetColor;

        // Unregister any existing callback first
        unregisterUCropLifecycleCallback(activity);

        ucropLifecycleCallbacks = new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                // Not used - view isn't ready yet
            }

            @Override
            public void onActivityStarted(Activity activity) {
                // Not used
            }

            @Override
            public void onActivityResumed(Activity activity) {
                if (activity instanceof UCropActivity) {
                    // Post to ensure views are fully laid out
                    activity.getWindow().getDecorView().post(() -> {
                        if (pendingCropperControlsColor != null) {
                            applyControlsBackgroundColor(activity, pendingCropperControlsColor);
                        }
                        if (pendingCropperControlsBarColor != null) {
                            applyControlsBarBackgroundColor(activity, pendingCropperControlsBarColor);
                        }
                        if (pendingCropperActiveWidgetColor != null || pendingCropperInactiveWidgetColor != null) {
                            applyWidgetColors(activity, pendingCropperActiveWidgetColor, pendingCropperInactiveWidgetColor);
                        }
                    });
                }
            }

            @Override
            public void onActivityPaused(Activity activity) {
                // Not used
            }

            @Override
            public void onActivityStopped(Activity activity) {
                // Not used
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                // Not used
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
                if (activity instanceof UCropActivity) {
                    // Clean up when UCropActivity is destroyed
                    pendingCropperControlsColor = null;
                    pendingCropperControlsBarColor = null;
                    pendingCropperActiveWidgetColor = null;
                    pendingCropperInactiveWidgetColor = null;
                    unregisterUCropLifecycleCallback(activity);
                }
            }
        };

        activity.getApplication().registerActivityLifecycleCallbacks(ucropLifecycleCallbacks);
    }

    /**
     * Unregisters the UCrop lifecycle callback.
     */
    private void unregisterUCropLifecycleCallback(Activity activity) {
        if (ucropLifecycleCallbacks != null && activity != null) {
            activity.getApplication().unregisterActivityLifecycleCallbacks(ucropLifecycleCallbacks);
            ucropLifecycleCallbacks = null;
        }
    }

    /**
     * Applies the custom background color to UCrop's controls wrapper.
     * Finds the wrapper_controls view and replaces its child ImageView's background
     * with a new GradientDrawable that has the custom color and rounded top corners.
     */
    private void applyControlsBackgroundColor(Activity activity, String colorString) {
        try {
            Resources resources = activity.getResources();
            
            // Use UCrop's R class directly to get the resource ID
            int wrapperControlsId = com.yalantis.ucrop.R.id.wrapper_controls;

            View wrapperControls = activity.findViewById(wrapperControlsId);
            if (wrapperControls == null) {
                Log.w("ImageCropPicker", "Could not find wrapper_controls view");
                return;
            }

            // The first child of wrapper_controls is the ImageView with the background
            if (wrapperControls instanceof ViewGroup) {
                ViewGroup wrapper = (ViewGroup) wrapperControls;
                if (wrapper.getChildCount() > 0) {
                    View backgroundView = wrapper.getChildAt(0);
                    if (backgroundView instanceof ImageView) {
                        // Create a new drawable with custom color and rounded top corners
                        GradientDrawable drawable = new GradientDrawable();
                        drawable.setShape(GradientDrawable.RECTANGLE);
                        
                        // Convert 12dp to pixels (matching ucrop_wrapper_controls_shape.xml)
                        float cornerRadiusPx = TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP, 12,
                            resources.getDisplayMetrics()
                        );

                        // Set only top corners rounded (topLeft, topRight, bottomRight, bottomLeft)
                        drawable.setCornerRadii(new float[]{
                            cornerRadiusPx, cornerRadiusPx,  // top-left
                            cornerRadiusPx, cornerRadiusPx,  // top-right
                            0, 0,                             // bottom-right
                            0, 0                              // bottom-left
                        });
                        
                        drawable.setColor(Color.parseColor(colorString));
                        backgroundView.setBackground(drawable);
                    }
                }
            }
        } catch (Exception e) {
            Log.e("ImageCropPicker", "Error applying controls background color", e);
        }
    }

    /**
     * Applies the custom background color to UCrop's bottom controls bar (wrapper_states).
     * This is the bar containing the crop, rotate, and scale icons.
     */
    private void applyControlsBarBackgroundColor(Activity activity, String colorString) {
        try {
            // Use UCrop's R class directly to get the resource ID
            int wrapperStatesId = com.yalantis.ucrop.R.id.wrapper_states;

            View wrapperStates = activity.findViewById(wrapperStatesId);
            if (wrapperStates == null) {
                Log.w("ImageCropPicker", "Could not find wrapper_states view");
                return;
            }

            // Set the background color directly on this view
            wrapperStates.setBackgroundColor(Color.parseColor(colorString));
        } catch (Exception e) {
            Log.e("ImageCropPicker", "Error applying controls bar background color", e);
        }
    }

    /**
     * Applies custom colors to the widget icons and text labels in the bottom controls bar.
     * This handles both active (selected) and inactive (unselected) states.
     */
    private void applyWidgetColors(Activity activity, String activeColorString, String inactiveColorString) {
        try {
            int activeColor = activeColorString != null ? Color.parseColor(activeColorString) : -1;
            int inactiveColor = inactiveColorString != null ? Color.parseColor(inactiveColorString) : -1;

            // Apply to all three state widgets: crop, rotate, scale
            applyWidgetStateColors(activity, com.yalantis.ucrop.R.id.state_aspect_ratio,
                com.yalantis.ucrop.R.id.image_view_state_aspect_ratio,
                com.yalantis.ucrop.R.id.text_view_crop,
                activeColor, inactiveColor);

            applyWidgetStateColors(activity, com.yalantis.ucrop.R.id.state_rotate,
                com.yalantis.ucrop.R.id.image_view_state_rotate,
                com.yalantis.ucrop.R.id.text_view_rotate,
                activeColor, inactiveColor);

            applyWidgetStateColors(activity, com.yalantis.ucrop.R.id.state_scale,
                com.yalantis.ucrop.R.id.image_view_state_scale,
                com.yalantis.ucrop.R.id.text_view_scale,
                activeColor, inactiveColor);

            // Also apply to aspect ratio text views (1:1, 3:4, Original, etc.)
            applyAspectRatioTextColors(activity, activeColor, inactiveColor);

            // Apply to the rotation and scale wheel tick marks
            if (inactiveColor != -1) {
                applyWheelTickColors(activity, inactiveColor);
            }
        } catch (Exception e) {
            Log.e("ImageCropPicker", "Error applying widget colors", e);
        }
    }

    /**
     * Applies the inactive color to the rotation and scale wheel tick marks.
     * Uses reflection since HorizontalProgressWheelView doesn't expose a public setter for tick color.
     */
    private void applyWheelTickColors(Activity activity, int tickColor) {
        try {
            // Apply to rotate wheel
            View rotateWheel = activity.findViewById(com.yalantis.ucrop.R.id.rotate_scroll_wheel);
            if (rotateWheel != null) {
                setWheelTickColor(rotateWheel, tickColor);
            }

            // Apply to scale wheel
            View scaleWheel = activity.findViewById(com.yalantis.ucrop.R.id.scale_scroll_wheel);
            if (scaleWheel != null) {
                setWheelTickColor(scaleWheel, tickColor);
            }
        } catch (Exception e) {
            Log.e("ImageCropPicker", "Error applying wheel tick colors", e);
        }
    }

    /**
     * Sets the tick color on a HorizontalProgressWheelView using reflection.
     */
    private void setWheelTickColor(View wheelView, int color) {
        try {
            // Access the private mProgressLinePaint field via reflection
            java.lang.reflect.Field paintField = wheelView.getClass().getDeclaredField("mProgressLinePaint");
            paintField.setAccessible(true);
            Paint progressLinePaint = (Paint) paintField.get(wheelView);
            if (progressLinePaint != null) {
                progressLinePaint.setColor(color);
                wheelView.invalidate();
            }
        } catch (Exception e) {
            Log.e("ImageCropPicker", "Error setting wheel tick color via reflection", e);
        }
    }

    /**
     * Applies colors to the aspect ratio text views (1:1, 3:4, Original, etc.)
     * These are dynamically created AspectRatioTextView instances inside layout_aspect_ratio.
     */
    private void applyAspectRatioTextColors(Activity activity, int activeColor, int inactiveColor) {
        try {
            // Find the layout containing aspect ratio views
            View layoutAspectRatio = activity.findViewById(com.yalantis.ucrop.R.id.layout_aspect_ratio);
            if (layoutAspectRatio == null || !(layoutAspectRatio instanceof ViewGroup)) {
                return;
            }

            ViewGroup aspectRatioContainer = (ViewGroup) layoutAspectRatio;
            
            // Iterate through all child views (FrameLayouts containing AspectRatioTextView)
            for (int i = 0; i < aspectRatioContainer.getChildCount(); i++) {
                View child = aspectRatioContainer.getChildAt(i);
                if (child instanceof ViewGroup) {
                    ViewGroup wrapper = (ViewGroup) child;
                    // The first child of each wrapper is the AspectRatioTextView
                    if (wrapper.getChildCount() > 0) {
                        View aspectRatioView = wrapper.getChildAt(0);
                        if (aspectRatioView instanceof TextView) {
                            TextView textView = (TextView) aspectRatioView;
                            
                            // Create and apply the color state list
                            if (activeColor != -1 || inactiveColor != -1) {
                                int finalActiveColor = activeColor != -1 ? activeColor : 0xFFFF6300; // orange default
                                int finalInactiveColor = inactiveColor != -1 ? inactiveColor : 0xFF000000; // black default
                                
                                int[][] states = new int[][] {
                                    new int[] { android.R.attr.state_selected },
                                    new int[] {}
                                };
                                int[] colors = new int[] { finalActiveColor, finalInactiveColor };
                                ColorStateList colorStateList = new ColorStateList(states, colors);
                                textView.setTextColor(colorStateList);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e("ImageCropPicker", "Error applying aspect ratio text colors", e);
        }
    }

    /**
     * Applies colors to a single widget state (icon + text).
     */
    private void applyWidgetStateColors(Activity activity, int stateId, int imageViewId, int textViewId,
                                        int activeColor, int inactiveColor) {
        try {
            View stateView = activity.findViewById(stateId);
            if (stateView == null) return;

            ImageView imageView = activity.findViewById(imageViewId);
            TextView textView = activity.findViewById(textViewId);

            // Apply colors to the icon using a color state list drawable
            if (imageView != null && imageView.getDrawable() != null) {
                Drawable drawable = imageView.getDrawable().mutate();
                
                if (activeColor != -1 && inactiveColor != -1) {
                    // Create a color state list for the drawable
                    int[][] states = new int[][] {
                        new int[] { android.R.attr.state_selected },
                        new int[] {}
                    };
                    int[] colors = new int[] { activeColor, inactiveColor };
                    ColorStateList colorStateList = new ColorStateList(states, colors);
                    drawable.setTintList(colorStateList);
                } else if (activeColor != -1) {
                    // Only active color specified - use it for selected state
                    int[][] states = new int[][] {
                        new int[] { android.R.attr.state_selected },
                        new int[] {}
                    };
                    // Keep original color for inactive by getting current tint or using a default
                    int[] colors = new int[] { activeColor, 0xFF808080 }; // gray default for inactive
                    ColorStateList colorStateList = new ColorStateList(states, colors);
                    drawable.setTintList(colorStateList);
                } else if (inactiveColor != -1) {
                    // Only inactive color - apply to unselected state
                    int[][] states = new int[][] {
                        new int[] { android.R.attr.state_selected },
                        new int[] {}
                    };
                    int[] colors = new int[] { 0xFFFF6300, inactiveColor }; // orange default for active
                    ColorStateList colorStateList = new ColorStateList(states, colors);
                    drawable.setTintList(colorStateList);
                }
            }

            // Apply colors to the text view
            if (textView != null) {
                if (activeColor != -1 && inactiveColor != -1) {
                    int[][] states = new int[][] {
                        new int[] { android.R.attr.state_selected },
                        new int[] {}
                    };
                    int[] colors = new int[] { activeColor, inactiveColor };
                    ColorStateList colorStateList = new ColorStateList(states, colors);
                    textView.setTextColor(colorStateList);
                } else if (activeColor != -1) {
                    int[][] states = new int[][] {
                        new int[] { android.R.attr.state_selected },
                        new int[] {}
                    };
                    int[] colors = new int[] { activeColor, 0xFF808080 };
                    ColorStateList colorStateList = new ColorStateList(states, colors);
                    textView.setTextColor(colorStateList);
                } else if (inactiveColor != -1) {
                    int[][] states = new int[][] {
                        new int[] { android.R.attr.state_selected },
                        new int[] {}
                    };
                    int[] colors = new int[] { 0xFFFF6300, inactiveColor };
                    ColorStateList colorStateList = new ColorStateList(states, colors);
                    textView.setTextColor(colorStateList);
                }
            }
        } catch (Exception e) {
            Log.e("ImageCropPicker", "Error applying widget state colors", e);
        }
    }

    private String getTmpDir(Activity activity) {
        String tmpDir = activity.getCacheDir() + "/react-native-image-crop-picker";
        new File(tmpDir).mkdir();

        return tmpDir;
    }

    private void setConfiguration(final ReadableMap options) {
        mediaType = options.hasKey("mediaType") ? options.getString("mediaType") : "any";
        multiple = options.hasKey("multiple") && options.getBoolean("multiple");
        includeBase64 = options.hasKey("includeBase64") && options.getBoolean("includeBase64");
        includeExif = options.hasKey("includeExif") && options.getBoolean("includeExif");
        width = options.hasKey("width") ? options.getInt("width") : 0;
        height = options.hasKey("height") ? options.getInt("height") : 0;
        maxFiles = options.hasKey("maxFiles") ? options.getInt("maxFiles") : maxFiles;
        cropping = options.hasKey("cropping") && options.getBoolean("cropping");
        cropperActiveWidgetColor = options.hasKey("cropperActiveWidgetColor") ? options.getString("cropperActiveWidgetColor") : null;
        cropperInactiveWidgetColor = options.hasKey("cropperInactiveWidgetColor") ? options.getString("cropperInactiveWidgetColor") : null;
        cropperToolbarColor = options.hasKey("cropperToolbarColor") ? options.getString("cropperToolbarColor") : null;
        cropperToolbarTitle = options.hasKey("cropperToolbarTitle") ? options.getString("cropperToolbarTitle") : null;
        cropperToolbarWidgetColor = options.hasKey("cropperToolbarWidgetColor") ? options.getString("cropperToolbarWidgetColor") : null;
        cropperControlsColor = options.hasKey("cropperControlsColor") ? options.getString("cropperControlsColor") : null;
        cropperControlsBarColor = options.hasKey("cropperControlsBarColor") ? options.getString("cropperControlsBarColor") : null;
        cropperCircleOverlay = options.hasKey("cropperCircleOverlay") && options.getBoolean("cropperCircleOverlay");
        freeStyleCropEnabled = options.hasKey("freeStyleCropEnabled") && options.getBoolean("freeStyleCropEnabled");
        showCropGuidelines = !options.hasKey("showCropGuidelines") || options.getBoolean("showCropGuidelines");
        showCropFrame = !options.hasKey("showCropFrame") || options.getBoolean("showCropFrame");
        hideBottomControls = options.hasKey("hideBottomControls") && options.getBoolean("hideBottomControls");
        enableRotationGesture = options.hasKey("enableRotationGesture") && options.getBoolean("enableRotationGesture");
        disableCropperColorSetters = options.hasKey("disableCropperColorSetters") && options.getBoolean("disableCropperColorSetters");
        useFrontCamera = options.hasKey("useFrontCamera") && options.getBoolean("useFrontCamera");
        cropperStatusBarLight = options.hasKey("cropperStatusBarLight") ? options.getBoolean("cropperStatusBarLight") : true;
        cropperNavigationBarLight = options.hasKey("cropperNavigationBarLight") ? options.getBoolean("cropperNavigationBarLight") : false;
        this.options = options;
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            for (File child : fileOrDirectory.listFiles()) {
                deleteRecursive(child);
            }
        }

        fileOrDirectory.delete();
    }

    public void clean(final Promise promise) {

        final Activity activity = reactContext.getCurrentActivity();
        final ImageCropPicker module = this;

        if (activity == null) {
            promise.reject(E_ACTIVITY_DOES_NOT_EXIST, "Activity doesn't exist");
            return;
        }

        permissionsCheck(activity, promise, Collections.singletonList(Manifest.permission.WRITE_EXTERNAL_STORAGE), new Callable<Void>() {
            @Override
            public Void call() {
                try {
                    File file = new File(module.getTmpDir(activity));
                    if (!file.exists()) throw new Exception("File does not exist");

                    module.deleteRecursive(file);
                    promise.resolve(null);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    promise.reject(E_ERROR_WHILE_CLEANING_FILES, ex.getMessage());
                }

                return null;
            }
        });
    }

    public void cleanSingle(final String pathToDelete, final Promise promise) {
        if (pathToDelete == null) {
            promise.reject(E_ERROR_WHILE_CLEANING_FILES, "Cannot cleanup empty path");
            return;
        }

        final Activity activity = reactContext.getCurrentActivity();
        final ImageCropPicker module = this;

        if (activity == null) {
            promise.reject(E_ACTIVITY_DOES_NOT_EXIST, "Activity doesn't exist");
            return;
        }

        permissionsCheck(activity, promise, Collections.singletonList(Manifest.permission.WRITE_EXTERNAL_STORAGE), new Callable<Void>() {
            @Override
            public Void call()  {
                try {
                    String path = pathToDelete;
                    final String filePrefix = "file://";
                    if (path.startsWith(filePrefix)) {
                        path = path.substring(filePrefix.length());
                    }

                    File file = new File(path);
                    if (!file.exists()) throw new Exception("File does not exist. Path: " + path);

                    module.deleteRecursive(file);
                    promise.resolve(null);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    promise.reject(E_ERROR_WHILE_CLEANING_FILES, ex.getMessage());
                }

                return null;
            }
        });
    }

    private void permissionsCheck(final Activity activity, final Promise promise, final List<String> requiredPermissions, final Callable<Void> callback) {

        List<String> missingPermissions = new ArrayList<>();
        List<String> supportedPermissions = new ArrayList<>(requiredPermissions);

        // android 11 introduced scoped storage, and WRITE_EXTERNAL_STORAGE no longer works there
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            supportedPermissions.remove(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        for (String permission : supportedPermissions) {
            int status = ActivityCompat.checkSelfPermission(activity, permission);
            if (status != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }

        if (!missingPermissions.isEmpty()) {

            ((PermissionAwareActivity) activity).requestPermissions(missingPermissions.toArray(new String[missingPermissions.size()]), 1, new PermissionListener() {

                @Override
                public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
                    if (requestCode == 1) {

                        for (int permissionIndex = 0; permissionIndex < permissions.length; permissionIndex++) {
                            String permission = permissions[permissionIndex];
                            int grantResult = grantResults[permissionIndex];

                            if (grantResult == PackageManager.PERMISSION_DENIED) {
                                if (permission.equals(Manifest.permission.CAMERA)) {
                                    promise.reject(E_NO_CAMERA_PERMISSION_KEY, E_NO_CAMERA_PERMISSION_MSG);
                                } else if (permission.equals(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                                    promise.reject(E_NO_LIBRARY_PERMISSION_KEY, E_NO_LIBRARY_PERMISSION_MSG);
                                } else {
                                    // should not happen, we fallback on E_NO_LIBRARY_PERMISSION_KEY rejection for minimal consistency
                                    promise.reject(E_NO_LIBRARY_PERMISSION_KEY, "Required permission missing");
                                }
                                return true;
                            }
                        }

                        try {
                            callback.call();
                        } catch (Exception e) {
                            promise.reject(E_CALLBACK_ERROR, "Unknown error", e);
                        }
                    }

                    return true;
                }
            });

            return;
        }

        // all permissions granted
        try {
            callback.call();
        } catch (Exception e) {
            promise.reject(E_CALLBACK_ERROR, "Unknown error", e);
        }
    }

    public void openCamera(final ReadableMap options, final Promise promise) {
        final Activity activity = reactContext.getCurrentActivity();

        if (activity == null) {
            promise.reject(E_ACTIVITY_DOES_NOT_EXIST, "Activity doesn't exist");
            return;
        }

        if (!isCameraAvailable(activity)) {
            promise.reject(E_CAMERA_IS_NOT_AVAILABLE, "Camera not available");
            return;
        }

        setConfiguration(options);
        resultCollector.setup(promise, false);

        permissionsCheck(activity, promise, Arrays.asList(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE), new Callable<Void>() {
            @Override
            public Void call() {
                initiateCamera(activity);
                return null;
            }
        });
    }

    private void initiateCamera(Activity activity) {

        try {
            String intent;
            File dataFile;

            if (mediaType.equals("video")) {
                intent = MediaStore.ACTION_VIDEO_CAPTURE;
                dataFile = createVideoFile();
            } else {
                intent = MediaStore.ACTION_IMAGE_CAPTURE;
                dataFile = createImageFile();
            }

            Intent cameraIntent = new Intent(intent);

            mCameraCaptureURI = FileProvider.getUriForFile(activity,
                    activity.getApplicationContext().getPackageName() + ".provider",
                    dataFile);

            cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, mCameraCaptureURI);

            if (this.useFrontCamera) {
                cameraIntent.putExtra("android.intent.extras.CAMERA_FACING", 1);
                cameraIntent.putExtra("android.intent.extras.LENS_FACING_FRONT", 1);
                cameraIntent.putExtra("android.intent.extra.USE_FRONT_CAMERA", true);
            }

            if (cameraIntent.resolveActivity(activity.getPackageManager()) == null) {
                resultCollector.notifyProblem(E_CANNOT_LAUNCH_CAMERA, "Cannot launch camera");
                return;
            }
            activity.startActivityForResult(cameraIntent, CAMERA_PICKER_REQUEST);
        } catch (Exception e) {
            resultCollector.notifyProblem(E_FAILED_TO_OPEN_CAMERA, e);
        }

    }

    private void initiatePicker(final Activity activity) {
        try {
            PickVisualMediaRequest.Builder builder = new PickVisualMediaRequest.Builder();
            // Simplified media type handling
            if (mediaType.equals("video")) {
                builder.setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly.INSTANCE);
            } else if (mediaType.equals("photo") || cropping) {
                // Force image-only for cropping
                builder.setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE);
            } else {
                builder.setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo.INSTANCE);
            }

            Intent intent;
            if (multiple && maxFiles > 1) {
                intent = new ActivityResultContracts.PickMultipleVisualMedia(maxFiles).createIntent(activity, builder.build());
            } else {
                intent = new ActivityResultContracts.PickVisualMedia().createIntent(activity, builder.build());
            }

            activity.startActivityForResult(intent, IMAGE_PICKER_REQUEST);
        } catch (Exception e) {
            resultCollector.notifyProblem(E_FAILED_TO_SHOW_PICKER, e);
        }
    }

    public void openPicker(final ReadableMap options, final Promise promise) {
        final Activity activity = reactContext.getCurrentActivity();

        if (activity == null) {
            promise.reject(E_ACTIVITY_DOES_NOT_EXIST, "Activity doesn't exist");
            return;
        }

        setConfiguration(options);
        resultCollector.setup(promise, multiple);

        permissionsCheck(activity, promise, Collections.singletonList(Manifest.permission.WRITE_EXTERNAL_STORAGE), new Callable<Void>() {
            @Override
            public Void call() {
                initiatePicker(activity);
                return null;
            }
        });
    }

    public void openCropper(final ReadableMap options, final Promise promise) {
        final Activity activity = reactContext.getCurrentActivity();

        if (activity == null) {
            promise.reject(E_ACTIVITY_DOES_NOT_EXIST, "Activity doesn't exist");
            return;
        }

        setConfiguration(options);
        resultCollector.setup(promise, false);

        final String mimeType = options.getString("mimeType");
        final Uri inputUri = Uri.parse(options.getString("path"));
        permissionsCheck(activity, promise, Collections.singletonList(Manifest.permission.WRITE_EXTERNAL_STORAGE), new Callable<Void>() {
            @Override
            public Void call() {
                // Resolve content URI to file URI if needed
                Uri uri = inputUri;
                try {
                    if ("content".equalsIgnoreCase(inputUri.getScheme())) {
                        uri = resolveContentUriToFileUri(activity, inputUri);
                    }
                } catch (Exception e) {
                    resultCollector.notifyProblem(E_NO_IMAGE_DATA_FOUND, "Failed to resolve content URI: " + e.getMessage());
                    return null;
                }

                if (mimeType != null) {
                    startCroppingWithMimeType(activity, uri, mimeType);
                } else {
                    startCropping(activity, uri);
                }
                return null;
            }
        });
    }
    
    /**
     * Resolves a content:// URI to a file:// URI by copying the content to a temp file.
     * This is necessary because UCrop may not have access to content URIs from other apps.
     */
    private Uri resolveContentUriToFileUri(Activity activity, Uri contentUri) throws IOException {
        // First try to get the real path
        String realPath = RealPathUtil.getRealPathFromURI(activity, contentUri);
        
        if (realPath != null && !realPath.isEmpty()) {
            File file = new File(realPath);
            if (file.exists() && file.canRead()) {
                return Uri.fromFile(file);
            }
        }
        
        // If we couldn't get the real path or the file doesn't exist/isn't readable,
        // copy the content to a temp file
        String extension = getExtension(activity, contentUri);
        if (extension == null || extension.isEmpty()) {
            extension = "jpg";
        }
        
        File tempFile = new File(getTmpDir(activity), UUID.randomUUID().toString() + "." + extension);
        
        try (InputStream inputStream = activity.getContentResolver().openInputStream(contentUri);
             FileOutputStream outputStream = new FileOutputStream(tempFile)) {
            
            if (inputStream == null) {
                throw new IOException("Failed to open input stream for content URI");
            }
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();
        }
        
        return Uri.fromFile(tempFile);
    }

    private String getBase64StringFromFile(String absoluteFilePath) {
        InputStream inputStream;

        try {
            inputStream = new FileInputStream(absoluteFilePath);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return null;
        }

        byte[] bytes;
        byte[] buffer = new byte[8192];
        int bytesRead;
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try {
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        bytes = output.toByteArray();
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private String getMimeType(String url) {
        String mimeType = null;
        Uri uri = Uri.parse(url);
        
        // Handle content:// URIs
        if (ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(uri.getScheme())) {
            ContentResolver cr = this.reactContext.getContentResolver();
            mimeType = cr.getType(uri);
        } 
        // Handle file:// URIs and regular file paths
        else {
            // If it's not already a file URI, convert it
            if (!"file".equalsIgnoreCase(uri.getScheme())) {
                uri = Uri.fromFile(new File(url));
            }
            String fileExtension = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
            if (fileExtension != null) {
                mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension.toLowerCase());
            }
        }
        return mimeType;
    }

    private WritableMap getSelection(Activity activity, Uri uri, boolean isCamera) throws Exception {
        String path = resolveRealPath(activity, uri, isCamera);
        if (path == null || path.isEmpty()) {
            throw new Exception("Cannot resolve asset path.");
        }

        String mime = getMimeType(path);
        if (mime != null && mime.startsWith("video/")) {
            getVideo(activity, path, mime);
            return null;
        }

        return getImage(activity, path);
    }

    private void getAsyncSelection(final Activity activity, Uri uri, boolean isCamera) throws Exception {
        String path = resolveRealPath(activity, uri, isCamera);
        if (path == null || path.isEmpty()) {
            resultCollector.notifyProblem(E_NO_IMAGE_DATA_FOUND, "Cannot resolve asset path.");
            return;
        }

        String mime = getMimeType(path);
        if (mime != null && mime.startsWith("video/")) {
            getVideo(activity, path, mime);
            return;
        }

        resultCollector.notifySuccess(getImage(activity, path));
    }

    private Bitmap validateVideo(Uri uri) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(reactContext.getCurrentActivity(), uri);
        Bitmap bmp = retriever.getFrameAtTime();

        if (bmp == null) {
            throw new Exception("Cannot retrieve video data");
        }

        retriever.release();
        return bmp;
    }

    private static Long getVideoDuration(String path) {
        try {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(path);

            return Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        } catch (Exception e) {
            return -1L;
        }
    }

    private void getVideo(final Activity activity, final String path, final String mime) throws Exception {
        validateVideo(Uri.parse(path));
        final String compressedVideoPath = getTmpDir(activity) + "/" + UUID.randomUUID().toString() + ".mp4";

        new Thread(() -> compression.compressVideo(activity, options, path, compressedVideoPath, new PromiseImpl(args -> {
            String videoPath = (String) args[0];

            try {
                Bitmap bmp = validateVideo(Uri.fromFile(new File(videoPath)));
                long modificationDate = new File(videoPath).lastModified();
                long duration = getVideoDuration(videoPath);

                WritableMap video = new WritableNativeMap();
                video.putInt("width", bmp.getWidth());
                video.putInt("height", bmp.getHeight());
                video.putString("mime", mime);
                video.putInt("size", (int) new File(videoPath).length());
                video.putInt("duration", (int) duration);
                video.putString("path", "file://" + videoPath);
                video.putString("modificationDate", String.valueOf(modificationDate));

                resultCollector.notifySuccess(video);
            } catch (Exception e) {
                resultCollector.notifyProblem(E_NO_IMAGE_DATA_FOUND, e);
            }
        }, args -> {
            WritableNativeMap ex = (WritableNativeMap) args[0];
            resultCollector.notifyProblem(ex.getString("code"), ex.getString("message"));
        }))).start();
    }

    private String resolveRealPath(Activity activity, Uri uri, boolean isCamera) throws IOException {
        String path;

        if (isCamera) {
            Uri mediaUri = Uri.parse(mCurrentMediaPath);
            path = mediaUri.getPath();
        } else {
            path = RealPathUtil.getRealPathFromURI(activity, uri);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // For videos, get the real path but don't copy the file
            String mimeType = activity.getContentResolver().getType(uri);
            if (mimeType != null && mimeType.startsWith("video/")) {
                return RealPathUtil.getRealPathFromURI(activity, uri);
            }

            String externalCacheDirPath = Uri.fromFile(activity.getExternalCacheDir()).getPath();
            String externalFilesDirPath = Uri.fromFile(activity.getExternalFilesDir(null)).getPath();
            String cacheDirPath = Uri.fromFile(activity.getCacheDir()).getPath();
            String FilesDirPath = Uri.fromFile(activity.getFilesDir()).getPath();

            if (!path.startsWith(externalCacheDirPath)
                    && !path.startsWith(externalFilesDirPath)
                    && !path.startsWith(cacheDirPath)
                    && !path.startsWith(FilesDirPath)) {
                File copiedFile = this.createExternalStoragePrivateFile(activity, uri);
                path = RealPathUtil.getRealPathFromURI(activity, Uri.fromFile(copiedFile));
            }
        }

        return path;
    }

    private File createExternalStoragePrivateFile(Context context, Uri uri) throws FileNotFoundException {
        InputStream inputStream = context.getContentResolver().openInputStream(uri);

        String extension = this.getExtension(context, uri);
        File file = new File(context.getExternalCacheDir(), "/temp/" + System.currentTimeMillis() + "." + extension);
        File parentFile = file.getParentFile();
        if (parentFile != null) {
            parentFile.mkdirs();
        }

        try {
            // Very simple code to copy a picture from the application's
            // resource into the external file.  Note that this code does
            // no error checking, and assumes the picture is small (does not
            // try to copy it in chunks).  Note that if external storage is
            // not currently mounted this will silently fail.
            OutputStream outputStream = new FileOutputStream(file);
            byte[] data = new byte[inputStream.available()];
            inputStream.read(data);
            outputStream.write(data);
            inputStream.close();
            outputStream.close();
        } catch (IOException e) {
            // Unable to create file, likely because external storage is
            // not currently mounted.
            Log.w("image-crop-picker", "Error writing " + file, e);
        }

        return file;
    }

    public String getExtension(Context context, Uri uri) {
        String extension;

        //Check uri format to avoid null
        if (uri.getScheme().equals(ContentResolver.SCHEME_CONTENT)) {
            //If scheme is a content
            final MimeTypeMap mime = MimeTypeMap.getSingleton();
            extension = mime.getExtensionFromMimeType(context.getContentResolver().getType(uri));
        } else {
            //If scheme is a File
            //This will replace white spaces with %20 and also other special characters. This will avoid returning null values on file name with spaces and special characters.
            extension = MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(new File(uri.getPath())).toString());

        }

        return extension;
    }

    private BitmapFactory.Options validateImage(String path) throws Exception {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inDither = true;

        BitmapFactory.decodeFile(path, options);

        if (options.outMimeType == null || options.outWidth == 0 || options.outHeight == 0) {
            throw new Exception("Invalid image selected");
        }

        return options;
    }

    private WritableMap getImage(final Activity activity, String path) throws Exception {
        WritableMap image = new WritableNativeMap();

        if (path.startsWith("http://") || path.startsWith("https://")) {
            throw new Exception("Cannot select remote files");
        }
        BitmapFactory.Options original = validateImage(path);
        ExifInterface originalExif = new ExifInterface(path);
        int orientation = originalExif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
        boolean invertDimensions = (
                orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                        orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                        orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                        orientation == ExifInterface.ORIENTATION_TRANSVERSE
        );


        // if compression options are provided image will be compressed. If none options is provided,
        // then original image will be returned
        File compressedImage = compression.compressImage(this.reactContext, options, path, original);
        String compressedImagePath = compressedImage.getPath();
        BitmapFactory.Options options = validateImage(compressedImagePath);
        long modificationDate = new File(path).lastModified();

        image.putString("path", "file://" + compressedImagePath);
        image.putInt("width", invertDimensions ? options.outHeight : options.outWidth);
        image.putInt("height", invertDimensions ? options.outWidth : options.outHeight);
        image.putString("mime", options.outMimeType);
        image.putInt("size", (int) new File(compressedImagePath).length());
        image.putString("modificationDate", String.valueOf(modificationDate));
        image.putString("filename", new File(path).getName());

        if (includeBase64) {
            image.putString("data", getBase64StringFromFile(compressedImagePath));
        }

        if (includeExif) {
            try {
                WritableMap exif = ExifExtractor.extract(path);
                image.putMap("exif", exif);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        return image;
    }

    private void configureCropperColors(UCrop.Options options) {
        if (cropperActiveWidgetColor != null) {
            options.setActiveControlsWidgetColor(Color.parseColor(cropperActiveWidgetColor));
        }

        if (cropperToolbarColor != null) {
            options.setToolbarColor(Color.parseColor(cropperToolbarColor));
        }

        if (cropperToolbarWidgetColor != null) {
            options.setToolbarWidgetColor(Color.parseColor(cropperToolbarWidgetColor));
        }

        options.setStatusBarLight(cropperStatusBarLight);
        options.setNavigationBarLight(cropperNavigationBarLight);
    }

    private void startCroppingWithMimeType(final Activity activity, final Uri uri, final String mimeType) {
        final Bitmap.CompressFormat compressFormat
                = MimeTypeUtils.getBitmapCompressFormat(mimeType);
        UCrop.Options options = new UCrop.Options();
        options.setCompressionFormat(compressFormat);
        options.setCompressionQuality(100);
        options.setCircleDimmedLayer(cropperCircleOverlay);
        options.setFreeStyleCropEnabled(freeStyleCropEnabled);
        options.setShowCropGrid(showCropGuidelines);
        options.setShowCropFrame(showCropFrame);
        options.setHideBottomControls(hideBottomControls);

        if (cropperToolbarTitle != null) {
            options.setToolbarTitle(cropperToolbarTitle);
        }

        if (enableRotationGesture) {
            // UCropActivity.ALL = enable both rotation & scaling
            options.setAllowedGestures(
                    UCropActivity.ALL, // When 'scale'-tab active
                    UCropActivity.ALL, // When 'rotate'-tab active
                    UCropActivity.ALL  // When 'aspect ratio'-tab active
            );
        }

        if (!disableCropperColorSetters) {
            configureCropperColors(options);
        }

        final String extension;
        if (compressFormat == Bitmap.CompressFormat.PNG) {
            extension = ".png";
        } else {
            extension = ".jpg";
        }

        UCrop uCrop = UCrop
                .of(uri, Uri.fromFile(new File(this.getTmpDir(activity), UUID.randomUUID().toString() + extension)))
                .withOptions(options);

        if (width > 0 && height > 0) {
            uCrop.withAspectRatio(width, height);
        }

        // Register lifecycle callback to customize controls background color
        registerUCropLifecycleCallback(activity);

        uCrop.start(activity);
    }

    private void startCropping(final Activity activity, final Uri uri) {
        final String mimeType = getMimeType(uri.toString());
        startCroppingWithMimeType(activity, uri, mimeType);
    }

    private void imagePickerResult(Activity activity, final int requestCode, final int resultCode, final Intent data) {
        if (resultCode == Activity.RESULT_CANCELED) {
            resultCollector.notifyProblem(E_PICKER_CANCELLED_KEY, E_PICKER_CANCELLED_MSG);
        } else if (resultCode == Activity.RESULT_OK) {
            if (multiple) {
                ClipData clipData = data.getClipData();

                try {
                    // only one image selected
                    if (clipData == null) {
                        resultCollector.setWaitCount(1);
                        getAsyncSelection(activity, data.getData(), false);
                    } else {
                        resultCollector.setWaitCount(clipData.getItemCount());
                        for (int i = 0; i < clipData.getItemCount(); i++) {
                            getAsyncSelection(activity, clipData.getItemAt(i).getUri(), false);
                        }
                    }
                } catch (Exception ex) {
                    resultCollector.notifyProblem(E_NO_IMAGE_DATA_FOUND, ex.getMessage());
                }

            } else {
                Uri uri = data.getData();

                // if the result comes in clipData format (which apparently it does in some cases)
                if (uri == null) {
                    ClipData clipData = data.getClipData();
                    if (clipData != null && clipData.getItemCount() > 0) {
                        ClipData.Item item = clipData.getItemAt(0);
                        uri = item.getUri();
                    }
                }

                // error out if uri is still null
                if(uri == null) {
                    resultCollector.notifyProblem(E_NO_IMAGE_DATA_FOUND, "Cannot resolve image url");
                    return;
                }

                if (cropping) {
                    startCropping(activity, uri);
                } else {
                    try {
                        getAsyncSelection(activity, uri, false);
                    } catch (Exception ex) {
                        resultCollector.notifyProblem(E_NO_IMAGE_DATA_FOUND, ex.getMessage());
                    }
                }
            }
        }
    }

    private void cameraPickerResult(Activity activity, final int requestCode, final int resultCode, final Intent data) {
        if (resultCode == Activity.RESULT_CANCELED) {
            resultCollector.notifyProblem(E_PICKER_CANCELLED_KEY, E_PICKER_CANCELLED_MSG);
        } else if (resultCode == Activity.RESULT_OK) {
            Uri uri = mCameraCaptureURI;

            if (uri == null) {
                resultCollector.notifyProblem(E_NO_IMAGE_DATA_FOUND, "Cannot resolve image url");
                return;
            }

            if (cropping) {
                UCrop.Options options = new UCrop.Options();
                options.setCompressionFormat(Bitmap.CompressFormat.JPEG);
                startCropping(activity, uri);
            } else {
                try {
                    resultCollector.setWaitCount(1);
                    WritableMap result = getSelection(activity, uri, true);

                    // If recording a video getSelection handles resultCollector part itself and returns null
                    if (result != null) {
                        resultCollector.notifySuccess(result);
                    }
                } catch (Exception ex) {
                    resultCollector.notifyProblem(E_NO_IMAGE_DATA_FOUND, ex.getMessage());
                }
            }
        }
    }

    private void croppingResult(Activity activity, final int requestCode, final int resultCode, final Intent data) {
        if (data != null) {
            Uri resultUri = UCrop.getOutput(data);

            if (resultUri != null) {
                try {
                    if (width > 0 && height > 0) {
                        File resized = null;
                        try{
                            resized = compression.resize(this.reactContext, resultUri.getPath(), width, height, width, height, 100, getMimeType(resultUri.toString()));
                        } catch (OutOfMemoryError ex) {
                                 resultCollector.notifyProblem(E_LOW_MEMORY_ERROR, ex.getMessage());
                        }
                        resultUri = Uri.fromFile(resized);
                    }

                    WritableMap result = getSelection(activity, resultUri, false);

                    if (result != null) {
                        result.putMap("cropRect", ImageCropPicker.getCroppedRectMap(data));

                        resultCollector.setWaitCount(1);
                        resultCollector.notifySuccess(result);
                    } else {
                        throw new Exception("Cannot crop video files");
                    }
                } catch (Exception ex) {
                    resultCollector.notifyProblem(E_NO_IMAGE_DATA_FOUND, ex.getMessage());
                }
            } else {
                resultCollector.notifyProblem(E_NO_IMAGE_DATA_FOUND, "Cannot find image data");
            }
        } else {
            resultCollector.notifyProblem(E_PICKER_CANCELLED_KEY, E_PICKER_CANCELLED_MSG);
        }
    }

    @Override
    public void onActivityResult(Activity activity, final int requestCode, final int resultCode, final Intent data) {
        Log.d("RESULT", "onActivityResult");
        if (requestCode == IMAGE_PICKER_REQUEST) {
            imagePickerResult(activity, requestCode, resultCode, data);
        } else if (requestCode == CAMERA_PICKER_REQUEST) {
            cameraPickerResult(activity, requestCode, resultCode, data);
        } else if (requestCode == UCrop.REQUEST_CROP) {
            croppingResult(activity, requestCode, resultCode, data);
        }
    }

    @Override
    public void onNewIntent(Intent intent) {
    }

    private boolean isCameraAvailable(Activity activity) {
        return activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA)
                || activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }

    private File createImageFile() throws IOException {

        String imageFileName = "image-" + UUID.randomUUID().toString();
        File path = this.reactContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES);

        if (!path.exists() && !path.isDirectory()) {
            path.mkdirs();
        }

        File image = File.createTempFile(imageFileName, ".jpg", path);

        // Save a file: path for use with ACTION_VIEW intents
        mCurrentMediaPath = "file:" + image.getAbsolutePath();

        return image;

    }

    private File createVideoFile() throws IOException {

        String videoFileName = "video-" + UUID.randomUUID().toString();
        File path = this.reactContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES);

        if (!path.exists() && !path.isDirectory()) {
            path.mkdirs();
        }

        File video = File.createTempFile(videoFileName, ".mp4", path);

        // Save a file: path for use with ACTION_VIEW intents
        mCurrentMediaPath = "file:" + video.getAbsolutePath();

        return video;

    }

    private static WritableMap getCroppedRectMap(Intent data) {
        final int DEFAULT_VALUE = -1;
        final WritableMap map = new WritableNativeMap();

        map.putInt("x", data.getIntExtra(UCrop.EXTRA_OUTPUT_OFFSET_X, DEFAULT_VALUE));
        map.putInt("y", data.getIntExtra(UCrop.EXTRA_OUTPUT_OFFSET_Y, DEFAULT_VALUE));
        map.putInt("width", data.getIntExtra(UCrop.EXTRA_OUTPUT_IMAGE_WIDTH, DEFAULT_VALUE));
        map.putInt("height", data.getIntExtra(UCrop.EXTRA_OUTPUT_IMAGE_HEIGHT, DEFAULT_VALUE));

        return map;
    }
}
