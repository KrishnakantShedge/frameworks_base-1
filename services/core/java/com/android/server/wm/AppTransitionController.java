/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.TRANSIT_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_ACTIVITY_OPEN;
import static android.view.WindowManager.TRANSIT_ACTIVITY_RELAUNCH;
import static android.view.WindowManager.TRANSIT_CRASHING_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_DOCK_TASK_FROM_RECENTS;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_SUBTLE_ANIMATION;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER;
import static android.view.WindowManager.TRANSIT_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_KEYGUARD_GOING_AWAY_ON_WALLPAPER;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_SHOW_SINGLE_TASK_DISPLAY;
import static android.view.WindowManager.TRANSIT_TASK_CLOSE;
import static android.view.WindowManager.TRANSIT_TASK_OPEN;
import static android.view.WindowManager.TRANSIT_TASK_TO_BACK;
import static android.view.WindowManager.TRANSIT_TASK_TO_FRONT;
import static android.view.WindowManager.TRANSIT_TRANSLUCENT_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_TRANSLUCENT_ACTIVITY_OPEN;
import static android.view.WindowManager.TRANSIT_WALLPAPER_CLOSE;
import static android.view.WindowManager.TRANSIT_WALLPAPER_INTRA_CLOSE;
import static android.view.WindowManager.TRANSIT_WALLPAPER_INTRA_OPEN;
import static android.view.WindowManager.TRANSIT_WALLPAPER_OPEN;

import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_CONFIG;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT;
import static com.android.server.wm.ActivityTaskManagerInternal.APP_TRANSITION_SNAPSHOT;
import static com.android.server.wm.ActivityTaskManagerInternal.APP_TRANSITION_SPLASH_SCREEN;
import static com.android.server.wm.ActivityTaskManagerInternal.APP_TRANSITION_WINDOWS_DRAWN;
import static com.android.server.wm.AppTransition.isKeyguardGoingAwayTransit;
import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_APP_TRANSITIONS;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.os.SystemClock;
import android.os.Trace;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationDefinition;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.animation.Animation;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.protolog.common.ProtoLog;

import java.util.function.Predicate;


/**
 * Checks for app transition readiness, resolves animation attributes and performs visibility
 * change for apps that animate as part of an app transition.
 */
public class AppTransitionController {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "AppTransitionController" : TAG_WM;
    private final WindowManagerService mService;
    private final DisplayContent mDisplayContent;
    private final WallpaperController mWallpaperControllerLocked;
    private RemoteAnimationDefinition mRemoteAnimationDefinition = null;

    private final SparseIntArray mTempTransitionReasons = new SparseIntArray();

    AppTransitionController(WindowManagerService service, DisplayContent displayContent) {
        mService = service;
        mDisplayContent = displayContent;
        mWallpaperControllerLocked = mDisplayContent.mWallpaperController;
    }

    void registerRemoteAnimations(RemoteAnimationDefinition definition) {
        mRemoteAnimationDefinition = definition;
    }

    /**
     * Handle application transition for given display.
     */
    void handleAppTransitionReady() {
        mTempTransitionReasons.clear();
        if (!transitionGoodToGo(mDisplayContent.mOpeningApps, mTempTransitionReasons)
                || !transitionGoodToGo(mDisplayContent.mChangingApps, mTempTransitionReasons)) {
            return;
        }
        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "AppTransitionReady");

        ProtoLog.v(WM_DEBUG_APP_TRANSITIONS, "**** GOOD TO GO");
        final AppTransition appTransition = mDisplayContent.mAppTransition;
        int transit = appTransition.getAppTransition();
        if (mDisplayContent.mSkipAppTransitionAnimation && !isKeyguardGoingAwayTransit(transit)) {
            transit = WindowManager.TRANSIT_UNSET;
        }
        mDisplayContent.mSkipAppTransitionAnimation = false;
        mDisplayContent.mNoAnimationNotifyOnTransitionFinished.clear();

        appTransition.removeAppTransitionTimeoutCallbacks();

        mDisplayContent.mWallpaperMayChange = false;

        int appCount = mDisplayContent.mOpeningApps.size();
        for (int i = 0; i < appCount; ++i) {
            // Clearing the mAnimatingExit flag before entering animation. It's set to true if app
            // window is removed, or window relayout to invisible. This also affects window
            // visibility. We need to clear it *before* maybeUpdateTransitToWallpaper() as the
            // transition selection depends on wallpaper target visibility.
            mDisplayContent.mOpeningApps.valueAtUnchecked(i).clearAnimatingFlags();
        }
        appCount = mDisplayContent.mChangingApps.size();
        for (int i = 0; i < appCount; ++i) {
            // Clearing for same reason as above.
            mDisplayContent.mChangingApps.valueAtUnchecked(i).clearAnimatingFlags();
        }

        // Adjust wallpaper before we pull the lower/upper target, since pending changes
        // (like the clearAnimatingFlags() above) might affect wallpaper target result.
        // Or, the opening app window should be a wallpaper target.
        mWallpaperControllerLocked.adjustWallpaperWindowsForAppTransitionIfNeeded(
                mDisplayContent.mOpeningApps, mDisplayContent.mChangingApps);

        // Determine if closing and opening app token sets are wallpaper targets, in which case
        // special animations are needed.
        final boolean hasWallpaperTarget = mWallpaperControllerLocked.getWallpaperTarget() != null;
        final boolean openingAppHasWallpaper = canBeWallpaperTarget(mDisplayContent.mOpeningApps)
                && hasWallpaperTarget;
        final boolean closingAppHasWallpaper = canBeWallpaperTarget(mDisplayContent.mClosingApps)
                && hasWallpaperTarget;

        transit = maybeUpdateTransitToTranslucentAnim(transit);
        transit = maybeUpdateTransitToWallpaper(transit, openingAppHasWallpaper,
                closingAppHasWallpaper);

        // Find the layout params of the top-most application window in the tokens, which is
        // what will control the animation theme. If all closing windows are obscured, then there is
        // no need to do an animation. This is the case, for example, when this transition is being
        // done behind a dream window.
        final ArraySet<Integer> activityTypes = collectActivityTypes(mDisplayContent.mOpeningApps,
                mDisplayContent.mClosingApps, mDisplayContent.mChangingApps);
        final boolean allowAnimations = mDisplayContent.getDisplayPolicy().allowAppAnimationsLw();
        final ActivityRecord animLpActivity = allowAnimations
                ? findAnimLayoutParamsToken(transit, activityTypes)
                : null;
        final ActivityRecord topOpeningApp = allowAnimations
                ? getTopApp(mDisplayContent.mOpeningApps, false /* ignoreHidden */)
                : null;
        final ActivityRecord topClosingApp = allowAnimations
                ? getTopApp(mDisplayContent.mClosingApps, false /* ignoreHidden */)
                : null;
        final ActivityRecord topChangingApp = allowAnimations
                ? getTopApp(mDisplayContent.mChangingApps, false /* ignoreHidden */)
                : null;
        final WindowManager.LayoutParams animLp = getAnimLp(animLpActivity);
        overrideWithRemoteAnimationIfSet(animLpActivity, transit, activityTypes);

        final boolean voiceInteraction = containsVoiceInteraction(mDisplayContent.mOpeningApps)
                || containsVoiceInteraction(mDisplayContent.mOpeningApps)
                || containsVoiceInteraction(mDisplayContent.mChangingApps);

        final int layoutRedo;
        mService.mSurfaceAnimationRunner.deferStartingAnimations();
        try {
            handleClosingApps(transit, animLp, voiceInteraction);
            handleOpeningApps(transit, animLp, voiceInteraction);
            handleChangingApps(transit, animLp, voiceInteraction);

            appTransition.setLastAppTransition(transit, topOpeningApp,
                    topClosingApp, topChangingApp);

            final int flags = appTransition.getTransitFlags();
            layoutRedo = appTransition.goodToGo(transit, topOpeningApp,
                    mDisplayContent.mOpeningApps);
            handleNonAppWindowsInTransition(transit, flags);
            appTransition.postAnimationCallback();
            appTransition.clear();
        } finally {
            mService.mSurfaceAnimationRunner.continueStartingAnimations();
        }

        mService.mTaskSnapshotController.onTransitionStarting(mDisplayContent);

        mDisplayContent.mOpeningApps.clear();
        mDisplayContent.mClosingApps.clear();
        mDisplayContent.mChangingApps.clear();
        mDisplayContent.mUnknownAppVisibilityController.clear();

        // This has changed the visibility of windows, so perform
        // a new layout to get them all up-to-date.
        mDisplayContent.setLayoutNeeded();

        mDisplayContent.computeImeTarget(true /* updateImeTarget */);

        mService.mAtmInternal.notifyAppTransitionStarting(mTempTransitionReasons.clone(),
            SystemClock.elapsedRealtimeNanos());

        if (transit == TRANSIT_SHOW_SINGLE_TASK_DISPLAY) {
            mService.mAnimator.addAfterPrepareSurfacesRunnable(() -> {
                mService.mAtmInternal.notifySingleTaskDisplayDrawn(mDisplayContent.getDisplayId());
            });
        }

        Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);

        mDisplayContent.pendingLayoutChanges |=
                layoutRedo | FINISH_LAYOUT_REDO_LAYOUT | FINISH_LAYOUT_REDO_CONFIG;
    }

    private static WindowManager.LayoutParams getAnimLp(ActivityRecord activity) {
        final WindowState mainWindow = activity != null ? activity.findMainWindow() : null;
        return mainWindow != null ? mainWindow.mAttrs : null;
    }

    RemoteAnimationAdapter getRemoteAnimationOverride(ActivityRecord animLpActivity, int transit,
            ArraySet<Integer> activityTypes) {
        final RemoteAnimationDefinition definition = animLpActivity.getRemoteAnimationDefinition();
        if (definition != null) {
            final RemoteAnimationAdapter adapter = definition.getAdapter(transit, activityTypes);
            if (adapter != null) {
                return adapter;
            }
        }
        if (mRemoteAnimationDefinition == null) {
            return null;
        }
        return mRemoteAnimationDefinition.getAdapter(transit, activityTypes);
    }

    /**
     * Overrides the pending transition with the remote animation defined for the transition in the
     * set of defined remote animations in the app window token.
     */
    private void overrideWithRemoteAnimationIfSet(ActivityRecord animLpActivity, int transit,
            ArraySet<Integer> activityTypes) {
        if (transit == TRANSIT_CRASHING_ACTIVITY_CLOSE) {
            // The crash transition has higher priority than any involved remote animations.
            return;
        }
        if (animLpActivity == null) {
            return;
        }
        final RemoteAnimationAdapter adapter =
                getRemoteAnimationOverride(animLpActivity, transit, activityTypes);
        if (adapter != null) {
            animLpActivity.getDisplayContent().mAppTransition.overridePendingAppTransitionRemote(
                    adapter);
        }
    }

    /**
     * @return The window token that determines the animation theme.
     */
    private ActivityRecord findAnimLayoutParamsToken(@WindowManager.TransitionType int transit,
            ArraySet<Integer> activityTypes) {
        ActivityRecord result;
        final ArraySet<ActivityRecord> closingApps = mDisplayContent.mClosingApps;
        final ArraySet<ActivityRecord> openingApps = mDisplayContent.mOpeningApps;
        final ArraySet<ActivityRecord> changingApps = mDisplayContent.mChangingApps;

        // Remote animations always win, but fullscreen tokens override non-fullscreen tokens.
        result = lookForHighestTokenWithFilter(closingApps, openingApps, changingApps,
                w -> w.getRemoteAnimationDefinition() != null
                        && w.getRemoteAnimationDefinition().hasTransition(transit, activityTypes));
        if (result != null) {
            return result;
        }
        result = lookForHighestTokenWithFilter(closingApps, openingApps, changingApps,
                w -> w.fillsParent() && w.findMainWindow() != null);
        if (result != null) {
            return result;
        }
        return lookForHighestTokenWithFilter(closingApps, openingApps, changingApps,
                w -> w.findMainWindow() != null);
    }

    /**
     * @return The set of {@link android.app.WindowConfiguration.ActivityType}s contained in the set
     *         of apps in {@code array1}, {@code array2}, and {@code array3}.
     */
    private static ArraySet<Integer> collectActivityTypes(ArraySet<ActivityRecord> array1,
            ArraySet<ActivityRecord> array2, ArraySet<ActivityRecord> array3) {
        final ArraySet<Integer> result = new ArraySet<>();
        for (int i = array1.size() - 1; i >= 0; i--) {
            result.add(array1.valueAt(i).getActivityType());
        }
        for (int i = array2.size() - 1; i >= 0; i--) {
            result.add(array2.valueAt(i).getActivityType());
        }
        for (int i = array3.size() - 1; i >= 0; i--) {
            result.add(array3.valueAt(i).getActivityType());
        }
        return result;
    }

    private static ActivityRecord lookForHighestTokenWithFilter(ArraySet<ActivityRecord> array1,
            ArraySet<ActivityRecord> array2, ArraySet<ActivityRecord> array3,
            Predicate<ActivityRecord> filter) {
        final int array2base = array1.size();
        final int array3base = array2.size() + array2base;
        final int count = array3base + array3.size();
        int bestPrefixOrderIndex = Integer.MIN_VALUE;
        ActivityRecord bestToken = null;
        for (int i = 0; i < count; i++) {
            final ActivityRecord wtoken = i < array2base
                    ? array1.valueAt(i)
                    : (i < array3base
                            ? array2.valueAt(i - array2base)
                            : array3.valueAt(i - array3base));
            final int prefixOrderIndex = wtoken.getPrefixOrderIndex();
            if (filter.test(wtoken) && prefixOrderIndex > bestPrefixOrderIndex) {
                bestPrefixOrderIndex = prefixOrderIndex;
                bestToken = wtoken;
            }
        }
        return bestToken;
    }

    private boolean containsVoiceInteraction(ArraySet<ActivityRecord> apps) {
        for (int i = apps.size() - 1; i >= 0; i--) {
            if (apps.valueAt(i).mVoiceInteraction) {
                return true;
            }
        }
        return false;
    }

    private void handleOpeningApps(int transit, LayoutParams animLp, boolean voiceInteraction) {
        final ArraySet<ActivityRecord> openingApps = mDisplayContent.mOpeningApps;
        final int appsCount = openingApps.size();
        for (int i = 0; i < appsCount; i++) {
            ActivityRecord wtoken = openingApps.valueAt(i);
            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS, "Now opening app %s", wtoken);

            if (!wtoken.commitVisibility(animLp, true, transit, false, voiceInteraction)) {
                // This token isn't going to be animating. Add it to the list of tokens to
                // be notified of app transition complete since the notification will not be
                // sent be the app window animator.
                mDisplayContent.mNoAnimationNotifyOnTransitionFinished.add(wtoken.token);
            }
            wtoken.updateReportedVisibilityLocked();
            wtoken.waitingToShow = false;
            if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                    ">>> OPEN TRANSACTION handleAppTransitionReady()");
            mService.openSurfaceTransaction();
            try {
                wtoken.showAllWindowsLocked();
            } finally {
                mService.closeSurfaceTransaction("handleAppTransitionReady");
                if (SHOW_LIGHT_TRANSACTIONS) Slog.i(TAG,
                        "<<< CLOSE TRANSACTION handleAppTransitionReady()");
            }

            if (mDisplayContent.mAppTransition.isNextAppTransitionThumbnailUp()) {
                wtoken.attachThumbnailAnimation();
            } else if (mDisplayContent.mAppTransition.isNextAppTransitionOpenCrossProfileApps()) {
                wtoken.attachCrossProfileAppsThumbnailAnimation();
            }
        }
    }

    private void handleClosingApps(int transit, LayoutParams animLp, boolean voiceInteraction) {
        final ArraySet<ActivityRecord> closingApps = mDisplayContent.mClosingApps;
        final int appsCount = closingApps.size();
        for (int i = 0; i < appsCount; i++) {
            ActivityRecord wtoken = closingApps.valueAt(i);

            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS, "Now closing app %s", wtoken);
            // TODO: Do we need to add to mNoAnimationNotifyOnTransitionFinished like above if not
            //       animating?
            wtoken.commitVisibility(animLp, false, transit, false, voiceInteraction);
            wtoken.updateReportedVisibilityLocked();
            // Force the allDrawn flag, because we want to start
            // this guy's animations regardless of whether it's
            // gotten drawn.
            wtoken.allDrawn = true;
            wtoken.deferClearAllDrawn = false;
            // Ensure that apps that are mid-starting are also scheduled to have their
            // starting windows removed after the animation is complete
            if (wtoken.startingWindow != null && !wtoken.startingWindow.mAnimatingExit) {
                wtoken.removeStartingWindow();
            }

            if (mDisplayContent.mAppTransition.isNextAppTransitionThumbnailDown()) {
                wtoken.attachThumbnailAnimation();
            }
        }
    }

    private void handleChangingApps(int transit, LayoutParams animLp, boolean voiceInteraction) {
        final ArraySet<ActivityRecord> apps = mDisplayContent.mChangingApps;
        final int appsCount = apps.size();
        for (int i = 0; i < appsCount; i++) {
            ActivityRecord activity = apps.valueAt(i);
            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS, "Now changing app %s", activity);
            activity.cancelAnimationOnly();
            activity.applyAnimation(null, transit, true, false);
            activity.updateReportedVisibilityLocked();
            mService.openSurfaceTransaction();
            try {
                activity.showAllWindowsLocked();
            } finally {
                mService.closeSurfaceTransaction("handleChangingApps");
            }
        }
    }

    private void handleNonAppWindowsInTransition(int transit, int flags) {
        if (transit == TRANSIT_KEYGUARD_GOING_AWAY) {
            if ((flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER) != 0
                    && (flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION) == 0
                    && (flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_SUBTLE_ANIMATION) == 0) {
                Animation anim = mService.mPolicy.createKeyguardWallpaperExit(
                        (flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE) != 0);
                if (anim != null) {
                    mDisplayContent.mWallpaperController.startWallpaperAnimation(anim);
                }
            }
        }
        if (transit == TRANSIT_KEYGUARD_GOING_AWAY
                || transit == TRANSIT_KEYGUARD_GOING_AWAY_ON_WALLPAPER) {
            mDisplayContent.startKeyguardExitOnNonAppWindows(
                    transit == TRANSIT_KEYGUARD_GOING_AWAY_ON_WALLPAPER,
                    (flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE) != 0,
                    (flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_SUBTLE_ANIMATION) != 0);
        }
    }

    private boolean transitionGoodToGo(ArraySet<ActivityRecord> apps, SparseIntArray outReasons) {
        ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                "Checking %d opening apps (frozen=%b timeout=%b)...", apps.size(),
                mService.mDisplayFrozen, mDisplayContent.mAppTransition.isTimeout());

        final ScreenRotationAnimation screenRotationAnimation = mService.mRoot.getDisplayContent(
                Display.DEFAULT_DISPLAY).getRotationAnimation();

        if (!mDisplayContent.mAppTransition.isTimeout()) {
            // Imagine the case where we are changing orientation due to an app transition, but a
            // previous orientation change is still in progress. We won't process the orientation
            // change for our transition because we need to wait for the rotation animation to
            // finish.
            // If we start the app transition at this point, we will interrupt it halfway with a
            // new rotation animation after the old one finally finishes. It's better to defer the
            // app transition.
            if (screenRotationAnimation != null && screenRotationAnimation.isAnimating() &&
                    mDisplayContent.getDisplayRotation().needsUpdate()) {
                ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                        "Delaying app transition for screen rotation animation to finish");
                return false;
            }
            for (int i = 0; i < apps.size(); i++) {
                ActivityRecord activity = apps.valueAt(i);
                ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                                "Check opening app=%s: allDrawn=%b startingDisplayed=%b "
                                        + "startingMoved=%b isRelaunching()=%b startingWindow=%s",
                                activity, activity.allDrawn, activity.startingDisplayed,
                                activity.startingMoved, activity.isRelaunching(),
                                activity.startingWindow);


                final boolean allDrawn = activity.allDrawn && !activity.isRelaunching();
                if (!allDrawn && !activity.startingDisplayed && !activity.startingMoved) {
                    return false;
                }
                final int windowingMode = activity.getWindowingMode();
                if (allDrawn) {
                    outReasons.put(windowingMode,  APP_TRANSITION_WINDOWS_DRAWN);
                } else {
                    outReasons.put(windowingMode,
                            activity.mStartingData instanceof SplashScreenStartingData
                                    ? APP_TRANSITION_SPLASH_SCREEN
                                    : APP_TRANSITION_SNAPSHOT);
                }
            }

            // We also need to wait for the specs to be fetched, if needed.
            if (mDisplayContent.mAppTransition.isFetchingAppTransitionsSpecs()) {
                ProtoLog.v(WM_DEBUG_APP_TRANSITIONS, "isFetchingAppTransitionSpecs=true");
                return false;
            }

            if (!mDisplayContent.mUnknownAppVisibilityController.allResolved()) {
                ProtoLog.v(WM_DEBUG_APP_TRANSITIONS, "unknownApps is not empty: %s",
                            mDisplayContent.mUnknownAppVisibilityController.getDebugMessage());
                return false;
            }

            // If the wallpaper is visible, we need to check it's ready too.
            boolean wallpaperReady = !mWallpaperControllerLocked.isWallpaperVisible() ||
                    mWallpaperControllerLocked.wallpaperTransitionReady();
            if (wallpaperReady) {
                return true;
            }
            return false;
        }
        return true;
    }

    private int maybeUpdateTransitToWallpaper(int transit, boolean openingAppHasWallpaper,
            boolean closingAppHasWallpaper) {
        // Given no app transition pass it through instead of a wallpaper transition.
        // Never convert the crashing transition.
        // Never update the transition for the wallpaper if we are just docking from recents
        // Never convert a change transition since the top activity isn't changing and will likely
        // still be above an opening wallpaper.
        if (transit == TRANSIT_NONE || transit == TRANSIT_CRASHING_ACTIVITY_CLOSE
                || transit == TRANSIT_DOCK_TASK_FROM_RECENTS
                || AppTransition.isChangeTransit(transit)) {
            return transit;
        }

        final WindowState wallpaperTarget = mWallpaperControllerLocked.getWallpaperTarget();
        final boolean showWallpaper = wallpaperTarget != null
                && (wallpaperTarget.mAttrs.flags & FLAG_SHOW_WALLPAPER) != 0;
        // If wallpaper is animating or wallpaperTarget doesn't have SHOW_WALLPAPER flag set,
        // don't consider upgrading to wallpaper transition.
        final WindowState oldWallpaper =
                (mWallpaperControllerLocked.isWallpaperTargetAnimating() || !showWallpaper)
                        ? null
                        : wallpaperTarget;
        final ArraySet<ActivityRecord> openingApps = mDisplayContent.mOpeningApps;
        final ArraySet<ActivityRecord> closingApps = mDisplayContent.mClosingApps;
        final ActivityRecord topOpeningApp = getTopApp(mDisplayContent.mOpeningApps,
                false /* ignoreHidden */);
        final ActivityRecord topClosingApp = getTopApp(mDisplayContent.mClosingApps,
                true /* ignoreHidden */);

        boolean openingCanBeWallpaperTarget = canBeWallpaperTarget(openingApps);
        ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                        "New wallpaper target=%s, oldWallpaper=%s, openingApps=%s, closingApps=%s",
                        wallpaperTarget, oldWallpaper, openingApps, closingApps);

        if (openingCanBeWallpaperTarget && transit == TRANSIT_KEYGUARD_GOING_AWAY) {
            transit = TRANSIT_KEYGUARD_GOING_AWAY_ON_WALLPAPER;
            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                    "New transit: %s", AppTransition.appTransitionToString(transit));
        }
        // We never want to change from a Keyguard transit to a non-Keyguard transit, as our logic
        // relies on the fact that we always execute a Keyguard transition after preparing one.
        else if (!isKeyguardGoingAwayTransit(transit)) {
            if (closingAppHasWallpaper && openingAppHasWallpaper) {
                ProtoLog.v(WM_DEBUG_APP_TRANSITIONS, "Wallpaper animation!");
                switch (transit) {
                    case TRANSIT_ACTIVITY_OPEN:
                    case TRANSIT_TASK_OPEN:
                    case TRANSIT_TASK_TO_FRONT:
                        transit = TRANSIT_WALLPAPER_INTRA_OPEN;
                        break;
                    case TRANSIT_ACTIVITY_CLOSE:
                    case TRANSIT_TASK_CLOSE:
                    case TRANSIT_TASK_TO_BACK:
                        transit = TRANSIT_WALLPAPER_INTRA_CLOSE;
                        break;
                }
                ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                        "New transit: %s", AppTransition.appTransitionToString(transit));
            } else if (oldWallpaper != null && !mDisplayContent.mOpeningApps.isEmpty()
                    && !openingApps.contains(oldWallpaper.mActivityRecord)
                    && closingApps.contains(oldWallpaper.mActivityRecord)
                    && topClosingApp == oldWallpaper.mActivityRecord) {
                // We are transitioning from an activity with a wallpaper to one without.
                transit = TRANSIT_WALLPAPER_CLOSE;
                ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                        "New transit away from wallpaper: %s",
                                AppTransition.appTransitionToString(transit));
            } else if (wallpaperTarget != null && wallpaperTarget.isVisibleLw()
                    && openingApps.contains(wallpaperTarget.mActivityRecord)
                    && topOpeningApp == wallpaperTarget.mActivityRecord
                    && transit != TRANSIT_TRANSLUCENT_ACTIVITY_CLOSE) {
                // We are transitioning from an activity without
                // a wallpaper to now showing the wallpaper
                transit = TRANSIT_WALLPAPER_OPEN;
                ProtoLog.v(WM_DEBUG_APP_TRANSITIONS, "New transit into wallpaper: %s",
                        AppTransition.appTransitionToString(transit));
            }
        }
        return transit;
    }

    /**
     * There are cases where we open/close a new task/activity, but in reality only a translucent
     * activity on top of existing activities is opening/closing. For that one, we have a different
     * animation because non of the task/activity animations actually work well with translucent
     * apps.
     *
     * @param transit The current transition type.
     * @return The current transition type or
     *         {@link WindowManager#TRANSIT_TRANSLUCENT_ACTIVITY_CLOSE}/
     *         {@link WindowManager#TRANSIT_TRANSLUCENT_ACTIVITY_OPEN} if appropriate for the
     *         situation.
     */
    @VisibleForTesting
    int maybeUpdateTransitToTranslucentAnim(int transit) {
        if (AppTransition.isChangeTransit(transit)) {
            // There's no special animation to handle change animations with translucent apps
            return transit;
        }
        final boolean taskOrActivity = AppTransition.isTaskTransit(transit)
                || AppTransition.isActivityTransit(transit);
        boolean allOpeningVisible = true;
        boolean allTranslucentOpeningApps = !mDisplayContent.mOpeningApps.isEmpty();
        for (int i = mDisplayContent.mOpeningApps.size() - 1; i >= 0; i--) {
            final ActivityRecord activity = mDisplayContent.mOpeningApps.valueAt(i);
            if (!activity.isVisible()) {
                allOpeningVisible = false;
                if (activity.fillsParent()) {
                    allTranslucentOpeningApps = false;
                }
            }
        }
        boolean allTranslucentClosingApps = !mDisplayContent.mClosingApps.isEmpty();
        for (int i = mDisplayContent.mClosingApps.size() - 1; i >= 0; i--) {
            if (mDisplayContent.mClosingApps.valueAt(i).fillsParent()) {
                allTranslucentClosingApps = false;
                break;
            }
        }

        if (taskOrActivity && allTranslucentClosingApps && allOpeningVisible) {
            return TRANSIT_TRANSLUCENT_ACTIVITY_CLOSE;
        }
        if (taskOrActivity && allTranslucentOpeningApps && mDisplayContent.mClosingApps.isEmpty()) {
            return TRANSIT_TRANSLUCENT_ACTIVITY_OPEN;
        }
        return transit;
    }

    /**
     * Identifies whether the current transition occurs within a single task or not. This is used
     * to determine whether animations should be clipped to the task bounds instead of stack bounds.
     */
    @VisibleForTesting
    boolean isTransitWithinTask(int transit, Task task) {
        if (task == null
                || !mDisplayContent.mChangingApps.isEmpty()) {
            // if there is no task, then we can't constrain to the task.
            // if anything is changing, it can animate outside its task.
            return false;
        }
        if (!(transit == TRANSIT_ACTIVITY_OPEN
                || transit == TRANSIT_ACTIVITY_CLOSE
                || transit == TRANSIT_ACTIVITY_RELAUNCH)) {
            // only activity-level transitions will be within-task.
            return false;
        }
        // check that all components are in the task.
        for (ActivityRecord activity : mDisplayContent.mOpeningApps) {
            Task activityTask = activity.getTask();
            if (activityTask != task) {
                return false;
            }
        }
        for (ActivityRecord activity : mDisplayContent.mClosingApps) {
            if (activity.getTask() != task) {
                return false;
            }
        }
        return true;
    }

    private boolean canBeWallpaperTarget(ArraySet<ActivityRecord> apps) {
        for (int i = apps.size() - 1; i >= 0; i--) {
            if (apps.valueAt(i).windowsCanBeWallpaperTarget()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the top app in a list of apps, using its {@link ActivityRecord#getPrefixOrderIndex} to
     * compare z-order.
     *
     * @param apps The list of apps to search.
     * @param ignoreInvisible If set to true, ignores apps that are not
     *                        {@link ActivityRecord#isVisible}.
     * @return The top {@link ActivityRecord}.
     */
    private ActivityRecord getTopApp(ArraySet<ActivityRecord> apps, boolean ignoreInvisible) {
        int topPrefixOrderIndex = Integer.MIN_VALUE;
        ActivityRecord topApp = null;
        for (int i = apps.size() - 1; i >= 0; i--) {
            final ActivityRecord app = apps.valueAt(i);
            if (ignoreInvisible && !app.isVisible()) {
                continue;
            }
            final int prefixOrderIndex = app.getPrefixOrderIndex();
            if (prefixOrderIndex > topPrefixOrderIndex) {
                topPrefixOrderIndex = prefixOrderIndex;
                topApp = app;
            }
        }
        return topApp;
    }
}
