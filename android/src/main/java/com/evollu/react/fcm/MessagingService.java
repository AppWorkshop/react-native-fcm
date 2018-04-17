package com.evollu.react.fcm;

import java.lang.reflect.Field;
import java.util.Map;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.facebook.react.ReactApplication;
import com.facebook.react.ReactInstanceManager;
import com.facebook.react.bridge.ReactContext;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONException;
import org.json.JSONObject;

import im.delight.android.ddp.*;

public class MessagingService extends FirebaseMessagingService implements MeteorCallback {
    private static final String TAG = "MessagingService";
    private Meteor mMeteor;
    private Map remoteMessageCallData;

    public static Object getBuildConfigValue(Context context, String fieldName) {
        try {
            Class<?> clazz = Class.forName(context.getPackageName() + ".BuildConfig");
            Field field = clazz.getField(fieldName);
            return field.get(null);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        Log.d(TAG, "Remote message received");

        if(remoteMessage.getData().get("custom_notification") != null) {
            Intent i = new Intent("com.evollu.react.fcm.ReceiveNotification");
            i.putExtra("data", remoteMessage);
            handleBadge(remoteMessage);
            buildLocalNotification(remoteMessage);

            final Intent message = i;

            // We need to run this on the main thread, as the React code assumes that is true.
            // Namely, DevServerHelper constructs a Handler() without a Looper, which triggers:
            // "Can't create handler inside thread that has not called Looper.prepare()"
            Handler handler = new Handler(Looper.getMainLooper());
            handler.post(new Runnable() {
                public void run() {
                    // Construct and load our normal React JS code bundle
                    ReactInstanceManager mReactInstanceManager = ((ReactApplication) getApplication()).getReactNativeHost().getReactInstanceManager();
                    ReactContext context = mReactInstanceManager.getCurrentReactContext();
                    // If it's constructed, send a notification
                    if (context != null) {
                        context.sendOrderedBroadcast(message, null);
                    } else {
                        // Otherwise wait for construction, then send the notification
                        mReactInstanceManager.addReactInstanceEventListener(new ReactInstanceManager.ReactInstanceEventListener() {
                            public void onReactContextInitialized(ReactContext context) {
                                context.sendOrderedBroadcast(message, null);
                            }
                        });
                        if (!mReactInstanceManager.hasStartedCreatingInitialContext()) {
                            // Construct it in the background
                            mReactInstanceManager.createReactContextInBackground();
                        }
                    }
                }
            });
        }
        else if(remoteMessage.getData().get("cancelRinging") != null) {
            // Send intent to inform CallActivity to cancel ringing
            Intent i = new Intent("net.appworkshop.cancelRinging");
            i.putExtra("roomName", remoteMessage.getData().get("roomName"));
            sendBroadcast(i);
        }
        else if(remoteMessage.getData().get("roomName") != null) {
            // It is entirely possible to receive a call notification when the device reboots
            // several hours later. To prevent this issue, we make sure that the time difference from
            // sent to received is less than 30 seconds.

            remoteMessageCallData = remoteMessage.getData();

            // Fetch meteor url
            String meteorUrl = (String) getBuildConfigValue(getApplicationContext(), "METEOR_URL");

            // We can not do a time comparison on device because even when adjusted for timezones,
            // the device could be several minutes out from the server. To fix this, we will
            // need to verify the time difference on the server where the time is consistent.
            // The call method to do this is located within the connect callback
            mMeteor = new Meteor(this, meteorUrl);
            mMeteor.addCallback(this);
            mMeteor.connect();
        }
    }

    public void handleBadge(RemoteMessage remoteMessage) {
        BadgeHelper badgeHelper = new BadgeHelper(this);
        if (remoteMessage.getData() == null) {
            return;
        }

        Map data = remoteMessage.getData();
        if (data.get("badge") == null) {
            return;
        }

        try {
            int badgeCount = Integer.parseInt((String)data.get("badge"));
            badgeHelper.setBadgeCount(badgeCount);
        } catch (Exception e) {
            Log.e(TAG, "Badge count needs to be an integer", e);
        }
    }

    public void buildLocalNotification(RemoteMessage remoteMessage) {
        if(remoteMessage.getData() == null){
            return;
        }
        Map<String, String> data = remoteMessage.getData();

        String customNotification = data.get("custom_notification");

        if(customNotification != null) {
            try {
                Bundle bundle = BundleJSONConverter.convertToBundle(new JSONObject(customNotification));
                FIRLocalMessagingHelper helper = new FIRLocalMessagingHelper(this.getApplication());
                helper.sendNotification(bundle);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    public void onConnect(boolean signedInAutomatically) {
        Log.d(TAG, "onConnect");

        mMeteor.call("verifyTimeOfNotification", new Object[]{remoteMessageCallData.get("timeSent")}, new ResultListener() {
            @Override
            public void onSuccess(String result) {
                Log.d(TAG, "Result verifiying: " +result);

                if(Boolean.valueOf(result)) {
                    Log.d(TAG, "Call notification received in time");

                    Intent launchIntent = new Intent("net.appworkshop.app.senses.call");
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    launchIntent.putExtra("callerName", remoteMessageCallData.get("callerName").toString());
                    launchIntent.putExtra("roomName", remoteMessageCallData.get("roomName").toString());
                    startActivity(launchIntent);
                }
                else {
                    Log.d(TAG, "Call notification took too long to receive");
                }

                // No longer need to remain connected
                if(mMeteor != null) {
                    mMeteor.disconnect();
                    mMeteor = null;
                }
            }

            @Override
            public void onError(String error, String reason, String details) {
                Log.d(TAG, "Error verifying notification time: " +error);
                // No longer need to remain connected
                if(mMeteor != null) {
                    mMeteor.disconnect();
                    mMeteor = null;
                }
            }
        });
    }

    // MARK: Required Meteor methods
    public void onDataAdded(String collectionName, String documentID, String newValuesJson) { }

    public void onDataChanged(String collectionName, String documentID, String updatedValuesJson, String removedValuesJson) { }

    public void onDataRemoved(String collectionName, String documentID) { }

    public void onDisconnect() { }

    public void onException(Exception e) {
        Log.d(TAG, "Meteor exception: " +e);
    }
}
