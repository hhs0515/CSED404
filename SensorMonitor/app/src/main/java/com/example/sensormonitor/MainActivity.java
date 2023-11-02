package com.example.sensormonitor;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.EventListener;

public class MainActivity extends AppCompatActivity {

    private SensorManager mSensorManager;
    private Sensor mAccel, mGravity, mGyro;
    private boolean mSensing = false;
    private boolean mSensingPause = false;
    private SensorEventListener mAccelEventListener, mGravityEventListener, mGyroEventListener;
    private Handler mHandlerAccel, mHandlerGravity, mHandlerGyro;
    
    private StringBuilder sbAccel, sbGravity, sbGyro;

    private static final int samplingRate = 10000;

    private long[] tsAccel, tsGravity, tsGyro;
    private float[][] datAccel, datGravity, datGyro;
    private int cntAccel = 0, cntGravity = 0, cntGyro = 0;

    private PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        String packageName = getPackageName();
        // WakeLock setup.
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,"SensorMonitor:WAKELOCK");

        //Get sensors : mAccel - Accelerometer, mGravity - Gravity, mGyro - Gyroscope
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        mAccel = mSensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        mGravity = mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        mGyro = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        // HandlerThread and looper for each sensor.
        HandlerThread mAccelThread = new HandlerThread("Accel Thread");
        mAccelThread.start();
        HandlerThread mGravityThread = new HandlerThread("Gravity Thread");
        mGravityThread.start();
        HandlerThread mGyroThread = new HandlerThread("Gyro Thread");
        mGyroThread.start();

        mHandlerAccel = new Handler(mAccelThread.getLooper());
        mHandlerGravity = new Handler(mGravityThread.getLooper());
        mHandlerGyro = new Handler(mGyroThread.getLooper());

        // Data that will be driven from smartphone sensors.
        tsAccel = new long[200000];
        tsGravity = new long[200000];
        tsGyro = new long[200000];
        datAccel = new float[200000][3];
        datGravity = new float[200000][3];
        datGyro = new float[200000][3];

        // Start-Stop button Click event listener.
        findViewById(R.id.buttonStartStop).setOnClickListener(new View.OnClickListener() {
            @SuppressLint("SetTextI18n")
            @Override
            public void onClick(View view) {
                mSensing = !mSensing;
                if (mSensing) {
                    ((Button) findViewById(R.id.buttonStartStop)).setText("Stop");
                    ((Button) findViewById(R.id.buttonPauseResume)).setText("Pause");
                    setActClassClickable(false);
                    mSensingPause = false;

                    sbAccel = new StringBuilder();
                    sbGravity = new StringBuilder();
                    sbGyro = new StringBuilder();

                    final int countdownDuration = 5;
                    setButtonClickable(false);
                    mHandlerAccel.post(() -> wakeLock.acquire(30*60*1000L));
                    mHandlerGravity.post(() -> wakeLock.acquire(30*60*1000L));
                    mHandlerGyro.post(() -> wakeLock.acquire(30*60*1000L));
                    wakeLock.acquire(30*60*1000L);
                    new CountDownTimer(countdownDuration * 1000, 5000) {
                        public void onTick(long millisUntilFinished) {
                            String countdownMessage = "Starting in 5 seconds...";
                            Toast.makeText(getApplicationContext(), countdownMessage, Toast.LENGTH_LONG).show();
                        }

                        public void onFinish() {
                            Toast.makeText(getApplicationContext(), "Starting now!", Toast.LENGTH_SHORT).show();
                            setButtonClickable(true);
                            startSensing(getActivityClass());
                        }
                    }.start();

                } else {
                    stopSensing();

                    setActClassClickable(true);
                    ((Button) findViewById(R.id.buttonStartStop)).setText("Start");
                    ((Button) findViewById(R.id.buttonPauseResume)).setText("Pause / Resume");
                    findViewById(R.id.radiogroupActivity).setClickable(true);
                }
            };
        });

        // Pause-Resume button event listener
        findViewById(R.id.buttonPauseResume).setOnClickListener(new View.OnClickListener() {
            @SuppressLint("SetTextI18n")
            @Override
            public void onClick(View view) {
                if(!mSensing) return;
                if (!mSensingPause) {
                    mSensingPause = true;
                    ((Button) findViewById(R.id.buttonPauseResume)).setText("Resume");
                    earlyStop();
                } else {
                    ((Button) findViewById(R.id.buttonPauseResume)).setText("Pause");

                    final int countdownDuration = 5;
                    setButtonClickable(false);
                    new CountDownTimer(countdownDuration * 1000, 5000) {
                        public void onTick(long millisUntilFinished) {
                            String countdownMessage = "Resuming in 5 seconds...";
                            Toast.makeText(getApplicationContext(), countdownMessage, Toast.LENGTH_LONG).show();
                        }

                        public void onFinish() {
                            Toast.makeText(getApplicationContext(), "Starting now!", Toast.LENGTH_SHORT).show();
                            setButtonClickable(true);
                            mSensingPause = !mSensingPause;
                        }
                    }.start();
                }
            }
        });

        //Discard button event listener
        findViewById(R.id.buttonDiscard).setOnClickListener(view -> {
            if(mSensing) return;
            new AlertDialog.Builder(this)
                    .setTitle("Discard Data")
                    .setMessage("Discard every data of the selected class.\nContinue?")
                    .setPositiveButton(android.R.string.ok, (dialogInterface, i) -> {
                        discardData(getActivityClass());
                        Toast.makeText(this, "Discarded Successfully.", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(android.R.string.cancel, (dialogInterface, i) -> {})
                    .show();
        });
    }

    /**
     * Enroll 3 sensors to Sensor Thread.
     * @param classID Which type of activity is now being collected
     */
    protected void startSensing(int classID){
        cntAccel = 0; cntGravity = 0; cntGyro = 0;

        // Linear Accelerometer
        mAccelEventListener = new SensorEventListener() {
            long ts = 0; long tsPast = 0;
            @SuppressLint("DefaultLocale")
            @Override
            public void onSensorChanged(SensorEvent sensorEvent) {
                if (sensorEvent.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
                    if (!mSensingPause){
                        tsPast = ts;
                        ts = sensorEvent.timestamp;
                        float[] values = sensorEvent.values.clone();

                        tsAccel[cntAccel] = ts/1000000;
                        System.arraycopy(values, 0, datAccel[cntAccel], 0, 3);
                        cntAccel++;

                        runOnUiThread(() -> {
                            ((TextView) findViewById(R.id.valueTimestamp)).setText(String.format("%d", ts/1000000));
                            ((TextView) findViewById(R.id.valueAccelX)).setText(String.format("%.3f", values[0]));
                            ((TextView) findViewById(R.id.valueAccelY)).setText(String.format("%.3f", values[1]));
                            ((TextView) findViewById(R.id.valueAccelZ)).setText(String.format("%.3f", values[2]));
                            ((TextView) findViewById(R.id.valueSamplerate)).setText(String.format("%.2f", 1e+9/(ts-tsPast)));
                        });
                    }
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int i) { return; }
        };

        // Gravity
        mGravityEventListener = new SensorEventListener() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onSensorChanged(SensorEvent sensorEvent) {
                if (sensorEvent.sensor.getType() == Sensor.TYPE_GRAVITY) {
                    if (!mSensingPause){
                        long ts = sensorEvent.timestamp;
                        float[] values = sensorEvent.values.clone();

                        tsGravity[cntGravity] = ts/1000000;
                        System.arraycopy(values, 0, datGravity[cntGravity], 0, 3);
                        cntGravity++;

                        runOnUiThread(() -> {
                        ((TextView) findViewById(R.id.valueGravityX)).setText(String.format("%.3f", values[0]));
                        ((TextView) findViewById(R.id.valueGravityY)).setText(String.format("%.3f", values[1]));
                        ((TextView) findViewById(R.id.valueGravityZ)).setText(String.format("%.3f", values[2]));
                        });
                    }
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int i) { return; }
        };

        // Gyroscope
        mGyroEventListener = new SensorEventListener() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onSensorChanged(SensorEvent sensorEvent) {
                if (sensorEvent.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
                    if (!mSensingPause){
                        long ts = sensorEvent.timestamp;
                        float[] values = sensorEvent.values.clone();

                        tsGyro[cntGyro] = ts/1000000;
                        System.arraycopy(values, 0, datGyro[cntGyro], 0, 3);
                        cntGyro++;

                        runOnUiThread(() -> {
                            ((TextView) findViewById(R.id.valueGyroX)).setText(String.format("%.3f", values[0]));
                            ((TextView) findViewById(R.id.valueGyroY)).setText(String.format("%.3f", values[1]));
                            ((TextView) findViewById(R.id.valueGyroZ)).setText(String.format("%.3f", values[2]));
                        });
                    }
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int i) { return; }
        };

        // Register all listeners.
        mSensorManager.registerListener(mAccelEventListener, mAccel, samplingRate, mHandlerAccel);
        mSensorManager.registerListener(mGravityEventListener, mGravity, samplingRate, mHandlerGravity);
        mSensorManager.registerListener(mGyroEventListener, mGyro, samplingRate, mHandlerGyro);
    }

    /**
     * 1. Unregister every event listener.
     * 2. Release wakeLock
     * 3. StringBuilder starts to concat data being collected as the interface.
     * 4. Make (Append) data to the designated folder/file.
     */
    @SuppressLint("DefaultLocale")
    protected void stopSensing() {
        if(!mSensingPause) earlyStop();

        mSensorManager.unregisterListener(mAccelEventListener, mAccel);
        mSensorManager.unregisterListener(mGravityEventListener, mGravity);
        mSensorManager.unregisterListener(mGyroEventListener, mGyro);
        mHandlerAccel.post(() -> { if (wakeLock.isHeld()) wakeLock.release(); });
        mHandlerGravity.post(() -> { if (wakeLock.isHeld()) wakeLock.release(); });
        mHandlerGyro.post(() -> { if (wakeLock.isHeld()) wakeLock.release(); });
        if (wakeLock.isHeld()) wakeLock.release();

        int activity_class = getActivityClass();
        // If no folder, create folder
        File classDir = new File(getFilesDir(), String.valueOf(activity_class));
        if (!classDir.exists()) {
            if (!classDir.mkdirs()) {
                Toast.makeText(this, "Class directory creation failed", Toast.LENGTH_SHORT).show();
            }
        }

        // 'Append' each data to the csv.
        mHandlerAccel.post(() -> {
            // Data is moved to StringBuilder
            for (int i = 0; i < cntAccel; i++) {
                sbAccel.append(String.format("%d,%d,%.9e,%.9e,%.9e\n", activity_class, tsAccel[i], datAccel[i][0], datAccel[i][1], datAccel[i][2]));
            }
            for(int i = 0; i < cntGravity; i++){
                sbGravity.append(String.format("%d,%d,%.9e,%.9e,%.9e\n", activity_class, tsGravity[i], datGravity[i][0], datGravity[i][1], datGravity[i][2]));
            }
            for(int i = 0; i < cntGyro; i++){
                sbGyro.append(String.format("%d,%d,%.9e,%.9e,%.9e\n", activity_class, tsGyro[i], datGyro[i][0], datGyro[i][1], datGyro[i][2]));
            }

            try (FileWriter fw = new FileWriter(new File(classDir, "linear.csv"), true)) {
                fw.write(String.valueOf(sbAccel));
            } catch (IOException e) {
                e.printStackTrace();
            }
            try (FileWriter fw = new FileWriter(new File(classDir, "gravity.csv"), true)) {
                fw.write(String.valueOf(sbGravity));
            } catch (IOException e) {
                e.printStackTrace();
            }
            try (FileWriter fw = new FileWriter(new File(classDir, "gyro.csv"), true)) {
                fw.write(String.valueOf(sbGyro));
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        new AlertDialog.Builder(this)
                .setTitle("Saved Data")
                .setMessage(String.format("Data saved successfully.\nAccel:%d\nGravity:%d\nGyro:%d",cntAccel, cntGravity, cntGyro))
                .setPositiveButton(android.R.string.ok, (dialogInterface, i) -> {})
                .show();
    }

    /**
     * Gets Activity class number by finding which child is clicked in the RadioGroup.
     * @return Activity number, which is also the name of the folder.
     */
    protected int getActivityClass() {
        RadioGroup radioGroup = findViewById(R.id.radiogroupActivity);
        return radioGroup.indexOfChild(findViewById(radioGroup.getCheckedRadioButtonId()));
    }

    /**
     * Remove file regarding selected activity.
     * @param classID Activity class which will be discarded
     */
    protected void discardData(int classID){
        File classDir = new File(getFilesDir(), String.valueOf(classID));
        File accelFile = new File(classDir, "linear.csv");
        File gravityFile = new File(classDir, "gravity.csv");
        File gyroFile = new File(classDir, "gyro.csv");

        if(accelFile.exists()) accelFile.delete();
        if(gravityFile.exists()) gravityFile.delete();
        if(gyroFile.exists()) gyroFile.delete();
    }

    /**
     * Rewrite 10 seconds which was at the tail of the whole data by reducing cnt{SensorName}
     */
    protected void earlyStop(){
        // Will rewrite 10 seconds which was at the tail of the whole data.
        cntAccel = Math.max(0, cntAccel - 1000);
        cntGravity = Math.max(0, cntGravity - 1000);
        cntGyro = Math.max(0, cntGyro - 1000);
    }

    /**
     * Set RadioGroup buttons (not) clickable to keep activity class uniform when we start sensing.
     * @param state True to make activity class clickable, false otherwise.
     */
    protected void setActClassClickable(boolean state){
        findViewById(R.id.radiogroupActivity).setClickable(state);
        findViewById(R.id.buttonOther).setClickable(state);
        findViewById(R.id.buttonWalking).setClickable(state);
        findViewById(R.id.buttonRunning).setClickable(state);
        findViewById(R.id.buttonStanding).setClickable(state);
        findViewById(R.id.buttonSitting).setClickable(state);
        findViewById(R.id.buttonUpstairs).setClickable(state);
        findViewById(R.id.buttonDownstairs).setClickable(state);
    }

    /**
     * Set 3 buttons below (not) clickable to use counter for late start as intended.
     * @param state True to make buttons clickable, false otherwise.
     */
    protected void setButtonClickable(boolean state){

        findViewById(R.id.buttonStartStop).setClickable(state);
        findViewById(R.id.buttonPauseResume).setClickable(state);
        findViewById(R.id.buttonDiscard).setClickable(state);
    }
}