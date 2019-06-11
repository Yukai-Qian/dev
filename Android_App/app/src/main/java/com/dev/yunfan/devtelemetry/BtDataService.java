package com.dev.yunfan.devtelemetry;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;


public class BtDataService extends Service {
    private static final String TAG = "DATA_SERVICE";
    private static BluetoothAdapter btAdapter;
    private static BluetoothSocket btSocket;
    private static final UUID MY_UUID = UUID.randomUUID();
    private String sessionName = "default";
    private DataObj mostUpToDate;
    private final Binder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        BtDataService getService() {
            Log.e(TAG, "Data Requested.");
            return BtDataService.this;
        }
    }

    public BtDataService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.e(TAG, "is created");
        btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter == null) {
            Log.e(TAG, "Bluetooth not supported by this phone!");
        } else if (!btAdapter.isEnabled()) {
            Log.e(TAG, "Bluetooth is not enabled!");
        }
        connect();
        BtConnectionThread btComm = new BtConnectionThread();
        btComm.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        Log.i(TAG, "is started");
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        disconnect();
    }

    private void connect() {
        if (btAdapter.isDiscovering())
            btAdapter.cancelDiscovery();
        Set<BluetoothDevice> devices = btAdapter.getBondedDevices();
        BluetoothDevice btDevice = null;

        for (BluetoothDevice device : devices) {
            Log.e(TAG, device.getName());
            if (device.getName().contains("HC") || device.getName().contains("BT1")) {
                btDevice = device;
                break;
            }
        }

        if (btDevice == null) {
            Log.e(TAG, "Did not find device!");
            return;
        }
        try {
            btSocket = btDevice.createInsecureRfcommSocketToServiceRecord(MY_UUID);
        } catch (IOException e) {
            Log.e(TAG, "Cannot create socket");
        }

        try {
            btSocket.connect();
        } catch (IOException e) {
            Log.e(TAG, "Something happened during BT connect. Try again with reflection.", e);
            try {
                btSocket = (BluetoothSocket) btDevice.getClass().getMethod("createRfcommSocket",
                        new Class[]{int.class}).invoke(btDevice, 1);
                btSocket.connect();
            } catch (IOException refE) {
                Log.e(TAG, "Reflection failed", refE);
                disconnect();
            } catch (Exception otherE) {
                Log.e(TAG, "Create Reflection failed", otherE);
                disconnect();
            }
        }
    }

    private void disconnect() {
        try {
            btSocket.close();
        } catch (IOException e) {
            Log.e(TAG, "cannot close socket!", e);
        }
    }

    public DataObj getMostRecent() {
        return mostUpToDate;
    }

    private class BtConnectionThread extends Thread {
        private InputStream inStream;
        private OutputStream outStream;
        private BufferedReader btReader;
        FirebaseDatabase database = null;
        DatabaseReference myRef = null;

        private BtConnectionThread() {
            if (btSocket == null) {
                Log.e(TAG, "No socket established in BtConnectionThread constructor.");
                return;
            }
            // Get the BluetoothSocket input and output streams
            try {
                inStream = btSocket.getInputStream();
                outStream = btSocket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "Socket stream cannot be created", e);
            }

            database = FirebaseDatabase.getInstance();
            myRef =  database.getReference("values");
        }

        public void run() {
            if (btSocket == null) {
                Log.e(TAG, "No socket established in run.");
                return;
            }

            try {
                btReader = new BufferedReader(new InputStreamReader(inStream));
            } catch (Exception e) {
                Log.e(TAG, "cannot create btReader", e);
                disconnect();
            }

            // Keep listening to the InputStream while connected
            while (btSocket.isConnected()) {
                try {
                    sleep(20);
                } catch (InterruptedException e) {
                    Log.e(TAG, "Cannot go to sleep.");
                }
                String result;
                try {
                    result = btReader.readLine();
                    Log.i(TAG, result);
                    DataObj newData = new DataObj(result, sessionName);
                    mostUpToDate = newData;
                    myRef.push().setValue(newData);
                } catch (IOException e) {
                    Log.e(TAG, "disconnected", e);
                    disconnect();
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Unknown Error during BT transmission", e);
                }
            }
        }

        public void write(String stringToWrite) {
            byte[] buffer = stringToWrite.getBytes(StandardCharsets.US_ASCII);
            try {
                outStream.write(buffer);
            } catch (IOException e) {
                Log.e(TAG, "Exception during write", e);
            }
        }
    }
}
