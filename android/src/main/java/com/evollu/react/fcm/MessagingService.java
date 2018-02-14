package com.evollu.react.fcm;

import java.util.Date;
import java.util.Map;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.facebook.react.ReactApplication;
import com.facebook.react.ReactInstanceManager;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONException;
import org.json.JSONObject;

public class MessagingService extends FirebaseMessagingService {
    private static final String TAG = "MessagingService";

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
            // We want to make sure we're not about to display a phone call from several
            // hours ago due to device possibly being shut off

            Long timeSent = Long.parseLong(remoteMessage.getData().get("timeSent"));
            Log.d(TAG, "timeSent: " +timeSent);
            Long timeReceived = new Date().getTime();
            Log.d(TAG, "timeReceived: " +timeReceived);

            Log.d(TAG, "Time difference in milliseconds: " +Math.abs(timeReceived-timeSent));

            // 1000 = 1second
            // 10000 = 10seconds
            // 100000 = 100seconds (1min 40seconds)

            if(Math.abs(timeReceived-timeSent) < 30000) { // Less than 30 seconds
                try {
                    ReactInstanceManager mReactInstanceManager = ((ReactApplication) getApplication()).getReactNativeHost().getReactInstanceManager();
                    ReactContext context = mReactInstanceManager.getCurrentReactContext();
                    context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit("checkForCall", null);
                } catch(Exception e) {
                    Log.e(TAG, "Exception doing emit: " +e);
                }

                Intent launchIntent = new Intent("net.appworkshop.app.senses.call");
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                launchIntent.putExtra("callerName", remoteMessage.getData().get("callerName"));
                launchIntent.putExtra("roomName", remoteMessage.getData().get("roomName"));
                startActivity(launchIntent);
            }
            else {
                Log.d(TAG, "Call notification took too long to receive, will not bother");
            }
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

        if(customNotification != null){
            try {
                Bundle bundle = BundleJSONConverter.convertToBundle(new JSONObject(customNotification));
                FIRLocalMessagingHelper helper = new FIRLocalMessagingHelper(this.getApplication());
                helper.sendNotification(bundle);
            } catch (JSONException e) {
                e.printStackTrace();
            }

        }
    }
}
