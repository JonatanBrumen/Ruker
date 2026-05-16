package com.example.ruker;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback, SensorEventListener {

    private GoogleMap mMap;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private LatLng lastLatLng;
    private boolean isFirstLocationUpdate = true;

    private SensorManager sensorManager;
    private Sensor accelerometer;
    
    private static final int RAW_SAMPLE_COUNT = 125;
    private static final int SAMPLES_PER_FRAME = 3;
    private final float[] inputBuffer = new float[RAW_SAMPLE_COUNT * SAMPLES_PER_FRAME];
    
    private final ExecutorService inferenceExecutor = Executors.newSingleThreadExecutor();
    private int inferenceCounter = 0;
    private static final int INFERENCE_INTERVAL_SAMPLES = 8; 

    private TextView statusText;
    private TextView timerText;
    private MaterialButton recordButton;
    private volatile int lastClassificationColor = Color.GRAY;
    private volatile String lastClassificationLabel = "Idle";
    
    private FirebaseFirestore db;
    private boolean isRecording = false;
    private String currentRunId;
    private Timestamp startTimestamp;
    private final List<Map<String, Object>> currentRunPath = new ArrayList<>();
    
    private long startTime = 0L;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            long millis = SystemClock.uptimeMillis() - startTime;
            int seconds = (int) (millis / 1000);
            int minutes = seconds / 60;
            seconds = seconds % 60;
            timerText.setText(String.format(Locale.US, "%02d:%02d", minutes, seconds));
            timerHandler.postDelayed(this, 500);
        }
    };

    enum Terrain {
        IDLE(Color.GRAY, "Idle"),
        SMOOTH(Color.parseColor("#4CAF50"), "Smooth"),
        TOUGH(Color.parseColor("#F44336"), "Tough");

        final int color;
        final String label;
        Terrain(int color, String label) {
            this.color = color;
            this.label = label;
        }
    }

    private static final int SMOOTHING_WINDOW_SIZE = 3; 
    private final LinkedList<Terrain> recentTerrains = new LinkedList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        timerText = findViewById(R.id.timerText);
        recordButton = findViewById(R.id.recordButton);

        db = FirebaseFirestore.getInstance();

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        setupLocationUpdates();

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }

        recordButton.setOnClickListener(v -> {
            if (!isRecording) {
                showSecurePhoneDialog();
            } else {
                stopRecording();
            }
        });
    }

    private void showSecurePhoneDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Secure Phone")
                .setMessage("Put your phone in a secure place on the wheelchair where it doesn't move around.")
                .setPositiveButton("OK", (dialog, which) -> startCountdown())
                .setCancelable(false)
                .show();
    }

    private void startCountdown() {
        recordButton.setBackgroundTintList(ColorStateList.valueOf(Color.RED));
        recordButton.setEnabled(false);
        final int[] secondsLeft = {5};
        
        Handler countdownHandler = new Handler(Looper.getMainLooper());
        Runnable countdownRunnable = new Runnable() {
            @Override
            public void run() {
                if (secondsLeft[0] > 0) {
                    recordButton.setText(String.format(Locale.US, "Recording in %d...", secondsLeft[0]));
                    secondsLeft[0]--;
                    countdownHandler.postDelayed(this, 1000);
                } else {
                    startRecording();
                }
            }
        };
        countdownHandler.post(countdownRunnable);
    }

    private void startRecording() {
        isRecording = true;
        recordButton.setEnabled(true);
        recordButton.setText("Stop Recording");
        startTime = SystemClock.uptimeMillis();
        startTimestamp = Timestamp.now();
        currentRunId = UUID.randomUUID().toString();
        timerHandler.postDelayed(timerRunnable, 0);
        
        Arrays.fill(inputBuffer, 0);
        inferenceCounter = 0;
        recentTerrains.clear();
        currentRunPath.clear();
        
        if (mMap != null) mMap.clear();
    }

    private void stopRecording() {
        isRecording = false;
        timerHandler.removeCallbacks(timerRunnable);
        recordButton.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#388E3C")));
        recordButton.setText("Start Recording");
        timerText.setText("00:00");

        uploadRunToFirebase();
    }

    private void uploadRunToFirebase() {
        if (currentRunPath.isEmpty()) {
            Toast.makeText(this, "No valid data to save.", Toast.LENGTH_SHORT).show();
            return;
        }

        Map<String, Object> runData = new HashMap<>();
        runData.put("run_id", currentRunId);
        runData.put("start_time", startTimestamp);
        runData.put("path", new ArrayList<>(currentRunPath));

        db.collection("recorded_paths")
                .add(runData)
                .addOnSuccessListener(documentReference -> 
                    Toast.makeText(MainActivity.this, "Path synced to Firebase!", Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e -> 
                    Log.e("Firebase", "Error saving run", e));
    }

    private void setupLocationUpdates() {
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                for (Location location : locationResult.getLocations()) {
                    LatLng currentLatLng = new LatLng(location.getLatitude(), location.getLongitude());
                    
                    if (isFirstLocationUpdate && mMap != null) {
                        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 19f));
                        isFirstLocationUpdate = false;
                    }

                    if (isRecording && lastLatLng != null && mMap != null) {
                        mMap.addPolyline(new PolylineOptions()
                                .add(lastLatLng, currentLatLng)
                                .color(lastClassificationColor)
                                .width(15));
                        mMap.animateCamera(CameraUpdateFactory.newLatLng(currentLatLng));

                        if (!"Idle".equals(lastClassificationLabel)) {
                            Map<String, Object> point = new HashMap<>();
                            point.put("latitude", location.getLatitude());
                            point.put("longitude", location.getLongitude());
                            point.put("terrain_type", lastClassificationLabel);
                            point.put("timestamp", Timestamp.now());
                            currentRunPath.add(point);
                        }
                    }
                    lastLatLng = currentLatLng;
                }
            }
        };
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        mMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            return;
        }
        
        enableMapFeatures();
    }

    private void enableMapFeatures() {
        if (mMap != null && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mMap.setMyLocationEnabled(true);
            mMap.getUiSettings().setMyLocationButtonEnabled(true);
            startLocationUpdates();
        }
    }

    private void startLocationUpdates() {
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500)
                .setMinUpdateIntervalMillis(250)
                .build();

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enableMapFeatures();
            } else {
                Toast.makeText(this, "Permission denied.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isRecording) return;
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(inputBuffer, 3, inputBuffer, 0, inputBuffer.length - 3);
            inputBuffer[inputBuffer.length - 3] = event.values[0];
            inputBuffer[inputBuffer.length - 2] = event.values[1];
            inputBuffer[inputBuffer.length - 1] = event.values[2];

            inferenceCounter++;
            if (inferenceCounter >= INFERENCE_INTERVAL_SAMPLES) {
                final float[] bufferCopy = inputBuffer.clone();
                inferenceExecutor.execute(() -> runInference(bufferCopy));
                inferenceCounter = 0;
            }
        }
    }

    private void runInference(float[] data) {
        float[] results = classify(data);
        if (results != null && results.length > 0) {
            int maxIdx = 0;
            float maxVal = -1;
            for (int i = 0; i < results.length; i++) {
                if (results[i] > maxVal) {
                    maxVal = results[i];
                    maxIdx = i;
                }
            }

            Terrain detected;
            switch (maxIdx) {
                case 1: detected = Terrain.SMOOTH; break;
                case 2: detected = Terrain.TOUGH; break;
                default: detected = Terrain.IDLE;
            }

            synchronized (recentTerrains) {
                recentTerrains.add(detected);
                if (recentTerrains.size() > SMOOTHING_WINDOW_SIZE) {
                    recentTerrains.removeFirst();
                }
                Terrain smoothedTerrain = getMostFrequent(recentTerrains);
                lastClassificationColor = smoothedTerrain.color;
                lastClassificationLabel = smoothedTerrain.label;
                final String label = smoothedTerrain.label;
                final float confidence = maxVal;
                runOnUiThread(() -> statusText.setText(String.format(Locale.US, "%s %.2", label, confidence)));
            }
        }
    }

    private Terrain getMostFrequent(List<Terrain> list) {
        if (list.isEmpty()) return Terrain.IDLE;
        Terrain winner = list.get(0);
        int maxCount = 0;
        for (Terrain t : Terrain.values()) {
            int count = Collections.frequency(list, t);
            if (count > maxCount) {
                maxCount = count;
                winner = t;
            }
        }
        return winner;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    protected void onResume() {
        super.onResume();
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        inferenceExecutor.shutdown();
    }

    static {
        System.loadLibrary("ruker");
    }

    public native float[] classify(float[] input);
}
