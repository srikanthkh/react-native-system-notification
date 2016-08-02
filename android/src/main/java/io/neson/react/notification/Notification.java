package io.neson.react.notification;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.SystemClock;
import android.os.Bundle;
import android.app.PendingIntent;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.support.annotation.Nullable;
import android.net.Uri;

import java.lang.System;
import java.util.HashMap;
import java.util.Map;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.BufferedInputStream;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import io.neson.react.notification.NotificationAttributes;
import io.neson.react.notification.NotificationEventReceiver;
import io.neson.react.notification.NotificationPublisher;
import android.support.v7.app.NotificationCompat;
import android.text.Html;
import android.util.Base64;
import android.util.Log;
import android.graphics.Color;

/**
 * An object-oriented Wrapper class around the system notification class.
 *
 * Each instance is an representation of a single, or a set of scheduled
 * notifications. It handles operations like showing, canceling and clearing.
 */
public class Notification {
    private Context context;
    private int id;
    private NotificationAttributes attributes;

    /**
     * Constructor.
     */
    public Notification(Context context, int id, @Nullable NotificationAttributes attributes) {
        this.context = context;
        this.id = id;
        this.attributes = attributes;
    }

    /**
     * Public context getter.
     */
    public Context getContext() {
        return context;
    }

    /**
     * Public attributes getter.
     */
    public NotificationAttributes getAttributes() {
        return attributes;
    }

    /**
     * Create the notification, show it now or set the schedule.
     */
    public Notification create() {
        setAlarmAndSaveOrShow();

        Log.i("ReactSystemNotification", "Notification Created: " + id);

        return this;
    }

    /**
     * Update the notification, resets its schedule.
     */
    public Notification update(NotificationAttributes notificationAttributes) {
        delete();
        attributes = notificationAttributes;
        setAlarmAndSaveOrShow();

        return this;
    }

    /**
     * Clear the notification from the status bar.
     */
    public Notification clear() {
        getSysNotificationManager().cancel(id);

        Log.i("ReactSystemNotification", "Notification Cleared: " + id);

        return this;
    }

    /**
     * Cancel the notification.
     */
    public Notification delete() {
        getSysNotificationManager().cancel(id);

        if (attributes.delayed || attributes.scheduled) {
            cancelAlarm();
        }

        deleteFromPreferences();

        Log.i("ReactSystemNotification", "Notification Deleted: " + id);

        return this;
    }

    // as opposed to just bailing out
    private android.app.Notification createErrorNotif(android.support.v7.app.NotificationCompat.Builder notificationBuilder) {
      notificationBuilder
        .setContentTitle("ERROR")
        .setContentText("Something went wrong trying to create the tray notif, check the logs");
      return notificationBuilder.build();
    }

    private android.app.Notification createMessageNotification(android.support.v7.app.NotificationCompat.Builder notificationBuilder, JsonObject notifData) {
      // fetch the relevant data or die
      try {
        JsonPrimitive messageBodyPrimitive = notifData.getAsJsonPrimitive("body");
        String messageBody = messageBodyPrimitive.getAsString();
        JsonPrimitive messageNamePrimitive = notifData.getAsJsonPrimitive("name");
        String messageName = messageNamePrimitive.getAsString();
        String messageText = messageName + " said " + messageBody;
        notificationBuilder
          .setContentTitle("New message")
          .setContentText(messageText);
        return notificationBuilder.build();
      } catch (Exception e) {
        Log.i("XKCD", "missing data, or whatever: " + e.toString());
        return this.createErrorNotif(notificationBuilder);
      }

    }

    private android.app.Notification createCallNotif(android.support.v7.app.NotificationCompat.Builder notificationBuilder) {
      notificationBuilder
        .setContentTitle("BLAH")
        .setContentText("BLUH")
        .addAction(0, "ANSWER", getCallIntent())
        .addAction(0, "DENY", getDenyIntent());
      return notificationBuilder.build();

    }

    /**
     * Build the notification.
     */
    public android.app.Notification build() {
        android.support.v7.app.NotificationCompat.Builder notificationBuilder = new android.support.v7.app.NotificationCompat.Builder(context);
        // HERE
        // grab the profile URL form the PN
        // fetch the image from the network
        // jam it into largeIcon
        int largeIconResId = 0;
        URL largeIconURL = null;
        HttpURLConnection fetchProfilePictureConnection = null;
        Bitmap largeIconBitmap = null;
        JsonObject attributesObject = null;

        String notifType = null;

        JsonParser jsonParser = new JsonParser();
        Log.i("ReactSystemNotification", "PAYLOAD" + attributes.payload);

        // set the small icon
        // TODO set it to the ones Brenda sent
        notificationBuilder.setSmallIcon(context.getResources().getIdentifier("ic_launcher", "mipmap", context.getPackageName()));

        // parse the payload attributes or fail with an error notif
        try {
          attributesObject = (JsonObject)jsonParser.parse(attributes.payload);
        } catch (Exception e) {
          Log.i("XKCD", "Error when parsing payload attributes" + e.toString());
          return createErrorNotif(notificationBuilder);
        }
        // fetch the avatar pic, or set the default one
        try {
          JsonPrimitive avatarJsonPrimitive = attributesObject.getAsJsonPrimitive("avatarUrl");
          String avatarUrl = avatarJsonPrimitive.getAsString();
          largeIconURL = new URL(avatarUrl);
          fetchProfilePictureConnection = (HttpURLConnection) largeIconURL.openConnection();
          InputStream in = new BufferedInputStream(fetchProfilePictureConnection.getInputStream());
          largeIconBitmap = BitmapFactory.decodeStream(in);
          notificationBuilder.setLargeIcon(largeIconBitmap);
        } catch (Exception e) {
          Log.i("XKCD", "Error when setting avatar pic: " + e.toString());
          largeIconResId = context.getResources().getIdentifier("ic_launcher", "mipmap", context.getPackageName());
          largeIconBitmap = BitmapFactory.decodeResource(context.getResources(), largeIconResId);
          notificationBuilder.setLargeIcon(largeIconBitmap);
        }

        // find type of notif or fail and die
        try {
          JsonPrimitive typeJsonPrimitive = attributesObject.getAsJsonPrimitive("type");
          notifType = typeJsonPrimitive.getAsString();
        } catch (Exception e) {
          Log.i("XKCD", "Error when finding type of notif: " + e.toString());
          return createErrorNotif(notificationBuilder);
        }

        if (notifType.equals("offer")) {
          notificationBuilder
            .addAction(0, "Answer", getCallIntent())
            .addAction(0, "Deny", getDenyIntent());
        }

        /*
        if (notifType.equals("offer")) {
          Log.i("XKCD", "CREATING CALL NOTIF");
          return this.createCallNotif(notificationBuilder);
        } else if (notifType.equals("message")) {
          Log.i("XKCD", "CREATING MESSAGE NOTIF");
          return this.createMessageNotification(notificationBuilder, attributesObject);
        } else if (null != notifType) {
          String imgoinginsane = "insane";
          Log.i("XKCD", "i am going mad mike: " + imgoinginsane);
          Log.i("XKCD", "wtf is that notificationType: " + notifType);
          return this.createErrorNotif(notificationBuilder);
        }
        */

          // here's a call
          // 07-27 15:15:34.370  1286  1391 I ReactSystemNotification: PAYLOAD{"from":"blep","to":"gotadirectory","type":"offer"}
          // here's a bye
          // 07-27 15:15:44.396  1286  1407 I ReactSystemNotification: PAYLOAD{"from":"blep","to":"gotadirectory","type":"bye"}
          // here's a message
          // 07-27 15:18:51.782  1286  1579 I ReactSystemNotification: PAYLOAD{"secs":79355,"name":null,"from":"blep","id":10,"to":"gotadirectory","lang":"en","body":"blop","cid":"cid_7906e1ab","ts":"2016-07-27T22:18:50.986Z"}
          // what I'm gonna do here
          // is add a type or some shit to that payload
          // have one "createNotification" method per type
          // call the right one depending
          // they will all be pretty simple except for the call and bye ones
          // which will involve, respectively, the app, and removing a tray notif
          // I will also pull the profile pic url of that data
          // meaning I'l have to modify the server-side code to add that


        notificationBuilder
            .setContentTitle(attributes.subject)
            .setContentText(attributes.message)
            .setSmallIcon(context.getResources().getIdentifier("ic_launcher", "mipmap", context.getPackageName()))
            .setAutoCancel(attributes.autoClear)
            .setContentIntent(getContentIntent());

        // I have a hauch that all this shit should run even with my custom notifs
        if (attributes.priority != null) {
            notificationBuilder.setPriority(attributes.priority);
        }


        if ( largeIconResId != 0 && (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP ) ) {
            notificationBuilder.setLargeIcon(largeIconBitmap);
        }
        if(attributes.inboxStyle){

            android.support.v7.app.NotificationCompat.InboxStyle inboxStyle = new android.support.v7.app.NotificationCompat.InboxStyle();

            if(attributes.inboxStyleBigContentTitle != null){
                inboxStyle.setBigContentTitle(attributes.inboxStyleBigContentTitle);
            }
            if(attributes.inboxStyleSummaryText != null){
                inboxStyle.setSummaryText(attributes.inboxStyleSummaryText);
            }
            if(attributes.inboxStyleLines != null){
                for(int i=0; i< attributes.inboxStyleLines.size(); i++){
                    inboxStyle.addLine(Html.fromHtml(attributes.inboxStyleLines.get(i)));
                }
            }

            Log.i("ReactSystemNotification", "set inbox style!!");

        }else{

            int defaults = 0;
            if ("default".equals(attributes.sound)) {
                defaults = defaults | android.app.Notification.DEFAULT_SOUND;
            }
            if ("default".equals(attributes.vibrate)) {
                defaults = defaults | android.app.Notification.DEFAULT_VIBRATE;
            }
            if ("default".equals(attributes.lights)) {
                defaults = defaults | android.app.Notification.DEFAULT_LIGHTS;
            }
            notificationBuilder.setDefaults(defaults);

        }

        if (attributes.onlyAlertOnce != null) {
            notificationBuilder.setOnlyAlertOnce(attributes.onlyAlertOnce);
        }

        if (attributes.tickerText != null) {
            notificationBuilder.setTicker(attributes.tickerText);
        }

        if (attributes.when != null) {
            notificationBuilder.setWhen(attributes.when);
            notificationBuilder.setShowWhen(true);
        }

        // if bigText is not null, it have priority over bigStyleImageBase64
        if (attributes.bigText != null) {
            notificationBuilder
              .setStyle(new android.support.v7.app.NotificationCompat.BigTextStyle()
              .bigText(attributes.bigText));
        }
        else if (attributes.bigStyleImageBase64 != null) {

            Bitmap bigPicture = null;

            try {

                Log.i("ReactSystemNotification", "start to convert bigStyleImageBase64 to bitmap");
                // Convert base64 image to Bitmap
                byte[] bitmapAsBytes = Base64.decode(attributes.bigStyleImageBase64.getBytes(), Base64.DEFAULT);
                bigPicture = BitmapFactory.decodeByteArray(bitmapAsBytes, 0, bitmapAsBytes.length);
                Log.i("ReactSystemNotification", "finished to convert bigStyleImageBase64 to bitmap");

            } catch (Exception e) {
                Log.e("ReactSystemNotification", "Error when converting base 64 to Bitmap" + e.getStackTrace());
            }

            if (bigPicture != null) {
                notificationBuilder
                        .setStyle(new NotificationCompat.BigPictureStyle().bigPicture(bigPicture));
            }
        }

        if (attributes.color != null) {
          notificationBuilder.setColor(Color.parseColor(attributes.color));
        }

        if (attributes.subText != null) {
            notificationBuilder.setSubText(attributes.subText);
        }

        if (attributes.progress != null) {
            if (attributes.progress < 0 || attributes.progress > 1000) {
                notificationBuilder.setProgress(1000, 100, true);
            } else {
                notificationBuilder.setProgress(1000, attributes.progress, false);
            }
        }

        if (attributes.number != null) {
            notificationBuilder.setNumber(attributes.number);
        }

        if (attributes.localOnly != null) {
            notificationBuilder.setLocalOnly(attributes.localOnly);
        }

        if (attributes.sound != null) {
            notificationBuilder.setSound(Uri.parse(attributes.sound));
        }

        if (notifType.equals("offer")){
          android.app.Notification notif = notificationBuilder.build();
          notif.flags |= android.app.Notification.FLAG_INSISTENT;
          return notif;
        }else{
          return notificationBuilder.build();
        }
    }

    /**
     * Show the notification now.
     */
    public void show() {
        getSysNotificationManager().notify(id, build());

        Log.i("ReactSystemNotification", "Notification Show: " + id);
    }

    /**
     * Setup alarm or show the notification.
     */
    public void setAlarmAndSaveOrShow() {
        if (attributes.delayed) {
            setDelay();
            saveAttributesToPreferences();

        } else if (attributes.scheduled) {
            setSchedule();
            saveAttributesToPreferences();

        } else {
            show();
        }
    }

    /**
     * Schedule the delayed notification.
     */
    public void setDelay() {
        PendingIntent pendingIntent = getScheduleNotificationIntent();

        long futureInMillis = SystemClock.elapsedRealtime() + attributes.delay;
        getAlarmManager().set(AlarmManager.ELAPSED_REALTIME_WAKEUP, futureInMillis, pendingIntent);

        Log.i("ReactSystemNotification", "Notification Delay Alarm Set: " + id + ", Repeat Type: " + attributes.repeatType + ", Current Time: " + System.currentTimeMillis() + ", Delay: " + attributes.delay);
    }

    /**
     * Schedule the notification.
     */
    public void setSchedule() {
        PendingIntent pendingIntent = getScheduleNotificationIntent();

        if (attributes.repeatType == null) {
            getAlarmManager().set(AlarmManager.RTC_WAKEUP, attributes.sendAt, pendingIntent);
            Log.i("ReactSystemNotification", "Set One-Time Alarm: " + id);

        } else {
            switch (attributes.repeatType) {
                case "time":
                    getAlarmManager().setRepeating(AlarmManager.RTC_WAKEUP, attributes.sendAt, attributes.repeatTime, pendingIntent);
                    Log.i("ReactSystemNotification", "Set " + attributes.repeatTime + "ms Alarm: " + id);
                    break;

                case "minute":
                    getAlarmManager().setRepeating(AlarmManager.RTC_WAKEUP, attributes.sendAt, 60000, pendingIntent);
                    Log.i("ReactSystemNotification", "Set Minute Alarm: " + id);
                    break;

                case "hour":
                    getAlarmManager().setRepeating(AlarmManager.RTC_WAKEUP, attributes.sendAt, AlarmManager.INTERVAL_HOUR, pendingIntent);
                    Log.i("ReactSystemNotification", "Set Hour Alarm: " + id);
                    break;

                case "halfDay":
                    getAlarmManager().setRepeating(AlarmManager.RTC_WAKEUP, attributes.sendAt, AlarmManager.INTERVAL_HALF_DAY, pendingIntent);
                    Log.i("ReactSystemNotification", "Set Half-Day Alarm: " + id);
                    break;

                case "day":
                case "week":
                case "month":
                case "year":
                    getAlarmManager().setRepeating(AlarmManager.RTC_WAKEUP, attributes.sendAt, AlarmManager.INTERVAL_DAY, pendingIntent);
                    Log.i("ReactSystemNotification", "Set Day Alarm: " + id + ", Type: " + attributes.repeatType);
                    break;

                default:
                    getAlarmManager().set(AlarmManager.RTC_WAKEUP, attributes.sendAt, pendingIntent);
                    Log.i("ReactSystemNotification", "Set One-Time Alarm: " + id);
                    break;
            }
        }

        Log.i("ReactSystemNotification", "Notification Schedule Alarm Set: " + id + ", Repeat Type: " + attributes.repeatType + ", Current Time: " + System.currentTimeMillis() + ", First Send At: " + attributes.sendAt);
    }

    /**
     * Cancel the delayed notification.
     */
    public void cancelAlarm() {
        PendingIntent pendingIntent = getScheduleNotificationIntent();
        getAlarmManager().cancel(pendingIntent);

        Log.i("ReactSystemNotification", "Notification Alarm Canceled: " + id);
    }

    public void saveAttributesToPreferences() {
        SharedPreferences.Editor editor = getSharedPreferences().edit();

        String attributesJSONString = new Gson().toJson(attributes);

        editor.putString(Integer.toString(id), attributesJSONString);

        if (Build.VERSION.SDK_INT < 9) {
            editor.commit();
        } else {
            editor.apply();
        }

        Log.i("ReactSystemNotification", "Notification Saved To Pref: " + id + ": " + attributesJSONString);
    }

    public void loadAttributesFromPreferences() {
        String attributesJSONString = getSharedPreferences().getString(Integer.toString(id), null);
        this.attributes = (NotificationAttributes) new Gson().fromJson(attributesJSONString, NotificationAttributes.class);

        Log.i("ReactSystemNotification", "Notification Loaded From Pref: " + id + ": " + attributesJSONString);
    }

    public void deleteFromPreferences() {
        SharedPreferences.Editor editor = getSharedPreferences().edit();

        editor.remove(Integer.toString(id));

        if (Build.VERSION.SDK_INT < 9) {
            editor.commit();
        } else {
            editor.apply();
        }

        Log.i("ReactSystemNotification", "Notification Deleted From Pref: " + id);
    }

    private NotificationManager getSysNotificationManager() {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private AlarmManager getAlarmManager() {
        return (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }

    private SharedPreferences getSharedPreferences () {
        return (SharedPreferences) context.getSharedPreferences(io.neson.react.notification.NotificationManager.PREFERENCES_KEY, Context.MODE_PRIVATE);
    }

    private PendingIntent getCallIntent() {
        // Intent intent = new Intent(context, NotificationEventReceiver.class);
        Intent intent = new Intent("com.mobile.CallPushNotifIntent");
        Bundle bundle = new Bundle();
        JsonObject notificationBody = new JsonObject();
        notificationBody.addProperty("type", "offer");
        bundle.putString("notificationBody", notificationBody.toString());
        intent.putExtra("bundle", bundle);
        Log.i("XKCD", "CREATING CALL INTENT");
        return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent getDenyIntent() {
        // Intent intent = new Intent(context, NotificationEventReceiver.class);
        // Intent intent = new Intent("com.mobile.CallPushNotifIntent");
        Intent intent = new Intent("com.oney.gcm.GCMReceiveNotification");
        Bundle bundle = new Bundle();
        JsonObject notificationBody = new JsonObject();
        notificationBody.addProperty("type", "deny");
        bundle.putString("notificationBody", notificationBody.toString());
        // intent.putExtra("type", "deny");
        intent.putExtra("bundle", bundle);
        Log.i("XKCD", "CREATING CALL INTENT");
        return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent getByeIntent() {
        Intent intent = new Intent(context, NotificationEventReceiver.class);
        intent.putExtra("what_am_I_doing", "bye");
        return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent getContentIntent() {
        Intent intent = new Intent(context, NotificationEventReceiver.class);

        intent.putExtra(NotificationEventReceiver.NOTIFICATION_ID, id);
        intent.putExtra(NotificationEventReceiver.ACTION, attributes.action);
        intent.putExtra(NotificationEventReceiver.PAYLOAD, attributes.payload);

        return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent getScheduleNotificationIntent() {
        Intent notificationIntent = new Intent(context, NotificationPublisher.class);
        notificationIntent.putExtra(NotificationPublisher.NOTIFICATION_ID, id);

        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, id, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        return pendingIntent;
    }
}