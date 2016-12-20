package com.sobkovych.appmonitor;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.support.v4.app.NotificationCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.jaredrummler.android.processes.AndroidProcesses;
import com.koushikdutta.async.AsyncServer;
import com.koushikdutta.async.http.server.AsyncHttpServer;
import com.koushikdutta.async.http.server.AsyncHttpServerRequest;
import com.koushikdutta.async.http.server.AsyncHttpServerResponse;
import com.koushikdutta.async.http.server.HttpServerRequestCallback;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteOrder;
import java.util.Enumeration;
import java.util.List;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    private static final int ownPID = android.os.Process.myPid();

    private static final int TL = Toast.LENGTH_SHORT;
    private static final String logTAG =
            String.valueOf(ownPID) + "::" + MainActivity.class.getSimpleName();

    private AsyncHttpServer mAsyncHttpserver = new AsyncHttpServer();
    private AsyncServer mAsyncServer = new AsyncServer();

    private static final String wakeLockID = "AppMonitorWL";
    PowerManager.WakeLock mWakeLock = null;

    private static boolean mIsNotifyActive = false;
    private static long mNotifyId = 0;

    private static final String prefID = "AppMonitorPrefs";
    private static final String prefAppName = "app_name";
    private static final String prefAppNameDefaultValue = "beehd";
    private static final String prefRestPort = "rest_port";
    private static final int prefRestPortDefaultVaue = 7654;
    private SharedPreferences mPrefs = null;
    private String appName = prefAppNameDefaultValue;
    private int restPort = prefRestPortDefaultVaue;

    private EditText textFieldAppName;
    private EditText textFieldRestPort;

    //==============================================================================================
    public void tvLogMessage(String message) {
        if (message.length() > 0) {
            Log.d(logTAG, message);
            Toast.makeText(this, message, TL).show();
        }
    }

    //==============================================================================================
    private String getConnectionType() {
        ConnectivityManager connMgr =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeInfo = connMgr.getActiveNetworkInfo();
        if (activeInfo != null && activeInfo.isConnected()) {
            switch (activeInfo.getType()) {
                // WiFi
                case ConnectivityManager.TYPE_WIFI:
                    return "WiFi";
                // Mobile
                case ConnectivityManager.TYPE_MOBILE:
                    return "Mobile";
            }
        } else {
            tvLogMessage("getLocalIP(): Network Unavailable!");
        }
        return "Disconnected";
    }

    //==============================================================================================
    private String getLocalIP() {
        ConnectivityManager connMgr =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeInfo = connMgr.getActiveNetworkInfo();
        if (activeInfo != null && activeInfo.isConnected()) {
            switch (activeInfo.getType()) {
                // WiFi
                case ConnectivityManager.TYPE_WIFI:
                    WifiManager wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
                    WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                    int ipAddress = wifiInfo.getIpAddress();
                    // Convert little-endian to big-endian if needed
                    if (ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN)) {
                        ipAddress = Integer.reverseBytes(ipAddress);
                    }
                    byte[] ipByteArray = BigInteger.valueOf(ipAddress).toByteArray();
                    String ipAddressString;
                    try {
                        ipAddressString = InetAddress.getByAddress(ipByteArray).getHostAddress();
                    } catch (UnknownHostException ex) {
                        tvLogMessage("getLocalIP(): Unable to get host address!");
                        ipAddressString = "Fail";
                    }
                    return ipAddressString;

                // Mobile
                case ConnectivityManager.TYPE_MOBILE:
                    try {
                        for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                            NetworkInterface intf = en.nextElement();
                            for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                                InetAddress inetAddress = enumIpAddr.nextElement();
                                if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                                    return inetAddress.getHostAddress();
                                }
                            }
                        }
                    } catch (SocketException ex) {
                        tvLogMessage("getLocalIP(): Exception in Get IP Address!");
                    }
                    break;
            }
        } else {
            tvLogMessage("getLocalIP(): Network Unavailable!");
        }
        return "Fail";
    }

    //==============================================================================================
    private void restServerStart() {
        //
        // Test server status
        //
        mAsyncHttpserver.get("/", new HttpServerRequestCallback() {
            @Override
            public void onRequest(AsyncHttpServerRequest request, AsyncHttpServerResponse response) {
                response.send("Application Monitor alive!");
            }
        });

        //
        // Retrieve Application status
        //
        mAsyncHttpserver.get("/app/status", new HttpServerRequestCallback() {

            @Override
            public void onRequest(AsyncHttpServerRequest request, AsyncHttpServerResponse response) {
                Context mContext = getApplicationContext();
                List<ActivityManager.RunningAppProcessInfo> appInfoList =
                        AndroidProcesses.getRunningAppProcessInfo(mContext);
                int mCount = 0;
                for (int i = 0; i < appInfoList.size(); i++) {
                    if (appInfoList.get(i).pid == ownPID) {
                        continue;
                    }
                    if (appInfoList.get(i).processName.contains(appName)) {
                        ++mCount;
                    }
                }
                JSONObject jsonResp = new JSONObject();
                try {
                    if (mCount > 0) {
                        jsonResp.put("AppName", appName);
                        jsonResp.put("Status", "Started");
                        jsonResp.put("ProcCount", mCount);
                    } else {
                        jsonResp.put("Status", "Stoped");
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                response.send(jsonResp);
            }
        });

        //
        // Retrieve Application procs list
        //
        mAsyncHttpserver.get("/app/procs", new HttpServerRequestCallback() {
            @Override
            public void onRequest(AsyncHttpServerRequest request, AsyncHttpServerResponse response) {
                Context mContext = getApplicationContext();
                JSONArray jsonArray = new JSONArray();
                List<ActivityManager.RunningAppProcessInfo> appInfoList =
                        AndroidProcesses.getRunningAppProcessInfo(mContext);
                for (int i = 0; i < appInfoList.size(); i++) {
                    if (appInfoList.get(i).pid == ownPID) {
                        continue;
                    }
                    if (appInfoList.get(i).processName.contains(appName)) {
                        JSONObject procItem = new JSONObject();
                        try {
                            procItem.put("ProcName", appInfoList.get(i).processName);
                            procItem.put("PID", appInfoList.get(i).pid);
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        jsonArray.put(procItem);
                    }
                }
                JSONObject jsonResp = new JSONObject();
                try {
                    jsonResp.put("AppProcs", jsonArray);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                response.send(jsonResp);
            }
        });

        //
        // Retrieve All procs list
        //
        mAsyncHttpserver.get("/all/procs", new HttpServerRequestCallback() {
            @Override
            public void onRequest(AsyncHttpServerRequest request, AsyncHttpServerResponse response) {
                Context mContext = getApplicationContext();
                JSONArray jsonArray = new JSONArray();
                List<ActivityManager.RunningAppProcessInfo> appInfoList =
                        AndroidProcesses.getRunningAppProcessInfo(mContext);
                for (int i = 0; i < appInfoList.size(); i++) {
                    JSONObject procItem = new JSONObject();
                    try {
                        procItem.put("ProcName", appInfoList.get(i).processName);
                        procItem.put("PID", appInfoList.get(i).pid);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                    jsonArray.put(procItem);
                }
                JSONObject jsonResp = new JSONObject();
                try {
                    jsonResp.put("AllProcs", jsonArray);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                response.send(jsonResp);
            }
        });

        mAsyncHttpserver.listen(mAsyncServer, restPort);

        if (mWakeLock != null) {
            mWakeLock.acquire();
        }
        tvLogMessage("REST server started on port " + restPort);
    }

    //==============================================================================================
    private void restServerStop() {
        mAsyncHttpserver.stop();
        mAsyncServer.stop();
        if (mWakeLock != null) {
            mWakeLock.release();
        }
        tvLogMessage("REST server stopped!");
    }

    //==============================================================================================
    private void restServerRestart() {
        appSettingsSave();
        restServerStop();
        restServerStart();
    }

    //==============================================================================================
    private void notifyIconCreate() {
        // Already created
        if (mIsNotifyActive && mNotifyId != 0) {
            return;
        }
        mNotifyId = System.currentTimeMillis();

        Context mContext = getApplicationContext();
        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(mContext)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getResources().getString(R.string.app_name));

        Intent intent = new Intent(mContext, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pIntent = PendingIntent.getActivity(
                mContext,
                (int)mNotifyId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        mBuilder.setContentIntent(pIntent);

        Notification notif = mBuilder.build();
        notif.flags |= Notification.FLAG_NO_CLEAR
                | Notification.FLAG_ONGOING_EVENT;
        NotificationManager mNotifyMgr = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        mNotifyMgr.notify((int)mNotifyId, notif);

        mIsNotifyActive = true;
    }

    //==============================================================================================
    private void notifyIconCancel() {

        if ( !mIsNotifyActive ) {
            return;
        }
        Context mContext = getApplicationContext();
        NotificationManager mNotifyMgr = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        mNotifyMgr.cancel((int)mNotifyId);

        mNotifyId = 0;
        mIsNotifyActive = false;
    }

    //==============================================================================================
    private void appSettingsSave() {
        if (mPrefs == null) {
            appSettingsLoad();
        }

        // Get current values
        String currAppName = textFieldAppName.getText().toString();
        int currRestPort = Integer.parseInt(textFieldRestPort.getText().toString());

        // If settings was changed
        if ( !currAppName.equals(appName) || (currRestPort != restPort)) {
            // Write current settings to shared prefs
            appName = currAppName;
            restPort = currRestPort;
            SharedPreferences.Editor prefEditor = mPrefs.edit();
            prefEditor.putString(prefAppName, appName);
            prefEditor.putInt(prefRestPort, restPort);
            prefEditor.apply();
            tvLogMessage("Settings saved!");
        }
    }

    //==============================================================================================
    private void appSettingsLoad() {
        // Get saved shared prefs
        //
        mPrefs = getSharedPreferences(prefID, 0);
        appName = mPrefs.getString(prefAppName, prefAppNameDefaultValue);
        restPort = mPrefs.getInt(prefRestPort, prefRestPortDefaultVaue);
        textFieldAppName.setText(appName);
        textFieldRestPort.setText(String.valueOf(restPort));
    }

    //==============================================================================================
    private void appStop() {
        appSettingsSave();
        notifyIconCancel();
        restServerStop();
        finish();
        System.exit(0);
    }

    //==============================================================================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Avoid automatically appear android keyboard when activity start
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);

        // Update UI with current network information
        //
        ((TextView)findViewById(R.id.textViewConnType)).setText(getConnectionType());
        ((TextView)findViewById(R.id.textViewIpAddr)).setText(getLocalIP());

        // Identify EditText fields
        //
        textFieldAppName = (EditText) findViewById(R.id.etAppName);
        textFieldRestPort = (EditText) findViewById(R.id.etRestPort);

        // Init Wake Lock object
        //
        if (mWakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, wakeLockID);
        }

        // Load application preferences
        //
        appSettingsLoad();

        // Attach button listeners
        //
        findViewById(R.id.button_restart).setOnClickListener(this);
        findViewById(R.id.button_stop).setOnClickListener(this);

        // Create Notification icon
        notifyIconCreate();

        // Start REST server
        restServerStart();
    }

    //==============================================================================================
    @Override
    protected void onPause() {
        super.onPause();
        appSettingsSave();
    }

    //==============================================================================================
    @Override
    public void onBackPressed() {
        appSettingsSave();
        moveTaskToBack(true);
    }

    //==============================================================================================
    @Override
    protected void onDestroy() {
        super.onDestroy();
        appSettingsSave();
        notifyIconCancel();
        restServerStop();
    }

    //==============================================================================================
    // Process button clicks
    @Override
    public void onClick(View v) {
        switch(v.getId()){
            case R.id.button_restart:
                restServerRestart();
                break;

            case R.id.button_stop:
                appStop();
                break;

            default:
                break;
        }
    }

}
