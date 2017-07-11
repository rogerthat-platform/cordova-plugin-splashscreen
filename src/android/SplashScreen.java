/*
       Licensed to the Apache Software Foundation (ASF) under one
       or more contributor license agreements.  See the NOTICE file
       distributed with this work for additional information
       regarding copyright ownership.  The ASF licenses this file
       to you under the Apache License, Version 2.0 (the
       "License"); you may not use this file except in compliance
       with the License.  You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

       Unless required by applicable law or agreed to in writing,
       software distributed under the License is distributed on an
       "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
       KIND, either express or implied.  See the License for the
       specific language governing permissions and limitations
       under the License.
*/

package org.apache.cordova.splashscreen;

import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AlphaAnimation;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;

import com.mobicage.rogerthat.cordova.CordovaActionScreenActivity;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;

public class SplashScreen extends CordovaPlugin {
    private static final String LOG_TAG = "SplashScreen";
    private static final int DEFAULT_SPLASHSCREEN_DURATION = 3000;
    private static final int DEFAULT_FADE_DURATION = 500;
    private static Dialog splashDialog;
    private static ProgressDialog spinnerDialog;
    private static boolean firstShow = true;
    private static boolean lastHideAfterDelay; // https://issues.apache.org/jira/browse/CB-9094

    /**
     * Displays the splash drawable.
     */
    private ImageView splashImageView;

    /**
     * Remember last device orientation to detect orientation changes.
     */
    private int orientation;

    // Helper to be compile-time compatible with both Cordova 3.x and 4.x.
    private View getView() {
        try {
            return (View)webView.getClass().getMethod("getView").invoke(webView);
        } catch (Exception e) {
            return (View)webView;
        }
    }

    @Override
    protected void pluginInitialize() {
        // Make WebView invisible while loading URL
        // CB-11326 Ensure we're calling this on UI thread
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                getView().setVisibility(View.INVISIBLE);
            }
        });

        // Save initial orientation.
        orientation = cordova.getActivity().getResources().getConfiguration().orientation;

        if (firstShow) {
            boolean autoHide = true;
            showSplashScreen(autoHide);
        }
    }

    /**
     * Shorter way to check value of "SplashMaintainAspectRatio" preference.
     */
    private boolean isMaintainAspectRatio () {
        return true;
    }

    private int getFadeDuration () {
        return 0;
    }

    @Override
    public void onPause(boolean multitasking) {
        // hide the splash screen to avoid leaking a window
        this.removeSplashScreen(true);
    }

    @Override
    public void onDestroy() {
        // hide the splash screen to avoid leaking a window
        this.removeSplashScreen(true);
        // If we set this to true onDestroy, we lose track when we go from page to page!
        //firstShow = true;
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (action.equals("hide")) {
            cordova.getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    webView.postMessage("splashscreen", "hide");
                }
            });
        } else if (action.equals("show")) {
            cordova.getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    webView.postMessage("splashscreen", "show");
                }
            });
        } else {
            return false;
        }

        callbackContext.success();
        return true;
    }

    @Override
    public Object onMessage(String id, Object data) {
        if ("splashscreen".equals(id)) {
            if ("hide".equals(data.toString())) {
                this.removeSplashScreen(false);
            } else {
                this.showSplashScreen(false);
            }
        } else if ("spinner".equals(id)) {
            if ("stop".equals(data.toString())) {
                getView().setVisibility(View.VISIBLE);
            }
        } else if ("onReceivedError".equals(id)) {
            this.spinnerStop();
        }
        return null;
    }

    private void removeSplashScreen(final boolean forceHideImmediately) {
        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                if (splashDialog != null && splashDialog.isShowing()) {
                    final int fadeSplashScreenDuration = getFadeDuration();
                    // CB-10692 If the plugin is being paused/destroyed, skip the fading and hide it immediately
                    if (fadeSplashScreenDuration > 0 && forceHideImmediately == false) {
                        AlphaAnimation fadeOut = new AlphaAnimation(1, 0);
                        fadeOut.setInterpolator(new DecelerateInterpolator());
                        fadeOut.setDuration(fadeSplashScreenDuration);

                        splashImageView.setAnimation(fadeOut);
                        splashImageView.startAnimation(fadeOut);

                        fadeOut.setAnimationListener(new Animation.AnimationListener() {
                            @Override
                            public void onAnimationStart(Animation animation) {
                                spinnerStop();
                            }

                            @Override
                            public void onAnimationEnd(Animation animation) {
                                if (splashDialog != null && splashDialog.isShowing()) {
                                    splashDialog.dismiss();
                                    splashDialog = null;
                                    splashImageView = null;
                                }
                            }

                            @Override
                            public void onAnimationRepeat(Animation animation) {
                            }
                        });
                    } else {
                        spinnerStop();
                        splashDialog.dismiss();
                        splashDialog = null;
                        splashImageView = null;
                    }
                }
            }
        });
    }

    /**
     * Shows the splash screen over the full Activity
     */
    @SuppressWarnings("deprecation")
    private void showSplashScreen(final boolean hideAfterDelay) {
        final int splashscreenTime = DEFAULT_SPLASHSCREEN_DURATION;

        final int fadeSplashScreenDuration = getFadeDuration();
        final int effectiveSplashDuration = Math.max(0, splashscreenTime - fadeSplashScreenDuration);

        lastHideAfterDelay = hideAfterDelay;

        // If the splash dialog is showing don't try to show it again
        if (splashDialog != null && splashDialog.isShowing()) {
            return;
        }

        final Drawable d = ((CordovaActionScreenActivity)cordova.getActivity()).getSplashScreenDrawable();

        if (d == null || splashscreenTime <= 0 && hideAfterDelay) {
            return;
        }

        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                // Get reference to display
                Display display = cordova.getActivity().getWindowManager().getDefaultDisplay();
                Context context = webView.getContext();

                // Use an ImageView to render the image because of its flexible scaling options.
                splashImageView = new ImageView(context);
                splashImageView.setImageDrawable(d);

                LayoutParams layoutParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
                splashImageView.setLayoutParams(layoutParams);

                splashImageView.setMinimumHeight(display.getHeight());
                splashImageView.setMinimumWidth(display.getWidth());

                // TODO: Use the background color of the webView's parent instead of using the preference.
                splashImageView.setBackgroundColor(Color.BLACK);

                if (isMaintainAspectRatio()) {
                    // CENTER_CROP scale mode is equivalent to CSS "background-size:cover"
                    splashImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                }
                else {
                    // FIT_XY scales image non-uniformly to fit into image view.
                    splashImageView.setScaleType(ImageView.ScaleType.FIT_XY);
                }

                // Create and show the dialog
                splashDialog = new Dialog(context, android.R.style.Theme_Translucent_NoTitleBar);
                // check to see if the splash screen should be full screen
                if ((cordova.getActivity().getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_FULLSCREEN)
                        == WindowManager.LayoutParams.FLAG_FULLSCREEN) {
                    splashDialog.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                            WindowManager.LayoutParams.FLAG_FULLSCREEN);
                }
                splashDialog.setContentView(splashImageView);
                splashDialog.setCancelable(false);
                splashDialog.show();


                // Set Runnable to remove splash screen just in case
                if (hideAfterDelay) {
                    final Handler handler = new Handler();
                    handler.postDelayed(new Runnable() {
                        public void run() {
                            if (lastHideAfterDelay) {
                                removeSplashScreen(false);
                            }
                        }
                    }, effectiveSplashDuration);
                }
            }
        });
    }

    // Show only spinner in the center of the screen
    private void spinnerStart() {
        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                spinnerStop();

                spinnerDialog = new ProgressDialog(webView.getContext());
                spinnerDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                    public void onCancel(DialogInterface dialog) {
                        spinnerDialog = null;
                    }
                });

                spinnerDialog.setCancelable(false);
                spinnerDialog.setIndeterminate(true);

                RelativeLayout centeredLayout = new RelativeLayout(cordova.getActivity());
                centeredLayout.setGravity(Gravity.CENTER);
                centeredLayout.setLayoutParams(new RelativeLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

                ProgressBar progressBar = new ProgressBar(webView.getContext());
                RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                layoutParams.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE);
                progressBar.setLayoutParams(layoutParams);

                centeredLayout.addView(progressBar);

                spinnerDialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                spinnerDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

                spinnerDialog.show();
                spinnerDialog.setContentView(centeredLayout);
            }
        });
    }

    private void spinnerStop() {
        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                if (spinnerDialog != null && spinnerDialog.isShowing()) {
                    spinnerDialog.dismiss();
                    spinnerDialog = null;
                }
            }
        });
    }
}
