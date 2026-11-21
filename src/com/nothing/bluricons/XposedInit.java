package com.nothing.bluricons;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class XposedInit implements IXposedHookLoadPackage {

    private static final String TAG = "NothingBlurIcons";
    private static Class<?> providerClass;
    private static Object providerInstance;

    private static Class<?> drawableClass;
    private static Method setClipRadiusMethod;
    private static Method updateOffsetMethod;
    private static Method setAnimateEntranceMethod;
    private static Method completeTransitionNowMethod;
    private static Method onThemeChangedMethod;
    private static Method getWallpaperOffsetXPxMethod;
    private static Method getBlurredWallpaperMethod;

    private static long lastFolderAnimInvalidateUptime = 0;
    private static long lastFolderScrollInvalidateUptime = 0;
    private static long lastWallpaperCheckUptime = -1;
    private static float cachedWallpaperOffsetX = 0f;
    private static Bitmap cachedBlurredWallpaper = null;
    private static volatile int lastSeenUiMode = -1;
    private static volatile Object activeLauncherInstance = null;

    private static final int VIEW_TYPE_UNKNOWN = 0;
    private static final int VIEW_TYPE_ALL_APPS = 1;
    private static final int VIEW_TYPE_FOLDER = 2;
    private static final int VIEW_TYPE_WORKSPACE = 3;
    private static final int VIEW_TYPE_WIDGET = 4;
    private static final Map<View, Integer> viewTypeCache = new WeakHashMap<>();
    private static final Set<View> themedViews = Collections.newSetFromMap(new WeakHashMap<>());

    private static class BackdropState {
        final Drawable drawable;
        float lastTargetX = Float.NaN;
        float lastTargetY = Float.NaN;
        float lastRadius = Float.NaN;
        float lastWpOffsetX = Float.NaN;
        Bitmap lastBitmap = null;
        int lastUiMode = -1;

        // For folder icons: always force a re-sync on the first draw after folder open.
        boolean forceUpdateOnNextDraw = false;

        BackdropState(Drawable drawable) {
            this.drawable = drawable;
        }
    }

    private static final Map<View, BackdropState> backdropStates = new WeakHashMap<>();
    private static final Map<View, Rect> iconBoundsCache = new WeakHashMap<>();
    private static final Map<Bitmap, Bitmap> rawBitmaps = new WeakHashMap<>();
    private static final Map<Bitmap, Bitmap> cleanedBitmapsDark = new WeakHashMap<>();
    private static final Map<Bitmap, Bitmap> cleanedBitmapsLight = new WeakHashMap<>();
    private static final Set<Object> processedDrawables = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<View, float[]> cachedRestOffsets = new WeakHashMap<>();

    private static final Map<String, Bitmap> appIconsByComponent = new ConcurrentHashMap<>();
    private static final Map<String, Bitmap> appIconsByPackage = new ConcurrentHashMap<>();
    private static final Map<String, Bitmap> appIconsByTitle = new ConcurrentHashMap<>();
    private static final Map<Bitmap, Bitmap> compositePlateCacheLight = new WeakHashMap<>();
    private static final Map<Bitmap, Bitmap> compositePlateCacheDark = new WeakHashMap<>();
    private static ClassLoader launcherClassLoader;
    private static volatile Object allAppsStoreInstance;

    private static final Paint allAppsPlatePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint folderPlateHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final int[] screenLocation = new int[2];

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.nothing.launcher".equals(lpparam.packageName)) {
            return;
        }

        launcherClassLoader = lpparam.classLoader;
        Log.i(TAG, "Initializing Nothing OS Icon Blur Hook in " + lpparam.packageName);

        try {
            providerClass = XposedHelpers.findClass("com.nothing.cardhost.frosted.FrostedBackdropProvider", lpparam.classLoader);
            XposedBridge.hookAllConstructors(providerClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    providerInstance = param.thisObject;
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Could not hook FrostedBackdropProvider: " + t.getMessage());
        }

        try {
            drawableClass = XposedHelpers.findClass("com.nothing.commBase.frosted.stdview.FrostedBackdropDrawable", lpparam.classLoader);
            if (drawableClass != null) {
                setClipRadiusMethod = drawableClass.getMethod("setClipRadius", float.class);
                setClipRadiusMethod.setAccessible(true);
                updateOffsetMethod = drawableClass.getMethod("updateOffset", float.class, float.class);
                updateOffsetMethod.setAccessible(true);
                setAnimateEntranceMethod = drawableClass.getMethod("setAnimateEntrance", boolean.class);
                setAnimateEntranceMethod.setAccessible(true);
                completeTransitionNowMethod = drawableClass.getMethod("completeTransitionNow");
                completeTransitionNowMethod.setAccessible(true);
                try {
                    onThemeChangedMethod = drawableClass.getMethod("onThemeChanged");
                    onThemeChangedMethod.setAccessible(true);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Log.e(TAG, "Could not hook FrostedBackdropDrawable methods: " + t.getMessage());
        }

        try {
            Class<?> hostViewClass = XposedHelpers.findClass("com.nothing.cardhost.FrostedCardWidgetHostView", lpparam.classLoader);
            XposedBridge.hookAllMethods(hostViewClass, "provideFrostedBackdrop", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.getResult() != null && providerInstance == null) {
                        providerInstance = param.getResult();
                    }
                }
            });
        } catch (Throwable ignored) {}

        // 1. Hook Launcher lifecycle & configuration change for instant seamless blur & theme sync
        XC_MethodHook configChangedHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                activeLauncherInstance = param.thisObject;
                if (param.args != null && param.args.length > 0 && param.args[0] instanceof Configuration) {
                    Configuration newConfig = (Configuration) param.args[0];
                    int newUiMode = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
                    handleThemeTransition(param.thisObject, newUiMode);
                }
            }
        };

        XC_MethodHook createHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                activeLauncherInstance = param.thisObject;
                if (param.thisObject instanceof Context) {
                    int currentUiMode = ((Context) param.thisObject).getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
                    lastSeenUiMode = currentUiMode;
                }
            }
        };

        XC_MethodHook resumeHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                activeLauncherInstance = param.thisObject;
                if (param.thisObject instanceof Context) {
                    int currentUiMode = ((Context) param.thisObject).getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
                    handleThemeTransition(param.thisObject, currentUiMode);
                }
                scheduleLauncherRefresh(param.thisObject);
            }
        };

        try {
            Class<?> ntLauncherClass = XposedHelpers.findClass("com.nothing.launcher.NTLauncher", lpparam.classLoader);
            XposedBridge.hookAllMethods(ntLauncherClass, "onConfigurationChanged", configChangedHook);
            XposedBridge.hookAllMethods(ntLauncherClass, "onCreate", createHook);
            XposedBridge.hookAllMethods(ntLauncherClass, "onResume", resumeHook);
        } catch (Throwable ignored) {}

        try {
            Class<?> launcherClass = XposedHelpers.findClass("com.android.launcher3.Launcher", lpparam.classLoader);
            XposedBridge.hookAllMethods(launcherClass, "onConfigurationChanged", configChangedHook);
            XposedBridge.hookAllMethods(launcherClass, "onCreate", createHook);
            XposedBridge.hookAllMethods(launcherClass, "onResume", resumeHook);
            XposedBridge.hookAllMethods(launcherClass, "onWindowFocusChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    activeLauncherInstance = param.thisObject;
                    if (param.args != null && param.args.length > 0 && Boolean.TRUE.equals(param.args[0])) {
                        scheduleLauncherRefresh(param.thisObject);
                    }
                }
            });
            Log.i(TAG, "Successfully hooked Launcher lifecycle for instant blur sync on app return!");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Launcher lifecycle: " + t.getMessage());
        }

        // 2. Sync blur refresh with Nothing OS's native folder backdrop cadence & page transitions
        try {
            Class<?> workspaceClass = XposedHelpers.findClass("com.android.launcher3.Workspace", lpparam.classLoader);
            XposedBridge.hookAllMethods(workspaceClass, "invalidateFolderIconBackdropsOnLayout", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof ViewGroup) {
                        invalidateIconsInLayout((ViewGroup) param.args[0]);
                    }
                }
            });

            XposedBridge.hookAllMethods(workspaceClass, "syncFrostedBackdropOffsets", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.thisObject instanceof ViewGroup) {
                        invalidateAllWorkspaceAndHotseatIcons((ViewGroup) param.thisObject);
                    }
                }
            });

            Log.i(TAG, "Successfully synced with Nothing OS folder backdrop & workspace refresh!");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Workspace: " + t.getMessage());
        }

        // 3. Hook PagedView and FolderPagedView scrolling & transitions for 120 FPS continuous swiping in folders
        try {
            Class<?> pagedViewClass = XposedHelpers.findClass("com.android.launcher3.PagedView", lpparam.classLoader);
            XposedBridge.hookAllMethods(pagedViewClass, "onScrollChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.thisObject instanceof ViewGroup) {
                        String name = param.thisObject.getClass().getName();
                        if (name.contains("FolderPagedView") || (name.contains("Folder") && !name.contains("FolderIcon"))) {
                            long now = SystemClock.uptimeMillis();
                            if (now - lastFolderScrollInvalidateUptime >= 8) {
                                lastFolderScrollInvalidateUptime = now;
                                invalidateIconsInLayout((ViewGroup) param.thisObject);
                            }
                        }
                    }
                }
            });

            XposedBridge.hookAllMethods(pagedViewClass, "onPageEndTransition", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.thisObject instanceof ViewGroup) {
                        invalidateIconsInLayout((ViewGroup) param.thisObject);
                    }
                }
            });
            Log.i(TAG, "Successfully hooked PagedView onScrollChanged & onPageEndTransition!");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook PagedView: " + t.getMessage());
        }

        try {
            Class<?> folderPagedViewClass = XposedHelpers.findClass("com.android.launcher3.folder.FolderPagedView", lpparam.classLoader);
            XposedBridge.hookAllMethods(folderPagedViewClass, "onScrollChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.thisObject instanceof ViewGroup) {
                        long now = SystemClock.uptimeMillis();
                        if (now - lastFolderScrollInvalidateUptime >= 8) {
                            lastFolderScrollInvalidateUptime = now;
                            invalidateIconsInLayout((ViewGroup) param.thisObject);
                        }
                    }
                }
            });
            Log.i(TAG, "Successfully hooked FolderPagedView.onScrollChanged for 120 FPS smooth swiping!");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook FolderPagedView: " + t.getMessage());
        }

        // 3b. Hook Folder opening lifecycle, state transitions, and animations
        // to achieve 100% seamless, real-time dynamic glass tracking on open & swipe.
        try {
            Class<?> folderClass = XposedHelpers.findClass("com.android.launcher3.folder.Folder", lpparam.classLoader);

            // Hook setState: STATE_OPEN (2) is reached when folder finishes its opening transition
            XposedBridge.hookAllMethods(folderClass, "setState", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args != null && param.args.length > 0 && Integer.valueOf(2).equals(param.args[0])) {
                        if (param.thisObject instanceof ViewGroup) {
                            final ViewGroup folder = (ViewGroup) param.thisObject;
                            resetFolderIconBackdrops(folder);
                            invalidateIconsInLayout(folder);
                            folder.post(new Runnable() {
                                @Override
                                public void run() {
                                    resetFolderIconBackdrops(folder);
                                    invalidateIconsInLayout(folder);
                                }
                            });
                        }
                    }
                }
            });

            XC_MethodHook folderOpenHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.thisObject instanceof ViewGroup) {
                        resetFolderIconBackdrops((ViewGroup) param.thisObject);
                    }
                }
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.thisObject instanceof ViewGroup) {
                        final ViewGroup folder = (ViewGroup) param.thisObject;
                        invalidateIconsInLayout(folder);
                        folder.post(new Runnable() {
                            @Override
                            public void run() {
                                resetFolderIconBackdrops(folder);
                                invalidateIconsInLayout(folder);
                            }
                        });
                    }
                }
            };
            XposedBridge.hookAllMethods(folderClass, "animateOpen", folderOpenHook);
            XposedBridge.hookAllMethods(folderClass, "prepareToOpenFolder", folderOpenHook);
            Log.i(TAG, "Successfully hooked Folder lifecycle & setState for seamless opening!");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Folder lifecycle: " + t.getMessage());
        }

        // Hook Folder$4 (mOpenListener) onAnimationEnd
        try {
            Class<?> f4Class = XposedHelpers.findClass("com.android.launcher3.folder.Folder$4", lpparam.classLoader);
            XposedBridge.hookAllMethods(f4Class, "onAnimationEnd", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object folder = XposedHelpers.getObjectField(param.thisObject, "this$0");
                    if (folder instanceof ViewGroup) {
                        final ViewGroup vg = (ViewGroup) folder;
                        resetFolderIconBackdrops(vg);
                        invalidateIconsInLayout(vg);
                        vg.post(new Runnable() {
                            @Override
                            public void run() {
                                resetFolderIconBackdrops(vg);
                                invalidateIconsInLayout(vg);
                            }
                        });
                    }
                }
            });
            Log.i(TAG, "Successfully hooked Folder$4.onAnimationEnd!");
        } catch (Throwable ignored) {}

        // Hook NTFolderAnimationManager and FolderAnimationManager to update backdrops on EVERY FRAME (120 FPS throttled)
        for (String animMgrClassName : new String[]{
                "com.android.launcher3.folder.NTFolderAnimationManager",
                "com.android.launcher3.folder.FolderAnimationManager"
        }) {
            try {
                Class<?> animMgrClass = XposedHelpers.findClass(animMgrClassName, lpparam.classLoader);
                XposedBridge.hookAllMethods(animMgrClass, "getAnimator", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object result = param.getResult();
                        if (!(result instanceof Animator)) return;
                        Animator anim = (Animator) result;

                        Object folderObj = null;
                        try {
                            folderObj = XposedHelpers.getObjectField(param.thisObject, "folder");
                        } catch (Throwable ignored) {}
                        if (folderObj == null) {
                            try {
                                folderObj = XposedHelpers.getObjectField(param.thisObject, "mFolder");
                            } catch (Throwable ignored) {}
                        }
                        if (!(folderObj instanceof ViewGroup)) return;
                        final ViewGroup folder = (ViewGroup) folderObj;

                        anim.addListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                resetFolderIconBackdrops(folder);
                                invalidateIconsInLayout(folder);
                                folder.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        resetFolderIconBackdrops(folder);
                                        invalidateIconsInLayout(folder);
                                    }
                                });
                            }
                        });

                        ValueAnimator.AnimatorUpdateListener throttledUpdateListener = new ValueAnimator.AnimatorUpdateListener() {
                            @Override
                            public void onAnimationUpdate(ValueAnimator animation) {
                                long now = SystemClock.uptimeMillis();
                                if (now - lastFolderAnimInvalidateUptime >= 8) {
                                    lastFolderAnimInvalidateUptime = now;
                                    invalidateIconsInLayout(folder);
                                }
                            }
                        };

                        if (anim instanceof AnimatorSet) {
                            ArrayList<Animator> children = ((AnimatorSet) anim).getChildAnimations();
                            if (children != null) {
                                for (Animator childAnim : children) {
                                    if (childAnim instanceof ValueAnimator) {
                                        ((ValueAnimator) childAnim).addUpdateListener(throttledUpdateListener);
                                    }
                                }
                            }
                        } else if (anim instanceof ValueAnimator) {
                            ((ValueAnimator) anim).addUpdateListener(throttledUpdateListener);
                        }
                    }
                });
                Log.i(TAG, "Successfully hooked " + animMgrClassName + ".getAnimator for 120 FPS dynamic glass opening!");
            } catch (Throwable ignored) {}
        }

        // --- REAL-TIME DRAG & DROP DYNAMIC WALLPAPER BLUR ---
        try {
            Class<?> dragViewClass = XposedHelpers.findClass("com.android.launcher3.dragndrop.DragView", lpparam.classLoader);
            if (dragViewClass != null) {
                XC_MethodHook dragViewInitHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        final View dragView = (View) param.thisObject;
                        if (isAppIconDrag(dragView)) {
                            setupDragViewFrostedLayer(dragView);
                        }
                    }
                };

                // Do NOT hook constructors: wait until setItemInfo and show so dragInfo and itemType are fully initialized
                XposedBridge.hookAllMethods(dragViewClass, "setItemInfo", dragViewInitHook);
                XposedBridge.hookAllMethods(dragViewClass, "show", dragViewInitHook);
                XposedBridge.hookAllMethods(dragViewClass, "onAttachedToWindow", dragViewInitHook);

                XposedBridge.hookAllMethods(dragViewClass, "drawFolderFrostedLayer", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        final View dragView = (View) param.thisObject;
                        if (dragView == null) return;

                        if (isAppIconDrag(dragView)) {
                            Canvas canvas = (Canvas) param.args[0];
                            if (canvas != null) {
                                drawAppIconFrostedLayer(dragView, canvas);
                            }
                            // Cancel native drawFolderFrostedLayer to avoid offset mismatch and outer rim
                            param.setResult(null);
                        }
                    }
                });

                XposedBridge.hookAllMethods(dragViewClass, "draw", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        final View dragView = (View) param.thisObject;
                        if (dragView == null) return;
                        if (!isAppIconDrag(dragView)) return;

                        // Ensure mContent is VISIBLE and properly sanitized
                        try {
                            Object content = XposedHelpers.getObjectField(dragView, "mContent");
                            if (content instanceof View) {
                                ((View) content).setVisibility(View.VISIBLE);
                                if (content instanceof ImageView) {
                                    Drawable cd = ((ImageView) content).getDrawable();
                                    if (cd != null) {
                                        sanitizeDragIconDrawable(cd);
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}

                        // Replace mBgSpringDrawable with transparent
                        try {
                            XposedHelpers.setObjectField(dragView, "mBgSpringDrawable", new ColorDrawable(0));
                        } catch (Throwable ignored) {}

                        // Inspect and sanitize mFgSpringDrawable if present
                        try {
                            Object fg = XposedHelpers.getObjectField(dragView, "mFgSpringDrawable");
                            if (fg instanceof Drawable) {
                                sanitizeDragIconDrawable((Drawable) fg);
                            }
                        } catch (Throwable ignored) {}
                    }
                });

                Log.i(TAG, "Successfully hooked DragView for 120 FPS dynamic Drag & Drop wallpaper blur!");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook DragView: " + t.getMessage());
        }

        // 4. Hook FastBitmapDrawable.draw and delegates to sanitize plates and enforce theme colors
        try {
            Class<?> fbdClass = XposedHelpers.findClass("com.android.launcher3.icons.FastBitmapDrawable", lpparam.classLoader);
            XC_MethodHook cleanHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    cleanDrawableIcon(param.thisObject);
                }
            };
            XposedBridge.hookAllMethods(fbdClass, "draw", cleanHook);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook FastBitmapDrawable: " + t.getMessage());
        }

        // 5. Hook TextView.onDraw for BubbleTextView:
        // Desktop / Hotseat: renders authentic Nothing OS FrostedBackdropDrawable (100% match with widgets & folders).
        // Folders: renders ultra-fast hardware-accelerated Skia with authentic ColorFilter (120 FPS dynamic glass).
        try {
            XposedHelpers.findAndHookMethod(
                TextView.class,
                "onDraw",
                Canvas.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Object obj = param.thisObject;
                        if (obj == null) return;

                        if (!obj.getClass().getName().contains("BubbleTextView")) {
                            return;
                        }

                        TextView view = (TextView) obj;
                        Canvas canvas = (Canvas) param.args[0];
                        if (canvas == null) return;

                        // Never render a backdrop for BubbleTextView inside FolderIcon (desktop closed folder preview)
                        ViewParent parent = view.getParent();
                        if (parent != null && parent.getClass().getName().contains("FolderIcon")) {
                            return;
                        }

                        // Fast-path cached icon bounds (avoids reflection and object allocations on every frame)
                        Rect blurBounds = iconBoundsCache.get(view);
                        int viewW = view.getWidth();
                        int viewH = view.getHeight();
                        if (blurBounds == null || viewW <= 0 || viewH <= 0) {
                            Rect iconBounds = new Rect();
                            try {
                                XposedHelpers.callMethod(view, "getIconBounds", iconBounds);
                            } catch (Throwable ignored) {}

                            int iconSize = 0;
                            try {
                                Object sz = XposedHelpers.callMethod(view, "getIconSize");
                                if (sz instanceof Integer) {
                                    iconSize = (Integer) sz;
                                }
                            } catch (Throwable ignored) {}

                            if (iconBounds.isEmpty()) {
                                if (iconSize > 0) {
                                    int left = (viewW - iconSize) / 2;
                                    int top = view.getPaddingTop();
                                    iconBounds.set(left, top, left + iconSize, top + iconSize);
                                } else {
                                    Drawable[] cds = view.getCompoundDrawables();
                                    if (cds != null && cds.length > 1 && cds[1] != null) {
                                        Rect b = cds[1].getBounds();
                                        if (b != null && !b.isEmpty()) {
                                            int bWidth = b.width();
                                            int left = (viewW - bWidth) / 2;
                                            int top = view.getPaddingTop();
                                            iconBounds.set(left, top, left + bWidth, top + bWidth);
                                        }
                                    }
                                }
                            }

                            if (iconBounds.isEmpty() || iconBounds.width() <= 0 || iconBounds.height() <= 0) {
                                return;
                            }

                            int inset = Math.round(iconBounds.width() * 0.044f);
                            blurBounds = new Rect(iconBounds);
                            blurBounds.inset(inset, inset);
                            iconBoundsCache.put(view, blurBounds);
                        }

                        Context context = view.getContext();
                        float cornerRadius = blurBounds.width() / 2.0f;

                        int viewType = getViewType(view);

                        if (viewType == VIEW_TYPE_ALL_APPS) {
                            // --- ALL APPS DRAWER & SEARCH ICONS ---
                            // Authentic Nothing Classic theme appearance (soft charcoal/off-white plate).
                            // Matches Nothing OS Personalization Classic theme (system_neutral1_900 / system_neutral1_50).
                            ensureViewHasThemedIcon(view);

                            int uiMode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
                            boolean isDark = (uiMode == Configuration.UI_MODE_NIGHT_YES);
                            int plateColor;
                            try {
                                plateColor = context.getColor(isDark ? android.R.color.system_neutral1_900 : android.R.color.system_neutral1_50);
                            } catch (Throwable t) {
                                plateColor = isDark ? 0xFF1B1B1B : 0xFFF1F1F1;
                            }
                            allAppsPlatePaint.setColor(plateColor);

                            canvas.save();
                            canvas.translate(blurBounds.left, blurBounds.top);
                            canvas.drawRoundRect(0, 0, blurBounds.width(), blurBounds.height(), cornerRadius, cornerRadius, allAppsPlatePaint);
                            canvas.restore();
                            return;
                        }

                        // If not Workspace or Folder (e.g. inside a widget sheet or popup), DO NOT DRAW BACKDROP!
                        if (viewType != VIEW_TYPE_WORKSPACE && viewType != VIEW_TYPE_FOLDER) {
                            return;
                        }

                        Object provider = getFrostedProvider(context, view.getClass().getClassLoader());
                        if (provider == null) {
                            return;
                        }

                        if (drawableClass == null || setClipRadiusMethod == null || updateOffsetMethod == null) {
                            return;
                        }

                        boolean inFolder = (viewType == VIEW_TYPE_FOLDER);
                        float targetX;
                        float targetY;

                        if (inFolder) {
                            // --- FOLDER ICONS ---
                            // In folders, icons can be dynamically paged / swiped.
                            // Always use live screen location so that backdrop is 100% anchored to the wallpaper behind the folder.
                            view.getLocationOnScreen(screenLocation);
                            targetX = screenLocation[0] + blurBounds.left;
                            targetY = screenLocation[1] + blurBounds.top;
                        } else {
                            // --- WORKSPACE & HOTSEAT ICONS ---
                            // On desktop, keep cached resting coordinates during app open/close transitions.
                            float[] cached = cachedRestOffsets.get(view);
                            boolean atRest = isViewAtRest(view);

                            if (atRest) {
                                view.getLocationOnScreen(screenLocation);
                                targetX = screenLocation[0] + blurBounds.left;
                                targetY = screenLocation[1] + blurBounds.top;
                                if (cached == null) {
                                    cached = new float[]{targetX, targetY};
                                    cachedRestOffsets.put(view, cached);
                                } else {
                                    cached[0] = targetX;
                                    cached[1] = targetY;
                                }
                            } else {
                                if (cached != null) {
                                    targetX = cached[0];
                                    targetY = cached[1];
                                } else {
                                    computeRestingLocation(view, screenLocation);
                                    targetX = screenLocation[0] + blurBounds.left;
                                    targetY = screenLocation[1] + blurBounds.top;
                                    cachedRestOffsets.put(view, new float[]{targetX, targetY});
                                }
                            }
                        }

                        try {
                            BackdropState state = backdropStates.get(view);
                            if (state == null) {
                                Drawable frostedDrawable = (Drawable) XposedHelpers.newInstance(drawableClass, context, provider);
                                if (frostedDrawable != null) {
                                    frostedDrawable.setCallback(null);
                                    if (setAnimateEntranceMethod != null) {
                                        setAnimateEntranceMethod.invoke(frostedDrawable, false);
                                    }
                                    if (completeTransitionNowMethod != null) {
                                        completeTransitionNowMethod.invoke(frostedDrawable);
                                    }
                                    state = new BackdropState(frostedDrawable);
                                    backdropStates.put(view, state);
                                }
                            }

                            if (state != null && state.drawable != null) {
                                state.drawable.setCallback(null);

                                int currentUiMode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
                                if (lastSeenUiMode != -1 && currentUiMode != lastSeenUiMode) {
                                    handleThemeTransition(context, currentUiMode);
                                } else if (lastSeenUiMode == -1) {
                                    lastSeenUiMode = currentUiMode;
                                }

                                boolean uiModeChanged = (state.lastUiMode != currentUiMode);
                                if (uiModeChanged) {
                                    if (onThemeChangedMethod != null) {
                                        try {
                                            onThemeChangedMethod.invoke(state.drawable);
                                        } catch (Throwable ignored) {}
                                    }
                                    state.lastUiMode = currentUiMode;
                                    state.forceUpdateOnNextDraw = true;
                                }

                                updateWallpaperCache(provider);

                                boolean needsUpdate = uiModeChanged || state.forceUpdateOnNextDraw ||
                                        (Math.abs(state.lastTargetX - targetX) > 0.1f) ||
                                        (Math.abs(state.lastTargetY - targetY) > 0.1f) ||
                                        (Math.abs(state.lastRadius - cornerRadius) > 0.1f) ||
                                        (Math.abs(state.lastWpOffsetX - cachedWallpaperOffsetX) > 0.1f) ||
                                        (state.lastBitmap != cachedBlurredWallpaper);

                                if (needsUpdate) {
                                    if (setAnimateEntranceMethod != null) {
                                        setAnimateEntranceMethod.invoke(state.drawable, false);
                                    }
                                    setClipRadiusMethod.invoke(state.drawable, cornerRadius);
                                    updateOffsetMethod.invoke(state.drawable, targetX, targetY);
                                    if (completeTransitionNowMethod != null) {
                                        completeTransitionNowMethod.invoke(state.drawable);
                                    }
                                    state.lastTargetX = targetX;
                                    state.lastTargetY = targetY;
                                    state.lastRadius = cornerRadius;
                                    state.lastWpOffsetX = cachedWallpaperOffsetX;
                                    state.lastBitmap = cachedBlurredWallpaper;
                                    state.forceUpdateOnNextDraw = false;
                                }

                                canvas.save();
                                canvas.translate(blurBounds.left, blurBounds.top);
                                state.drawable.setBounds(0, 0, blurBounds.width(), blurBounds.height());
                                state.drawable.draw(canvas);

                                if (inFolder) {
                                    // Highlight icons inside folders:
                                    // In light mode: soft milky white overlay (lighter tone).
                                    // In dark mode: soft dark smoked glass overlay (darker tone) so it matches
                                    // dark style while staying distinct from the folder background.
                                    boolean isDark = (currentUiMode == Configuration.UI_MODE_NIGHT_YES);
                                    int highlightColor = isDark ? 0x44000000 : 0x48FFFFFF;
                                    folderPlateHighlightPaint.setColor(highlightColor);
                                    canvas.drawRoundRect(0, 0, blurBounds.width(), blurBounds.height(), cornerRadius, cornerRadius, folderPlateHighlightPaint);
                                }

                                canvas.restore();
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            );

            Log.i(TAG, "Successfully hooked TextView.onDraw for Nothing OS Launcher!");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook TextView onDraw: " + t.getMessage(), t);
        }

        // 6. Hook AllAppsStore and Launcher callbacks to collect authentic Nothing OS themed app icons
        try {
            Class<?> aasClass = XposedHelpers.findClass("com.android.launcher3.allapps.AllAppsStore", lpparam.classLoader);
            XposedBridge.hookAllConstructors(aasClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    allAppsStoreInstance = param.thisObject;
                    populateFromAllAppsStore(param.thisObject);
                }
            });
            XposedBridge.hookAllMethods(aasClass, "setApps", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    allAppsStoreInstance = param.thisObject;
                    populateFromAllAppsStore(param.thisObject);
                }
            });
            Log.i(TAG, "Successfully hooked AllAppsStore!");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook AllAppsStore: " + t.getMessage());
        }

        try {
            Class<?> aacvClass = XposedHelpers.findClass("com.android.launcher3.allapps.ActivityAllAppsContainerView", lpparam.classLoader);
            XposedBridge.hookAllConstructors(aacvClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        Object store = XposedHelpers.getObjectField(param.thisObject, "mAllAppsStore");
                        if (store != null) {
                            allAppsStoreInstance = store;
                            populateFromAllAppsStore(store);
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}

        try {
            Class<?> launcherClass = XposedHelpers.findClass("com.android.launcher3.Launcher", lpparam.classLoader);
            XposedBridge.hookAllMethods(launcherClass, "bindAllApplications", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof Object[]) {
                        Object[] apps = (Object[]) param.args[0];
                        for (Object app : apps) {
                            cacheThemedAppIcon(app);
                        }
                        Log.i(TAG, "Cached " + appIconsByPackage.size() + " themed app icons from Launcher.bindAllApplications");
                    }
                }
            });
        } catch (Throwable ignored) {}

        try {
            Class<?> ntLauncherClass = XposedHelpers.findClass("com.nothing.launcher.NTLauncher", lpparam.classLoader);
            XposedBridge.hookAllMethods(ntLauncherClass, "bindAllApplications", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof Object[]) {
                        Object[] apps = (Object[]) param.args[0];
                        for (Object app : apps) {
                            cacheThemedAppIcon(app);
                        }
                    }
                }
            });
        } catch (Throwable ignored) {}

        try {
            Class<?> qdkClass = XposedHelpers.findClass("qd.k", lpparam.classLoader);
            XposedBridge.hookAllMethods(qdkClass, "bindAllApplications", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof Object[]) {
                        Object[] apps = (Object[]) param.args[0];
                        for (Object app : apps) {
                            cacheThemedAppIcon(app);
                        }
                    }
                }
            });
        } catch (Throwable ignored) {}

        // 7. Hook SuggestItemView, BubbleTextView, and search models to ensure authentic Nothing OS classic theme in drawer search
        try {
            Class<?> sivClass = XposedHelpers.findClass("com.nothing.launcher.search.ui.components.app.SuggestItemView", lpparam.classLoader);
            XC_MethodHook suggestHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args != null) {
                        for (Object arg : param.args) {
                            if (arg instanceof List) {
                                for (Object item : (List<?>) arg) {
                                    ensureItemInfoHasThemedIcon(item);
                                }
                            } else {
                                ensureItemInfoHasThemedIcon(arg);
                            }
                        }
                    }
                }
            };
            XposedBridge.hookAllMethods(sivClass, "createBubbleTextView", suggestHook);
            XposedBridge.hookAllMethods(sivClass, "setupViews", suggestHook);
            XposedBridge.hookAllMethods(sivClass, "setSuggestion", suggestHook);
            Log.i(TAG, "Successfully hooked SuggestItemView!");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook SuggestItemView: " + t.getMessage());
        }

        try {
            Class<?> btvClass = XposedHelpers.findClass("com.android.launcher3.BubbleTextView", lpparam.classLoader);
            XC_MethodHook btvApplyHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.thisObject instanceof View) {
                        themedViews.remove((View) param.thisObject);
                    }
                    if (param.args != null && param.args.length > 0) {
                        ensureItemInfoHasThemedIcon(param.args[0]);
                    }
                }
            };
            XposedBridge.hookAllMethods(btvClass, "applyFromApplicationInfo", btvApplyHook);
            XposedBridge.hookAllMethods(btvClass, "applyFromWorkspaceItem", btvApplyHook);
            XposedBridge.hookAllMethods(btvClass, "applyIconAndLabel", btvApplyHook);
        } catch (Throwable ignored) {}

        try {
            Class<?> ntBtvClass = XposedHelpers.findClass("com.nothing.launcher.views.NTBubbleTextView", lpparam.classLoader);
            XC_MethodHook ntBtvApplyHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.thisObject instanceof View) {
                        themedViews.remove((View) param.thisObject);
                    }
                    if (param.args != null && param.args.length > 0) {
                        ensureItemInfoHasThemedIcon(param.args[0]);
                    }
                }
            };
            XposedBridge.hookAllMethods(ntBtvClass, "applyFromApplicationInfo", ntBtvApplyHook);
            XposedBridge.hookAllMethods(ntBtvClass, "applyFromWorkspaceItem", ntBtvApplyHook);
            XposedBridge.hookAllMethods(ntBtvClass, "applyIconAndLabel", ntBtvApplyHook);
        } catch (Throwable ignored) {}

        try {
            Class<?> sarClass = XposedHelpers.findClass("com.nothing.essential.search.data.model.SearchableAppResult", lpparam.classLoader);
            XC_MethodHook sarGetIconHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Bitmap themed = resolveThemedIconForSearchResult(param.thisObject);
                    if (themed != null) {
                        Context ctx = getApplicationContext();
                        param.setResult(getThemedIconWithPlate(themed, ctx));
                    }
                }
            };
            XposedBridge.hookAllMethods(sarClass, "getIcon", sarGetIconHook);
            XposedBridge.hookAllConstructors(sarClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Bitmap themed = resolveThemedIconForSearchResult(param.thisObject);
                    if (themed != null) {
                        Context ctx = getApplicationContext();
                        XposedHelpers.setObjectField(param.thisObject, "icon", getThemedIconWithPlate(themed, ctx));
                    }
                }
            });
            Log.i(TAG, "Successfully hooked SearchableAppResult!");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook SearchableAppResult: " + t.getMessage());
        }

        try {
            Class<?> ccaClass = XposedHelpers.findClass("cc.a", lpparam.classLoader);
            XposedBridge.hookAllMethods(ccaClass, "getIcon", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Bitmap themed = resolveThemedIconForSearchResult(param.thisObject);
                    if (themed != null) {
                        Context ctx = getApplicationContext();
                        param.setResult(getThemedIconWithPlate(themed, ctx));
                    }
                }
            });
        } catch (Throwable ignored) {}

        try {
            Class<?> cccClass = XposedHelpers.findClass("cc.c", lpparam.classLoader);
            XposedBridge.hookAllMethods(cccClass, "getIcon", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Bitmap themed = resolveThemedIconForSearchResult(param.thisObject);
                    if (themed != null) {
                        Context ctx = getApplicationContext();
                        param.setResult(getThemedIconWithPlate(themed, ctx));
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    private static int getViewType(View view) {
        if (view == null) return VIEW_TYPE_UNKNOWN;
        Integer type = viewTypeCache.get(view);
        if (type != null) {
            return type;
        }
        int computed = computeViewType(view);
        viewTypeCache.put(view, computed);
        return computed;
    }

    private static int computeViewType(View view) {
        if (view == null) return VIEW_TYPE_UNKNOWN;

        // Check view itself
        String selfName = view.getClass().getName().toLowerCase();
        if (selfName.contains("widgetpicker") || selfName.contains("widgetslist") ||
            selfName.contains("widgetfullsheet") || selfName.contains("widgetbottomsheet") ||
            selfName.contains("widgetcell") || selfName.contains("preview_container") ||
            selfName.contains("widget_picker_detail") || selfName.contains("widget_preview_page")) {
            return VIEW_TYPE_WIDGET;
        }

        ViewParent p = view.getParent();
        while (p instanceof View) {
            View pv = (View) p;
            String name = pv.getClass().getName();
            String nameLower = name.toLowerCase();

            // 1. Check for Widgets Picker / Sheet / Details
            if (nameLower.contains("widgetpicker") || nameLower.contains("widgetslist") ||
                nameLower.contains("widgetfullsheet") || nameLower.contains("widgetbottomsheet") ||
                nameLower.contains("widgetcell") || nameLower.contains("preview_container") ||
                nameLower.contains("widget_picker_detail") || nameLower.contains("widget_preview_page")) {
                return VIEW_TYPE_WIDGET;
            }
            try {
                int id = pv.getId();
                if (id != View.NO_ID && pv.getResources() != null) {
                    String resName = pv.getResources().getResourceEntryName(id);
                    if (resName != null) {
                        String resLower = resName.toLowerCase();
                        if (resLower.contains("widget_picker") || resLower.contains("widget_preview") ||
                            resLower.contains("widget_cell") || resLower.contains("widgets_full_sheet") ||
                            resLower.contains("widgets_bottom_sheet") || resLower.contains("preview_container")) {
                            return VIEW_TYPE_WIDGET;
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // 2. Check for All Apps Drawer & Search
            if (nameLower.contains("allapps") || nameLower.contains("all_apps") ||
                nameLower.contains("smartfolder") || nameLower.contains("smartfullsheet") ||
                nameLower.contains("category") || nameLower.contains("search") ||
                nameLower.contains("apps_view") || nameLower.contains("hidden") ||
                nameLower.contains("suggest") || nameLower.contains("compose") ||
                nameLower.contains("viewfactory")) {
                return VIEW_TYPE_ALL_APPS;
            }
            try {
                int id = pv.getId();
                if (id != View.NO_ID && pv.getResources() != null) {
                    String resName = pv.getResources().getResourceEntryName(id);
                    if (resName != null) {
                        String resLower = resName.toLowerCase();
                        if (resLower.contains("allapps") || resLower.contains("all_apps") ||
                            resLower.contains("search") || resLower.contains("apps_view") ||
                            resLower.contains("hidden")) {
                            return VIEW_TYPE_ALL_APPS;
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // 3. Check for Folder
            if (name.contains("FolderPagedView") || (name.contains("Folder") && !name.contains("FolderIcon"))) {
                return VIEW_TYPE_FOLDER;
            }

            // 4. Check for Workspace / Hotseat
            if (name.contains("Workspace") || name.contains("Hotseat")) {
                return VIEW_TYPE_WORKSPACE;
            }

            p = pv.getParent();
        }
        return VIEW_TYPE_UNKNOWN;
    }

    private static boolean isInsideFolder(View view) {
        return getViewType(view) == VIEW_TYPE_FOLDER;
    }

    private static boolean isInsideAllApps(View view) {
        return getViewType(view) == VIEW_TYPE_ALL_APPS;
    }

    private static boolean isViewAtRest(View view) {
        if (view == null) return false;
        if (Math.abs(view.getScaleX() - 1.0f) > 0.01f || Math.abs(view.getScaleY() - 1.0f) > 0.01f) {
            return false;
        }
        if (Math.abs(view.getTranslationX()) > 0.5f || Math.abs(view.getTranslationY()) > 0.5f) {
            return false;
        }

        ViewParent parent = view.getParent();
        while (parent instanceof View) {
            View pv = (View) parent;
            if (Math.abs(pv.getScaleX() - 1.0f) > 0.01f || Math.abs(pv.getScaleY() - 1.0f) > 0.01f) {
                return false;
            }
            if (Math.abs(pv.getTranslationX()) > 0.5f || Math.abs(pv.getTranslationY()) > 0.5f) {
                return false;
            }
            String pName = pv.getClass().getName();
            if (pName.contains("DragLayer") || pName.contains("LauncherRootView")) {
                break;
            }
            parent = pv.getParent();
        }
        return true;
    }

    private static void computeRestingLocation(View view, int[] outLoc) {
        int x = view.getLeft();
        int y = view.getTop();
        ViewParent parent = view.getParent();
        while (parent instanceof View) {
            View pv = (View) parent;
            String pName = pv.getClass().getName();
            if (pName.contains("Workspace") || pName.contains("FolderPagedView") || pName.contains("PagedView")) {
                x += (pv.getLeft() - pv.getScrollX());
                y += (pv.getTop() - pv.getScrollY());
            } else {
                x += pv.getLeft();
                y += pv.getTop();
            }
            if (pName.contains("DragLayer") || pName.contains("LauncherRootView")) {
                break;
            }
            parent = pv.getParent();
        }
        outLoc[0] = x;
        outLoc[1] = y;
    }

    private static void sanitizeDelegate(Object delegate, boolean isDark) {
        if (delegate == null) return;
        int targetFg = isDark ? 0xFFF1F1F1 : 0xFF303030;
        String cName = delegate.getClass().getName();

        // 1. AutomatedIconDelegate: neutralize artificial plates/glow
        if (cName.contains("AutomatedIconDelegate")) {
            try {
                Paint pp = (Paint) XposedHelpers.getObjectField(delegate, "platePaint");
                if (pp != null && pp.getAlpha() != 0) pp.setAlpha(0);
            } catch (Throwable ignored) {}
            try {
                Paint gp = (Paint) XposedHelpers.getObjectField(delegate, "glowPaint");
                if (gp != null && gp.getAlpha() != 0) gp.setAlpha(0);
            } catch (Throwable ignored) {}

            try {
                Object parent = XposedHelpers.getObjectField(delegate, "parentDelegate");
                if (parent != null && parent != delegate) {
                    sanitizeDelegate(parent, isDark);
                }
            } catch (Throwable ignored) {}
        }

        // 2. ThemedIconDelegate: neutralize plate and synchronize foreground color
        if (cName.contains("ThemedIconDelegate")) {
            try {
                Paint p = (Paint) XposedHelpers.getObjectField(delegate, "paint");
                if (p != null && p.getAlpha() != 0) p.setAlpha(0);
            } catch (Throwable ignored) {}
            try {
                int currFg = XposedHelpers.getIntField(delegate, "colorFg");
                if (currFg != targetFg) {
                    XposedHelpers.setIntField(delegate, "colorFg", targetFg);
                    Paint mp = (Paint) XposedHelpers.getObjectField(delegate, "monoPaint");
                    if (mp != null) {
                        mp.setColorFilter(new PorterDuffColorFilter(targetFg, PorterDuff.Mode.SRC_IN));
                        mp.setColor(targetFg);
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void invalidateIconsInLayout(ViewGroup vg) {
        if (vg == null) return;
        boolean isDark = (lastSeenUiMode == Configuration.UI_MODE_NIGHT_YES);
        try {
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = vg.getChildAt(i);
                if (child != null) {
                    if (child.getClass().getName().contains("BubbleTextView")) {
                        try {
                            TextView tv = (TextView) child;
                            Drawable[] cds = tv.getCompoundDrawables();
                            if (cds != null && cds.length > 1 && cds[1] != null) {
                                Drawable d = cds[1];
                                try {
                                    Paint p = (Paint) XposedHelpers.getObjectField(d, "paint");
                                    if (p != null && p.getAlpha() == 0) p.setAlpha(255);
                                } catch (Throwable ignored) {}
                                Object delegate = null;
                                try {
                                    delegate = XposedHelpers.getObjectField(d, "delegate");
                                } catch (Throwable ignored) {}
                                if (delegate != null) {
                                    sanitizeDelegate(delegate, isDark);
                                }
                                try {
                                    Object info = XposedHelpers.getObjectField(d, "bitmapInfo");
                                    if (info != null) {
                                        Bitmap bmp = (Bitmap) XposedHelpers.getObjectField(info, "icon");
                                        if (bmp != null && !bmp.isRecycled()) {
                                            Bitmap cleaned = getCleanedBitmap(bmp);
                                            if (cleaned != null && cleaned != bmp) {
                                                XposedHelpers.setObjectField(info, "icon", cleaned);
                                            }
                                        }
                                    }
                                } catch (Throwable ignored) {}
                                d.invalidateSelf();
                            }
                        } catch (Throwable ignored) {}
                        child.invalidate();
                    } else if (child instanceof ViewGroup) {
                        invalidateIconsInLayout((ViewGroup) child);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Called before/during/after Folder open and state transitions.
     * Clears cached bounds and backdrop offsets of every BubbleTextView inside the folder
     * so that all draws use the real on-screen position (never stale desktop positions).
     */
    private static void resetFolderIconBackdrops(ViewGroup vg) {
        if (vg == null) return;
        try {
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = vg.getChildAt(i);
                if (child == null) continue;
                String name = child.getClass().getName();
                if (name.contains("BubbleTextView")) {
                    cachedRestOffsets.remove(child);
                    iconBoundsCache.remove(child);
                    BackdropState state = backdropStates.get(child);
                    if (state != null) {
                        state.lastTargetX = Float.NaN;
                        state.lastTargetY = Float.NaN;
                        state.lastRadius = Float.NaN;
                        state.forceUpdateOnNextDraw = true;
                    }
                    child.invalidate();
                } else if (child instanceof ViewGroup) {
                    resetFolderIconBackdrops((ViewGroup) child);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void invalidateAllWorkspaceAndHotseatIcons(ViewGroup workspace) {
        if (workspace == null) return;
        try {
            int count = workspace.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = workspace.getChildAt(i);
                if (child instanceof ViewGroup) {
                    invalidateIconsInLayout((ViewGroup) child);
                }
            }

            Context ctx = workspace.getContext();
            if (ctx != null) {
                try {
                    ViewGroup hotseat = (ViewGroup) XposedHelpers.callMethod(ctx, "getHotseat");
                    if (hotseat != null) {
                        invalidateIconsInLayout(hotseat);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static boolean isAppIconDrag(View dragView) {
        if (dragView == null) return false;

        try {
            Boolean c = (Boolean) XposedHelpers.callMethod(dragView, "isLiveCardDragging");
            if (c != null && c) return false;
        } catch (Throwable ignored) {}
        try {
            Boolean wCheck = (Boolean) XposedHelpers.callMethod(dragView, "containsAppWidgetHostView");
            if (wCheck != null && wCheck) return false;
        } catch (Throwable ignored) {}

        int itemType = -999;
        try {
            itemType = XposedHelpers.getIntField(dragView, "mItemType");
            // itemType 2 = folder, 4 = appwidget, 5 = custom appwidget, 100 = Nothing Card/Widget
            if (itemType == 2 || itemType == 4 || itemType == 5 || itemType == 100) {
                return false;
            }
        } catch (Throwable ignored) {}

        int w = dragView.getWidth();
        int h = dragView.getHeight();
        if (w <= 0) {
            try { w = XposedHelpers.getIntField(dragView, "mWidth"); } catch (Throwable ignored) {}
        }
        if (h <= 0) {
            try { h = XposedHelpers.getIntField(dragView, "mHeight"); } catch (Throwable ignored) {}
        }
        // Normal 1x1 app icons on Nothing OS are ~192px-240px (+ blur outline up to ~280px).
        // Widgets (2x2, 4x2) are 500px+ and must never be treated as 1x1 circular icons.
        if (w > 380 || h > 380) {
            return false;
        }

        try {
            Object activity = XposedHelpers.getObjectField(dragView, "mActivity");
            if (activity != null) {
                Object dc = XposedHelpers.callMethod(activity, "getDragController");
                if (dc != null) {
                    Object dragObj = XposedHelpers.callMethod(dc, "getDragObject");
                    if (dragObj != null) {
                        Object source = XposedHelpers.getObjectField(dragObj, "dragSource");
                        if (source != null) {
                            String sName = source.getClass().getName();
                            // Do NOT use generic contains("widget") because android.widget.* contains "widget"!
                            if (sName.contains("WidgetPicker") || sName.contains("NtWidget") || sName.contains("de.e")) {
                                return false;
                            }
                        }

                        Object dragInfo = XposedHelpers.getObjectField(dragObj, "dragInfo");
                        if (dragInfo != null) {
                            String infoName = dragInfo.getClass().getName();
                            if (infoName.contains("AppWidget") || infoName.contains("Folder") ||
                                infoName.contains("PendingAddWidget") || infoName.contains("LauncherCard")) {
                                return false;
                            }

                            try {
                                int spanX = XposedHelpers.getIntField(dragInfo, "spanX");
                                int spanY = XposedHelpers.getIntField(dragInfo, "spanY");
                                if (spanX > 1 || spanY > 1) {
                                    return false;
                                }
                            } catch (Throwable ignored) {}

                            int dType = XposedHelpers.getIntField(dragInfo, "itemType");
                            if (dType == 2 || dType == 4 || dType == 5 || dType == 100) {
                                return false;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            Object content = XposedHelpers.getObjectField(dragView, "mContent");
            if (content != null) {
                String cName = content.getClass().getName();
                // Notice: do NOT check contains("widget") because android.widget.ImageView contains "widget"!
                if (cName.contains("AppWidget") || cName.contains("LauncherCard") || cName.contains("Folder")) {
                    return false;
                }

                if (content instanceof View) {
                    Object tag = ((View) content).getTag();
                    if (tag != null) {
                        String tagClass = tag.getClass().getName();
                        if (tagClass.contains("AppWidget") || tagClass.contains("Folder") ||
                            tagClass.contains("PendingAddWidget") || tagClass.contains("LauncherCard")) {
                            return false;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        return true;
    }

    private static Rect getDragViewIconBounds(View dragView) {
        int w = dragView.getWidth();
        int h = dragView.getHeight();
        if (w <= 0) w = XposedHelpers.getIntField(dragView, "mWidth");
        if (h <= 0) h = XposedHelpers.getIntField(dragView, "mHeight");

        try {
            Object maskPath = XposedHelpers.getObjectField(dragView, "mScaledMaskPath");
            if (maskPath instanceof Path) {
                RectF pathBounds = new RectF();
                ((Path) maskPath).computeBounds(pathBounds, true);
                if (pathBounds.width() > 10 && pathBounds.height() > 10) {
                    return new Rect(
                        Math.round(pathBounds.left),
                        Math.round(pathBounds.top),
                        Math.round(pathBounds.right),
                        Math.round(pathBounds.bottom)
                    );
                }
            }
        } catch (Throwable ignored) {}

        try {
            Object bg = XposedHelpers.getObjectField(dragView, "mBgSpringDrawable");
            if (bg instanceof Drawable) {
                Rect bounds = ((Drawable) bg).getBounds();
                if (bounds != null && !bounds.isEmpty()) {
                    return new Rect(bounds);
                }
            }
        } catch (Throwable ignored) {}

        int outline = 0;
        try {
            outline = XposedHelpers.getIntField(dragView, "mBlurSizeOutline");
        } catch (Throwable ignored) {}
        if (outline <= 0) {
            try {
                Context ctx = dragView.getContext();
                int resId = ctx.getResources().getIdentifier("blur_size_medium_outline", "dimen", ctx.getPackageName());
                if (resId != 0) {
                    outline = ctx.getResources().getDimensionPixelSize(resId);
                }
            } catch (Throwable ignored) {}
        }
        if (outline <= 0) outline = 28;

        int half = outline / 2;
        int iconW = Math.max(1, w - outline);
        int iconH = Math.max(1, h - outline);
        return new Rect(half, half, half + iconW, half + iconH);
    }

    private static void setupDragViewFrostedLayer(View dragView) {
        if (dragView == null) return;
        if (!isAppIconDrag(dragView)) return;
        try {
            Rect iconBounds = getDragViewIconBounds(dragView);

            Object existing = XposedHelpers.getObjectField(dragView, "mFolderFrostedDrawable");
            if (existing instanceof Drawable) {
                Drawable d = (Drawable) existing;
                Rect bounds = d.getBounds();
                if (!bounds.equals(iconBounds)) {
                    d.setBounds(iconBounds);
                    float radius = iconBounds.width() / 2.0f;
                    if (setClipRadiusMethod != null) {
                        setClipRadiusMethod.invoke(d, radius);
                    }
                }
                // Always ensure mBgSpringDrawable is transparent
                try {
                    XposedHelpers.setObjectField(dragView, "mBgSpringDrawable", new ColorDrawable(0));
                } catch (Throwable ignored) {}
                return;
            }

            Context ctx = dragView.getContext();
            Object provider = getFrostedProvider(ctx, dragView.getClass().getClassLoader());
            if (provider == null || drawableClass == null) return;

            Drawable frostedDrawable = (Drawable) XposedHelpers.newInstance(drawableClass, ctx, provider);
            if (frostedDrawable == null) return;

            frostedDrawable.setCallback(null);
            if (setAnimateEntranceMethod != null) {
                setAnimateEntranceMethod.invoke(frostedDrawable, false);
            }
            if (completeTransitionNowMethod != null) {
                completeTransitionNowMethod.invoke(frostedDrawable);
            }

            frostedDrawable.setBounds(iconBounds);
            float radius = iconBounds.width() / 2.0f;
            if (setClipRadiusMethod != null) {
                setClipRadiusMethod.invoke(frostedDrawable, radius);
            }

            XposedHelpers.setObjectField(dragView, "mFolderFrostedDrawable", frostedDrawable);

            // Suppress mBgSpringDrawable so it NEVER draws an opaque plate over the blur
            try {
                XposedHelpers.setObjectField(dragView, "mBgSpringDrawable", new ColorDrawable(0));
            } catch (Throwable ignored) {}

            // Sanitize mContent and mFgSpringDrawable so they are stripped of opaque plates
            try {
                Object content = XposedHelpers.getObjectField(dragView, "mContent");
                if (content instanceof View) {
                    ((View) content).setVisibility(View.VISIBLE);
                    if (content instanceof ImageView) {
                        Drawable cd = ((ImageView) content).getDrawable();
                        if (cd != null) sanitizeDragIconDrawable(cd);
                    }
                }
            } catch (Throwable ignored) {}
            try {
                Object fg = XposedHelpers.getObjectField(dragView, "mFgSpringDrawable");
                if (fg instanceof Drawable) sanitizeDragIconDrawable((Drawable) fg);
            } catch (Throwable ignored) {}

            Log.i(TAG, "Attached live FrostedBackdropDrawable to DragView: " + iconBounds.width() + "x" + iconBounds.height() + " at " + iconBounds);
        } catch (Throwable t) {
            Log.e(TAG, "setupDragViewFrostedLayer failed: " + t.getMessage());
        }
    }

    private static void sanitizeDragIconDrawable(Drawable drawable) {
        if (drawable == null) return;

        if (drawable instanceof android.graphics.drawable.InsetDrawable) {
            Drawable inner = ((android.graphics.drawable.InsetDrawable) drawable).getDrawable();
            if (inner != null) sanitizeDragIconDrawable(inner);
            return;
        }

        boolean isDark = (lastSeenUiMode == Configuration.UI_MODE_NIGHT_YES);
        int targetFg = isDark ? 0xFFF1F1F1 : 0xFF303030;

        // Ensure FastBitmapDrawable's main paint does NOT tint the entire bitmap
        try {
            Paint p = (Paint) XposedHelpers.getObjectField(drawable, "paint");
            if (p != null) {
                p.setColorFilter(null);
            }
        } catch (Throwable ignored) {}

        // Sanitize inner delegate
        try {
            Object delegate = XposedHelpers.getObjectField(drawable, "delegate");
            if (delegate != null) {
                sanitizeDelegate(delegate, isDark);
            }
        } catch (Throwable ignored) {}

        // Strip plate from bitmapInfo.icon
        try {
            Object info = XposedHelpers.getObjectField(drawable, "bitmapInfo");
            if (info != null) {
                Bitmap icon = (Bitmap) XposedHelpers.getObjectField(info, "icon");
                if (icon != null && !icon.isRecycled()) {
                    Bitmap cleaned = getCleanedBitmap(icon);
                    if (cleaned != null && cleaned != icon) {
                        XposedHelpers.setObjectField(info, "icon", cleaned);
                    }
                }
            }
        } catch (Throwable ignored) {}

        // Strip plate from mBitmap directly
        try {
            Bitmap bmp = (Bitmap) XposedHelpers.getObjectField(drawable, "mBitmap");
            if (bmp != null && !bmp.isRecycled()) {
                Bitmap cleaned = getCleanedBitmap(bmp);
                if (cleaned != null && cleaned != bmp) {
                    XposedHelpers.setObjectField(drawable, "mBitmap", cleaned);
                }
            }
        } catch (Throwable ignored) {}

        drawable.invalidateSelf();
    }

    private static void drawAppIconFrostedLayer(View dragView, Canvas canvas) {
        try {
            setupDragViewFrostedLayer(dragView);

            Object frostedObj = XposedHelpers.getObjectField(dragView, "mFolderFrostedDrawable");
            if (!(frostedObj instanceof Drawable) || updateOffsetMethod == null) return;
            Drawable frostedDrawable = (Drawable) frostedObj;

            Rect iconBounds = getDragViewIconBounds(dragView);
            float radius = iconBounds.width() / 2.0f;

            if (setClipRadiusMethod != null) {
                setClipRadiusMethod.invoke(frostedDrawable, radius);
            }
            frostedDrawable.setBounds(iconBounds);

            int[] tempLoc = (int[]) XposedHelpers.getObjectField(dragView, "mTempLoc");
            if (tempLoc == null) {
                tempLoc = new int[2];
            }
            dragView.getLocationOnScreen(tempLoc);

            float scaleX = dragView.getScaleX();
            float scaleY = dragView.getScaleY();
            float screenX = tempLoc[0] + iconBounds.left * scaleX;
            float screenY = tempLoc[1] + iconBounds.top * scaleY;

            updateOffsetMethod.invoke(frostedDrawable, screenX, screenY);
            frostedDrawable.draw(canvas);

            // Ensure mBgSpringDrawable stays 100% transparent
            XposedHelpers.setObjectField(dragView, "mBgSpringDrawable", new ColorDrawable(0));
        } catch (Throwable t) {
            Log.e(TAG, "drawAppIconFrostedLayer failed: " + t.getMessage());
        }
    }

    private static void scheduleLauncherRefresh(final Object launcherObj) {
        Object target = (launcherObj != null) ? launcherObj : activeLauncherInstance;
        if (target == null) return;
        try {
            ViewGroup workspace = null;
            ViewGroup hotseat = null;
            if (target instanceof Activity) {
                try {
                    workspace = (ViewGroup) XposedHelpers.callMethod(target, "getWorkspace");
                } catch (Throwable ignored) {}
                try {
                    hotseat = (ViewGroup) XposedHelpers.callMethod(target, "getHotseat");
                } catch (Throwable ignored) {}
            } else if (target instanceof View) {
                View v = (View) target;
                View root = v.getRootView();
                if (root instanceof ViewGroup) {
                    invalidateIconsInLayout((ViewGroup) root);
                }
            }

            if (workspace == null && activeLauncherInstance != null && activeLauncherInstance != target) {
                try {
                    workspace = (ViewGroup) XposedHelpers.callMethod(activeLauncherInstance, "getWorkspace");
                } catch (Throwable ignored) {}
            }
            if (hotseat == null && activeLauncherInstance != null && activeLauncherInstance != target) {
                try {
                    hotseat = (ViewGroup) XposedHelpers.callMethod(activeLauncherInstance, "getHotseat");
                } catch (Throwable ignored) {}
            }

            if (workspace != null) {
                try {
                    XposedHelpers.callMethod(workspace, "syncFrostedBackdropOffsets");
                } catch (Throwable ignored) {}
                invalidateAllWorkspaceAndHotseatIcons(workspace);
            }
            if (hotseat != null) {
                invalidateIconsInLayout(hotseat);
            }

            // Also check if a folder is currently open and refresh it
            try {
                ClassLoader cl = (target instanceof Context) ? ((Context) target).getClassLoader() : launcherClassLoader;
                if (cl != null) {
                    Class<?> folderClass = XposedHelpers.findClass("com.android.launcher3.folder.Folder", cl);
                    Object targetActivity = (target instanceof Activity) ? target : activeLauncherInstance;
                    if (targetActivity != null) {
                        View openFolder = (View) XposedHelpers.callStaticMethod(folderClass, "getOpen", targetActivity);
                        if (openFolder instanceof ViewGroup) {
                            invalidateIconsInLayout((ViewGroup) openFolder);
                            resetFolderIconBackdrops((ViewGroup) openFolder);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            Log.e(TAG, "scheduleLauncherRefresh failed: " + t.getMessage());
        }
    }

    private static synchronized void handleThemeTransition(Object contextOrLauncher, int newUiMode) {
        if (newUiMode != Configuration.UI_MODE_NIGHT_YES && newUiMode != Configuration.UI_MODE_NIGHT_NO) {
            return;
        }
        if (lastSeenUiMode == newUiMode) {
            return;
        }
        lastSeenUiMode = newUiMode;
        boolean isDark = (newUiMode == Configuration.UI_MODE_NIGHT_YES);
        Log.i(TAG, "Theme transition detected! Switching to " + (isDark ? "DARK" : "LIGHT") + " mode");

        if (contextOrLauncher instanceof Activity) {
            activeLauncherInstance = contextOrLauncher;
        }

        // 1. Purge internal caches
        synchronized (cleanedBitmapsDark) {
            cleanedBitmapsDark.clear();
        }
        synchronized (cleanedBitmapsLight) {
            cleanedBitmapsLight.clear();
        }
        synchronized (processedDrawables) {
            processedDrawables.clear();
        }
        synchronized (themedViews) {
            themedViews.clear();
        }
        appIconsByComponent.clear();
        appIconsByPackage.clear();
        appIconsByTitle.clear();
        synchronized (compositePlateCacheLight) {
            compositePlateCacheLight.clear();
        }
        synchronized (compositePlateCacheDark) {
            compositePlateCacheDark.clear();
        }

        // 2. Invalidate all frosted backdrop states
        synchronized (backdropStates) {
            for (BackdropState state : backdropStates.values()) {
                if (state != null) {
                    state.forceUpdateOnNextDraw = true;
                    state.lastUiMode = -1;
                    state.lastBitmap = null;
                }
            }
        }

        // 3. Clear Launcher's in-memory IconCache and reload LauncherModel
        try {
            Context ctx = null;
            if (contextOrLauncher instanceof Context) {
                ctx = (Context) contextOrLauncher;
            } else if (activeLauncherInstance instanceof Context) {
                ctx = (Context) activeLauncherInstance;
            } else {
                ctx = getApplicationContext();
            }

            ClassLoader cl = (ctx != null) ? ctx.getClassLoader() : launcherClassLoader;
            if (cl != null) {
                Class<?> launcherAppStateClass = XposedHelpers.findClass("com.android.launcher3.LauncherAppState", cl);
                Object appState = XposedHelpers.callStaticMethod(launcherAppStateClass, "getInstance", ctx);
                if (appState != null) {
                    Object iconCache = XposedHelpers.callMethod(appState, "getIconCache");
                    if (iconCache != null) {
                        try {
                            XposedHelpers.callMethod(iconCache, "clearMemoryCache");
                        } catch (Throwable ignored) {}
                        try {
                            XposedHelpers.callMethod(iconCache, "updateIconParams", 0, 0);
                        } catch (Throwable ignored) {}
                    }
                    try {
                        Class<?> iconPoolClass = XposedHelpers.findClass("com.android.launcher3.icons.LauncherIcons$IconPool", cl);
                        XposedHelpers.callStaticMethod(iconPoolClass, "clear");
                    } catch (Throwable ignored) {}

                    Object model = XposedHelpers.callMethod(appState, "getModel");
                    if (model != null) {
                        XposedHelpers.callMethod(model, "reloadIfActive");
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to reload LauncherModel on theme transition: " + t.getMessage());
        }

        // 4. Immediately sanitize and invalidate on-screen icons in workspace, hotseat, and open folders
        scheduleLauncherRefresh(contextOrLauncher);
    }

    private static Bitmap getCleanedBitmap(Bitmap icon) {
        if (icon == null || icon.isRecycled()) return null;

        synchronized (cleanedBitmapsDark) {
            Bitmap cached = cleanedBitmapsDark.get(icon);
            if (cached != null && !cached.isRecycled()) {
                return cached;
            }
        }

        try {
            int w = icon.getWidth();
            int h = icon.getHeight();
            if (w < 10 || h < 10) return icon;

            Bitmap swBitmap;
            if (icon.getConfig() == Bitmap.Config.HARDWARE || !icon.isMutable()) {
                swBitmap = icon.copy(Bitmap.Config.ARGB_8888, true);
            } else {
                swBitmap = icon;
            }

            int[] pixels = new int[w * h];
            swBitmap.getPixels(pixels, 0, w, 0, 0, w, h);

            boolean hasDisk = false;
            int sampleIdx1 = (h / 6) * w + (w / 2);
            int sampleA1 = (pixels[sampleIdx1] >>> 24);
            int sampleIdx2 = (h / 2) * w + (w / 6);
            int sampleA2 = (pixels[sampleIdx2] >>> 24);
            int pr = 0, pg = 0, pb = 0, pa = 0;

            if (sampleA1 > 0 && sampleA1 <= 160) {
                hasDisk = true;
                pa = sampleA1;
            } else if (sampleA2 > 0 && sampleA2 <= 160) {
                hasDisk = true;
                pa = sampleA2;
            } else if (sampleA1 == 255 && sampleA2 == 255) {
                int p1 = pixels[sampleIdx1];
                int p2 = pixels[sampleIdx2];
                int r1 = (p1 >> 16) & 0xFF, g1 = (p1 >> 8) & 0xFF, b1 = p1 & 0xFF;
                int r2 = (p2 >> 16) & 0xFF, g2 = (p2 >> 8) & 0xFF, b2 = p2 & 0xFF;
                if (Math.abs(r1 - r2) <= 15 && Math.abs(g1 - g2) <= 15 && Math.abs(b1 - b2) <= 15) {
                    hasDisk = true;
                    pa = 255;
                    pr = r1;
                    pg = g1;
                    pb = b1;
                }
            }

            if (hasDisk) {
                if (swBitmap == icon) {
                    swBitmap = icon.copy(Bitmap.Config.ARGB_8888, true);
                    swBitmap.getPixels(pixels, 0, w, 0, 0, w, h);
                }

                if (pa <= 160) {
                    int maxDiskAlpha = Math.max(pa, 130);
                    for (int i = 0; i < pixels.length; i++) {
                        int a = (pixels[i] >>> 24);
                        if (a > 0 && a <= maxDiskAlpha + 5) {
                            pixels[i] = 0;
                        }
                    }
                } else {
                    for (int i = 0; i < pixels.length; i++) {
                        int px = pixels[i];
                        int a = (px >>> 24);
                        if (a == 0) continue;
                        int r = (px >> 16) & 0xFF;
                        int g = (px >> 8) & 0xFF;
                        int b = px & 0xFF;
                        if (Math.abs(r - pr) <= 25 && Math.abs(g - pg) <= 25 && Math.abs(b - pb) <= 25) {
                            pixels[i] = 0;
                        }
                    }
                }
                swBitmap.setPixels(pixels, 0, w, 0, 0, w, h);

                synchronized (cleanedBitmapsDark) {
                    cleanedBitmapsDark.put(icon, swBitmap);
                    cleanedBitmapsDark.put(swBitmap, swBitmap);
                }
                return swBitmap;
            }
        } catch (Throwable ignored) {}

        synchronized (cleanedBitmapsDark) {
            cleanedBitmapsDark.put(icon, icon);
        }
        return icon;
    }

    private static void cleanDrawableIcon(Object drawable) {
        if (drawable == null) return;
        if (!(drawable instanceof Drawable)) return;

        Drawable.Callback cb = ((Drawable) drawable).getCallback();
        if (!(cb instanceof View)) {
            // NEVER alter drawables that do not belong to an attached View
            return;
        }

        View cbView = (View) cb;
        int viewType = getViewType(cbView);
        String viewClassName = cbView.getClass().getName();

        boolean isDrag = false;
        View dragView = null;
        if (cbView.getClass().getName().contains("DragView")) {
            isDrag = true;
            dragView = cbView;
        } else {
            ViewParent parentView = cbView.getParent();
            while (parentView != null) {
                String pName = parentView.getClass().getName();
                if (pName.contains("DragView")) {
                    isDrag = true;
                    if (parentView instanceof View) {
                        dragView = (View) parentView;
                    }
                    break;
                }
                parentView = parentView.getParent();
            }
        }

        if (isDrag) {
            // Strictly verify if this DragView is actually an app icon drag!
            // If it is a widget or folder being dragged, NEVER clean or tint its drawable!
            if (dragView != null && !isAppIconDrag(dragView)) {
                return;
            }
        }

        // ONLY desktop, folder, and actively dragged app icons need plate removal and adaptive glyph tinting!
        // All apps drawer, search, widgets, and popups MUST remain 100% untouched in their native state!
        if (viewType != VIEW_TYPE_WORKSPACE && viewType != VIEW_TYPE_FOLDER && !isDrag) {
            return;
        }

        // Additional safeguard: verify the view is actually an app icon view (BubbleTextView, FolderIcon, or DragView)
        if (!viewClassName.contains("BubbleTextView") && !viewClassName.contains("FolderIcon") && !isDrag) {
            return;
        }

        boolean isDark = (lastSeenUiMode == Configuration.UI_MODE_NIGHT_YES);
        int targetFg = isDark ? 0xFFF1F1F1 : 0xFF303030;

        // Ensure FastBitmapDrawable's main paint is NEVER muted or zeroed and has adaptive theme color
        try {
            Paint p = (Paint) XposedHelpers.getObjectField(drawable, "paint");
            if (p != null) {
                if (p.getAlpha() == 0) {
                    p.setAlpha(255);
                }
                p.setColorFilter(new PorterDuffColorFilter(targetFg, PorterDuff.Mode.SRC_IN));
            }
        } catch (Throwable ignored) {}

        // Sanitize inner delegate for desktop / folder icons
        try {
            Object delegate = XposedHelpers.getObjectField(drawable, "delegate");
            if (delegate != null) {
                sanitizeDelegate(delegate, isDark);
            }
        } catch (Throwable ignored) {}

        if (processedDrawables.contains(drawable)) return;
        processedDrawables.add(drawable);

        try {
            Object info = XposedHelpers.getObjectField(drawable, "bitmapInfo");
            if (info != null) {
                Bitmap icon = (Bitmap) XposedHelpers.getObjectField(info, "icon");
                if (icon != null && !icon.isRecycled()) {
                    Bitmap cleaned = getCleanedBitmap(icon);
                    if (cleaned != null && cleaned != icon) {
                        XposedHelpers.setObjectField(info, "icon", cleaned);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void initProviderMethods(Class<?> clazz) {
        if (clazz == null) return;
        try {
            if (getWallpaperOffsetXPxMethod == null) {
                getWallpaperOffsetXPxMethod = clazz.getMethod("getWallpaperOffsetXPx");
                getWallpaperOffsetXPxMethod.setAccessible(true);
            }
        } catch (Throwable ignored) {}
        try {
            if (getBlurredWallpaperMethod == null) {
                getBlurredWallpaperMethod = clazz.getMethod("getBlurredWallpaper");
                getBlurredWallpaperMethod.setAccessible(true);
            }
        } catch (Throwable ignored) {}
    }

    private static void updateWallpaperCache(Object provider) {
        if (provider == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastWallpaperCheckUptime < 8) {
            return;
        }
        lastWallpaperCheckUptime = now;
        if (getWallpaperOffsetXPxMethod == null || getBlurredWallpaperMethod == null) {
            initProviderMethods(provider.getClass());
        }
        try {
            if (getWallpaperOffsetXPxMethod != null) {
                Object off = getWallpaperOffsetXPxMethod.invoke(provider);
                if (off instanceof Number) {
                    cachedWallpaperOffsetX = ((Number) off).floatValue();
                }
            }
        } catch (Throwable ignored) {}
        try {
            if (getBlurredWallpaperMethod != null) {
                cachedBlurredWallpaper = (Bitmap) getBlurredWallpaperMethod.invoke(provider);
            }
        } catch (Throwable ignored) {}
    }

    private static Object getFrostedProvider(Context context, ClassLoader cl) {
        if (providerInstance != null) {
            return providerInstance;
        }

        try {
            if (providerClass == null && cl != null) {
                providerClass = XposedHelpers.findClass("com.nothing.cardhost.frosted.FrostedBackdropProvider", cl);
            }
            if (providerClass == null) {
                return null;
            }

            try {
                Field fField = providerClass.getDeclaredField("F");
                fField.setAccessible(true);
                Object obj = fField.get(null);
                if (obj != null) {
                    providerInstance = obj;
                    return providerInstance;
                }
            } catch (Throwable ignored) {}

            for (Field f : providerClass.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && providerClass.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object obj = f.get(null);
                    if (obj != null) {
                        providerInstance = obj;
                        return providerInstance;
                    }
                }
            }

            for (Method m : providerClass.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && providerClass.isAssignableFrom(m.getReturnType()) && m.getParameterTypes().length == 0) {
                    m.setAccessible(true);
                    Object obj = m.invoke(null);
                    if (obj != null) {
                        providerInstance = obj;
                        return providerInstance;
                    }
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private static void cacheThemedAppIcon(Object app) {
        if (app == null) return;
        try {
            ComponentName cn = null;
            try {
                cn = (ComponentName) XposedHelpers.getObjectField(app, "componentName");
            } catch (Throwable ignored) {}
            CharSequence titleSeq = null;
            try {
                titleSeq = (CharSequence) XposedHelpers.getObjectField(app, "title");
            } catch (Throwable ignored) {}

            Object bitmapInfo = null;
            try {
                bitmapInfo = XposedHelpers.getObjectField(app, "bitmap");
            } catch (Throwable ignored) {}
            if (bitmapInfo == null) {
                try {
                    bitmapInfo = XposedHelpers.getObjectField(app, "bitmapInfo");
                } catch (Throwable ignored) {}
            }

            if (bitmapInfo != null) {
                Bitmap bmp = null;
                try {
                    bmp = (Bitmap) XposedHelpers.getObjectField(bitmapInfo, "icon");
                } catch (Throwable ignored) {}

                if (bmp != null && !bmp.isRecycled()) {
                    Bitmap cleanBmp = getCleanedBitmap(bmp);
                    if (cleanBmp == null) cleanBmp = bmp;

                    if (cn != null) {
                        String flat = cn.flattenToString();
                        String pkg = cn.getPackageName();
                        String cls = cn.getClassName();
                        appIconsByComponent.put(flat, cleanBmp);
                        if (cls != null) appIconsByComponent.put(cls, cleanBmp);
                        if (pkg != null) appIconsByPackage.put(pkg, cleanBmp);
                    }
                    if (titleSeq != null) {
                        String t = titleSeq.toString().trim().toLowerCase();
                        if (!t.isEmpty()) {
                            appIconsByTitle.put(t, cleanBmp);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in cacheThemedAppIcon: " + t.getMessage());
        }
    }

    private static Context getApplicationContext() {
        try {
            Class<?> atClass = XposedHelpers.findClass("android.app.ActivityThread", null);
            return (Context) XposedHelpers.callStaticMethod(atClass, "currentApplication");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bitmap getThemedIconWithPlate(Bitmap glyph, Context context) {
        if (glyph == null || glyph.isRecycled()) return glyph;

        Context ctx = (context != null) ? context : getApplicationContext();
        int uiMode = (ctx != null) ? (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) : Configuration.UI_MODE_NIGHT_NO;
        boolean isDark = (uiMode == Configuration.UI_MODE_NIGHT_YES);

        Map<Bitmap, Bitmap> cache = isDark ? compositePlateCacheDark : compositePlateCacheLight;
        synchronized (cache) {
            Bitmap cached = cache.get(glyph);
            if (cached != null && !cached.isRecycled()) {
                return cached;
            }
        }

        try {
            int w = glyph.getWidth();
            int h = glyph.getHeight();
            Bitmap composite = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(composite);

            int plateColor;
            if (ctx != null) {
                try {
                    plateColor = ctx.getColor(isDark ? android.R.color.system_neutral1_900 : android.R.color.system_neutral1_50);
                } catch (Throwable t) {
                    plateColor = isDark ? 0xFF1B1B1B : 0xFFF1F1F1;
                }
            } else {
                plateColor = isDark ? 0xFF1B1B1B : 0xFFF1F1F1;
            }

            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(plateColor);

            float cx = w / 2.0f;
            float cy = h / 2.0f;
            float radius = (Math.min(w, h) / 2.0f) * 0.912f;
            c.drawCircle(cx, cy, radius, p);

            c.drawBitmap(glyph, 0, 0, null);

            synchronized (cache) {
                cache.put(glyph, composite);
            }
            return composite;
        } catch (Throwable t) {
            return glyph;
        }
    }

    private static Bitmap resolveThemedIconFromItemInfo(Object itemInfo) {
        if (itemInfo == null) return null;
        if (appIconsByPackage.isEmpty()) {
            populateAppIconCacheOnDemand();
        }
        try {
            ComponentName cn = null;
            try {
                cn = (ComponentName) XposedHelpers.getObjectField(itemInfo, "componentName");
            } catch (Throwable ignored) {}
            if (cn == null) {
                try {
                    Intent intent = (Intent) XposedHelpers.getObjectField(itemInfo, "intent");
                    if (intent != null) {
                        cn = intent.getComponent();
                    }
                } catch (Throwable ignored) {}
            }

            CharSequence titleSeq = null;
            try {
                titleSeq = (CharSequence) XposedHelpers.getObjectField(itemInfo, "title");
            } catch (Throwable ignored) {}

            if (cn != null) {
                Bitmap bmp = appIconsByComponent.get(cn.flattenToString());
                if (bmp != null) return bmp;
                if (cn.getClassName() != null) {
                    bmp = appIconsByComponent.get(cn.getClassName());
                    if (bmp != null) return bmp;
                }
                if (cn.getPackageName() != null) {
                    bmp = appIconsByPackage.get(cn.getPackageName());
                    if (bmp != null) return bmp;
                }
            }
            if (titleSeq != null) {
                String t = titleSeq.toString().trim().toLowerCase();
                if (!t.isEmpty()) {
                    Bitmap bmp = appIconsByTitle.get(t);
                    if (bmp != null) return bmp;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void ensureItemInfoHasThemedIcon(Object itemInfo) {
        if (itemInfo == null) return;
        try {
            Bitmap themed = resolveThemedIconFromItemInfo(itemInfo);
            if (themed != null) {
                Object bitmapInfo = null;
                try {
                    bitmapInfo = XposedHelpers.getObjectField(itemInfo, "bitmap");
                } catch (Throwable ignored) {}
                if (bitmapInfo == null) {
                    try {
                        bitmapInfo = XposedHelpers.getObjectField(itemInfo, "bitmapInfo");
                    } catch (Throwable ignored) {}
                }
                if (bitmapInfo != null) {
                    Bitmap current = (Bitmap) XposedHelpers.getObjectField(bitmapInfo, "icon");
                    if (current != themed) {
                        XposedHelpers.setObjectField(bitmapInfo, "icon", themed);
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in ensureItemInfoHasThemedIcon: " + t.getMessage());
        }
    }

    private static void ensureViewHasThemedIcon(TextView view) {
        if (view == null || themedViews.contains(view)) return;
        try {
            Object tag = view.getTag();
            Bitmap themed = null;
            if (tag != null) {
                ensureItemInfoHasThemedIcon(tag);
                themed = resolveThemedIconFromItemInfo(tag);
            }
            if (themed == null && view.getText() != null) {
                String t = view.getText().toString().trim().toLowerCase();
                if (!t.isEmpty()) {
                    themed = appIconsByTitle.get(t);
                }
            }
            if (themed != null) {
                Drawable[] cds = view.getCompoundDrawables();
                if (cds != null && cds.length > 1 && cds[1] != null) {
                    Drawable d = cds[1];
                    try {
                        Object info = XposedHelpers.getObjectField(d, "bitmapInfo");
                        if (info != null) {
                            Bitmap curr = (Bitmap) XposedHelpers.getObjectField(info, "icon");
                            if (curr != themed) {
                                XposedHelpers.setObjectField(info, "icon", themed);
                            }
                        }
                    } catch (Throwable ignored) {}
                    try {
                        Bitmap curr = (Bitmap) XposedHelpers.getObjectField(d, "mBitmap");
                        if (curr != themed) {
                            XposedHelpers.setObjectField(d, "mBitmap", themed);
                        }
                    } catch (Throwable ignored) {}
                    try {
                        Object delegate = XposedHelpers.getObjectField(d, "delegate");
                        if (delegate != null) {
                            try {
                                Object delInfo = XposedHelpers.getObjectField(delegate, "bitmapInfo");
                                if (delInfo != null) {
                                    XposedHelpers.setObjectField(delInfo, "icon", themed);
                                }
                            } catch (Throwable ignored) {}
                            try {
                                XposedHelpers.setObjectField(delegate, "monoIcon", themed);
                            } catch (Throwable ignored) {}
                            try {
                                Paint pp = (Paint) XposedHelpers.getObjectField(delegate, "platePaint");
                                if (pp != null && pp.getAlpha() != 0) pp.setAlpha(0);
                            } catch (Throwable ignored) {}
                            try {
                                Paint gp = (Paint) XposedHelpers.getObjectField(delegate, "glowPaint");
                                if (gp != null && gp.getAlpha() != 0) gp.setAlpha(0);
                            } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}
                }
                themedViews.add(view);
            }
        } catch (Throwable ignored) {}
    }

    private static Bitmap resolveThemedIconForSearchResult(Object sar) {
        if (sar == null) return null;
        try {
            if (appIconsByPackage.isEmpty()) {
                populateAppIconCacheOnDemand();
            }

            try {
                Object appInfo = XposedHelpers.getObjectField(sar, "d");
                if (appInfo != null) {
                    Bitmap bmp = resolveThemedIconFromItemInfo(appInfo);
                    if (bmp != null) return bmp;
                }
            } catch (Throwable ignored) {}

            Intent intent = null;
            try {
                intent = (Intent) XposedHelpers.getObjectField(sar, "detailIntent");
            } catch (Throwable ignored) {}
            if (intent == null) {
                try {
                    intent = (Intent) XposedHelpers.getObjectField(sar, "intent");
                } catch (Throwable ignored) {}
            }
            if (intent != null) {
                ComponentName cn = intent.getComponent();
                if (cn != null) {
                    Bitmap bmp = appIconsByComponent.get(cn.flattenToString());
                    if (bmp != null) return bmp;
                    bmp = appIconsByComponent.get(cn.getClassName());
                    if (bmp != null) return bmp;
                    bmp = appIconsByPackage.get(cn.getPackageName());
                    if (bmp != null) return bmp;
                }
                String pkg = intent.getPackage();
                if (pkg != null) {
                    Bitmap bmp = appIconsByPackage.get(pkg);
                    if (bmp != null) return bmp;
                }
            }

            String id = null;
            try {
                id = (String) XposedHelpers.getObjectField(sar, "id");
            } catch (Throwable ignored) {}
            if (id != null) {
                Bitmap bmp = appIconsByComponent.get(id);
                if (bmp != null) return bmp;
                bmp = appIconsByPackage.get(id);
                if (bmp != null) return bmp;

                if (id.contains("#")) {
                    String baseId = id.substring(0, id.indexOf('#'));
                    bmp = appIconsByComponent.get(baseId);
                    if (bmp != null) return bmp;
                    bmp = appIconsByPackage.get(baseId);
                    if (bmp != null) return bmp;
                }
                if (id.contains("/")) {
                    String pkgPart = id.substring(0, id.indexOf('/'));
                    bmp = appIconsByPackage.get(pkgPart);
                    if (bmp != null) return bmp;
                }
            }

            String title = null;
            try {
                title = (String) XposedHelpers.getObjectField(sar, "title");
            } catch (Throwable ignored) {}
            if (title != null) {
                String t = title.trim().toLowerCase();
                Bitmap bmp = appIconsByTitle.get(t);
                if (bmp != null) return bmp;
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in resolveThemedIconForSearchResult: " + t.getMessage());
        }
        return null;
    }

    private static void populateFromAllAppsStore(Object store) {
        if (store == null) return;
        try {
            Object[] apps = (Object[]) XposedHelpers.getObjectField(store, "mApps");
            if (apps != null && apps.length > 0) {
                for (Object app : apps) {
                    cacheThemedAppIcon(app);
                }
                Log.i(TAG, "Cached " + appIconsByPackage.size() + " themed app icons from AllAppsStore (" + apps.length + " apps)");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to read mApps from AllAppsStore: " + t.getMessage());
        }
    }

    private static void populateAppIconCacheOnDemand() {
        if (allAppsStoreInstance != null) {
            populateFromAllAppsStore(allAppsStoreInstance);
            if (!appIconsByPackage.isEmpty()) return;
        }

        if (launcherClassLoader == null) return;
        try {
            Class<?> atClass = XposedHelpers.findClass("android.app.ActivityThread", null);
            Context ctx = (Context) XposedHelpers.callStaticMethod(atClass, "currentApplication");
            if (ctx != null) {
                try {
                    Class<?> qdkClass = XposedHelpers.findClass("qd.k", launcherClassLoader);
                    Object companion = XposedHelpers.getStaticObjectField(qdkClass, "t");
                    if (companion != null) {
                        Object qdInstance = XposedHelpers.callMethod(companion, "a", ctx);
                        if (qdInstance != null) {
                            List<?> appsList = (List<?>) XposedHelpers.getObjectField(qdInstance, "c");
                            if (appsList != null && !appsList.isEmpty()) {
                                for (Object app : appsList) {
                                    cacheThemedAppIcon(app);
                                }
                                Log.i(TAG, "Populated " + appIconsByPackage.size() + " icons on-demand from qd.k");
                                return;
                            }
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "qd.k lookup failed: " + t.getMessage());
                }

                try {
                    Class<?> lasClass = XposedHelpers.findClass("com.android.launcher3.LauncherAppState", launcherClassLoader);
                    Object appState = XposedHelpers.callStaticMethod(lasClass, "getInstance", ctx);
                    if (appState != null) {
                        Object model = XposedHelpers.callMethod(appState, "getModel");
                        if (model != null) {
                            for (Field f : model.getClass().getDeclaredFields()) {
                                if (f.getName().toLowerCase().contains("app") || f.getName().toLowerCase().contains("bg")) {
                                    f.setAccessible(true);
                                    Object val = f.get(model);
                                    if (val instanceof Object[]) {
                                        for (Object app : (Object[]) val) cacheThemedAppIcon(app);
                                    } else if (val instanceof List) {
                                        for (Object app : (List<?>) val) cacheThemedAppIcon(app);
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "LauncherAppState lookup failed: " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "populateAppIconCacheOnDemand failed: " + t.getMessage());
        }
    }
}
