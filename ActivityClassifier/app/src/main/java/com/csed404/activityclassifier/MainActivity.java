package com.csed404.activityclassifier;

import android.annotation.SuppressLint;
import android.content.res.AssetManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import libsvm.*;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {
    boolean onClassify = false;
    private static final int samplingRate = 10000;

    private SensorManager mSensorManager;
    private Sensor mAccel, mGravity, mGyro;
    private SensorEventListener mCombinedEventListener;
    private Handler mHandlerSensor, mHandlerClassifier;
    private static int second;
    private final float[][][] datAccel = new float[3][100][3];
    private final float[][][] datGravity = new float[3][100][3];
    private final float[][][] datGyro = new float[3][100][3];

    public svm_model model;
    public double[][] params;
    public svm_node[] svmNode = new svm_node[36];


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btn = findViewById(R.id.StartStopButton);

        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        mAccel = mSensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        mGravity = mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        mGyro = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        // load params and model
        try {
            getModel();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try {
            getParameter();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // HandlerThread and looper for each sensor.
        HandlerThread mSensorThread = new HandlerThread("Sensor Thread");
        mSensorThread.start();
        mHandlerSensor = new Handler(mSensorThread.getLooper());
        HandlerThread mClassThread = new HandlerThread("Classifier Thread");

        mClassThread.start();
        mHandlerClassifier = new Handler(mClassThread.getLooper());

        btn.setOnClickListener(view -> {
            onClassify = !onClassify;
            if(onClassify){
                startClassify();
                btn.setText("Stop");
            }
            else{
                stopClassify();
                btn.setText("Start");
            }
        });
    }

    protected void startClassify() {
        startSensing();
        startWork();

    }

    protected void stopClassify(){
        stopSensing();
    }

    protected void startSensing(){
        long tsStart = System.nanoTime();

        mCombinedEventListener = new SensorEventListener() {
            int cntAccel = 0;
            int cntGravity = 0;
            int cntGyro = 0;

            @SuppressLint("DefaultLocale")
            @Override
            public void onSensorChanged(SensorEvent sensorEvent) {
                long ts = sensorEvent.timestamp;
                int cell = (int) (((ts - tsStart) / 1e+9) % 3);
                float[] values = sensorEvent.values.clone();
                if(second != cell){
                    Log.d("cnt: ", cntAccel +" " + cntGravity + " " + cntGyro);
                    cntAccel = 0;
                    cntGravity = 0;
                    cntGyro = 0;
                    second = cell;
                }
                switch (sensorEvent.sensor.getType()) {
                    case Sensor.TYPE_LINEAR_ACCELERATION:
                        System.arraycopy(values, 0, datAccel[cell][cntAccel % 100], 0, 3);
                        cntAccel++;
                        break;

                    case Sensor.TYPE_GRAVITY:
                        System.arraycopy(values, 0, datGravity[cell][cntGravity % 100], 0, 3);
                        cntGravity++;
                        break;

                    case Sensor.TYPE_GYROSCOPE:
                        System.arraycopy(values, 0, datGyro[cell][cntGyro % 100], 0, 3);
                        cntGyro++;
                        break;
                }

            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int i) {  }
        };

        // Register the combined listener for all sensors.
        mSensorManager.registerListener(mCombinedEventListener, mAccel, samplingRate, mHandlerSensor);
        mSensorManager.registerListener(mCombinedEventListener, mGravity, samplingRate, mHandlerSensor);
        mSensorManager.registerListener(mCombinedEventListener, mGyro, samplingRate, mHandlerSensor);

    }

    protected void stopSensing() {
        mSensorManager.unregisterListener(mCombinedEventListener, mAccel);
        mSensorManager.unregisterListener(mCombinedEventListener, mGravity);
        mSensorManager.unregisterListener(mCombinedEventListener, mGyro);
        mHandlerClassifier.removeCallbacksAndMessages(null);
    }

    protected void startWork(){
        float[][] windowGravity = new float[200][3];
        float[][] windowGyro = new float[200][3];
        float[][] windowAccel = new float[200][3];

        float[] features = new float[36];

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            mHandlerClassifier.postDelayed(new Runnable() {
                @Override
                public void run() {
                    int front = 0, back = 0;
                    switch (second) {
                        case 0:
                            front = 1;
                            back = 2;
                            break;
                        case 1:
                            front = 2;
                            back = 0;
                            break;
                        case 2:
                            front = 0;
                            back = 1;
                            break;
                    }
                    for (int i = 0; i < 100; i++) {
                        System.arraycopy(datAccel[front][i], 0, windowAccel[i], 0, 3);
                        System.arraycopy(datGravity[front][i], 0, windowGravity[i], 0, 3);
                        System.arraycopy(datGyro[front][i], 0, windowGyro[i], 0, 3);
                    }
                    for (int i = 100; i < 200; i++) {
                        System.arraycopy(datAccel[back][i - 100], 0, windowAccel[i], 0, 3);
                        System.arraycopy(datGravity[back][i - 100], 0, windowGravity[i], 0, 3);
                        System.arraycopy(datGyro[back][i - 100], 0, windowGyro[i], 0, 3);
                    }

                    getFeatures(windowGravity, 0, features);
                    getFeatures(windowGyro, 12, features);
                    getFeatures(windowAccel, 24, features);

                    for(int i = 0; i < 36; i++){
                        features[i] = (float) ((features[i] - params[i][0]) / (params[i][1] - params[i][0]) * 2 - 1);
                    }

                    int result = (int) svm.svm_predict(model, convertSvmNode(features));
                    runOnUiThread(() -> {
                        TextView editText = findViewById(R.id.resultView);
                        editText.setText(((int) System.currentTimeMillis() + ": " + result));
                        volleyPost(result);
                        Log.d("X Y Z:", features[0] +" "+ features[1] +" "+features[2]);
                    });
                    mHandlerClassifier.postDelayed(this, 1000);
                };
            }, 1000);
        }
    }



    public void getModel() throws IOException {
        AssetManager assetManager = getAssets();
        InputStream is = assetManager.open("base_new.txt");
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        model = svm.svm_load_model(br);
    }

    private void getParameter() throws IOException {
        AssetManager assetManager = getAssets();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(assetManager.open("params_new.txt")))) {
            List<double[]> paramsList = new ArrayList<>();

            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = line.split(",");
                if (values.length == 2) {
                    double[] row = {Double.parseDouble(values[0].trim()), Double.parseDouble(values[1].trim())};
                    paramsList.add(row);
                } else {
                    System.err.println("Invalid data format in line: " + line);
                }
            }

            params = new double[paramsList.size()][2];
            paramsList.toArray(params);
        }
    }

    private void getFeatures(float[][] window, int idx, float[] features){
        float[][] windowTranspose = new float[3][200];
        for(int i = 0; i < 200; i++){
            for(int j = 0; j < 3; j++){
                windowTranspose[j][i] = window[i][j];
            }
        }

        for(int i = 0; i < 3; i++){
            features[idx+i] = calculateMean(windowTranspose[i]);
            features[idx+i+3] = calculateVariance(windowTranspose[i], features[idx+i]);
            features[idx+i+6] = freqSpectrum(windowTranspose[i]);
        }
        features[idx+9] = calculateCorrelation(windowTranspose[0], windowTranspose[1], features[idx], features[idx+1]);
        features[idx+10] = calculateCorrelation(windowTranspose[0], windowTranspose[2], features[idx], features[idx+2]);
        features[idx+11] = calculateCorrelation(windowTranspose[1], windowTranspose[2], features[idx+1], features[idx+2]);
    }

    public static float calculateMean(float[] array) {
        float sum = 0;

        for (float value : array) {
            sum += value;
        }

        return sum / array.length;
    }

    public static float calculateVariance(float[] array, float mean) {
        float sumSquaredDifferences = 0;

        for (float value : array) {
            sumSquaredDifferences += (float) Math.pow(value - mean, 2);
        }

        return sumSquaredDifferences / array.length;
    }

    public static float freqSpectrum(float[] dat) {
        float sumSquared = 0;

        for (float v : dat) {
            sumSquared += (float) Math.abs(Math.pow(v, 2));
        }

        return sumSquared / dat.length;
    }

    public static float calculateCorrelation(float[] x, float[] y, float meanX, float meanY) {
        if (x.length != y.length || x.length == 0) {
            throw new IllegalArgumentException("Arrays must have the same non-zero length");
        }

        int n = x.length;

        float numerator = 0.0F;
        float denominatorX = 0.0F;
        float denominatorY = 0.0F;

        for (int i = 0; i < n; i++) {
            numerator += (x[i] - meanX) * (y[i] - meanY);
            denominatorX += (float) Math.pow(x[i] - meanX, 2);
            denominatorY += (float) Math.pow(y[i] - meanY, 2);
        }

        if (denominatorX == 0 || denominatorY == 0) {
            return 0.0F;
        }

        return (float) (numerator / Math.sqrt(denominatorX * denominatorY));
    }

    public svm_node[] convertSvmNode(float[] feat){
        svm_node[] nodes = new svm_node[36];
        for (int i = 0; i < 36; i++) {
            nodes[i] = new svm_node();
            nodes[i].index = i + 1;
            nodes[i].value = feat[i];
        }
        return nodes;
    }

    public void volleyPost(int activityClass){
        String postUrl = "http://172.30.1.72:5000/post_activity";
        RequestQueue requestQueue = Volley.newRequestQueue(this);

        JSONObject postData = new JSONObject();

        String act = "No action Yet";
        switch (activityClass){
            case 0:
                act = "Others";
                break;
            case 1:
                act = "Walking";
                break;
            case 2:
                act = "Running";
                break;
            case 3:
                act = "Standing";
                break;
            case 4:
                act = "Sitting";
                break;
            case 5:
                act = "Upstairs";
                break;
            case 6:
                act = "Downstairs";
                break;
        }

        try {
            postData.put("activity", act);

        } catch (JSONException e) {
            e.printStackTrace();
        }

        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(Request.Method.POST, postUrl, postData, new Response.Listener<JSONObject>() {
            @Override
            public void onResponse(JSONObject response) {
                System.out.println(response);
            }
        }, error -> error.printStackTrace());

        requestQueue.add(jsonObjectRequest);
    }
}