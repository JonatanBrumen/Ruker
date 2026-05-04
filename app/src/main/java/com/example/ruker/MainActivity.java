package com.example.ruker;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.NonNull;
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

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback, SensorEventListener {

    private GoogleMap mMap;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private LatLng lastLatLng;

    private SensorManager sensorManager;
    private Sensor accelerometer;
    
    // Model constants from metadata
    private static final int RAW_SAMPLE_COUNT = 125;
    private static final int SAMPLES_PER_FRAME = 3;
    private final float[] inputBuffer = new float[RAW_SAMPLE_COUNT * SAMPLES_PER_FRAME];
    private int bufferIndex = 0;

    private TextView statusText;
    private int lastClassificationColor = Color.GRAY;

    // 1. Define Terrain Types for modularity
    enum Terrain {
        IDLE(Color.GRAY, "Stationary"),
        SMOOTH(Color.GREEN, "Smooth Path"),
        TOUGHER(Color.YELLOW, "Tougher Terrain"),
        ROUGH(Color.RED, "Rough/Dangerous");

        final int color;
        final String label;
        Terrain(int color, String label) {
            this.color = color;
            this.label = label;
        }
    }

    // 2. Smoothing variables for stable map coloring
    private static final int SMOOTHING_WINDOW_SIZE = 5;
    private final LinkedList<Terrain> recentTerrains = new LinkedList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);

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
    }

    private void setupLocationUpdates() {
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                for (Location location : locationResult.getLocations()) {
                    LatLng currentLatLng = new LatLng(location.getLatitude(), location.getLongitude());
                    if (lastLatLng != null && mMap != null) {
                        mMap.addPolyline(new PolylineOptions()
                                .add(lastLatLng, currentLatLng)
                                .color(lastClassificationColor)
                                .width(12));
                    }
                    lastLatLng = currentLatLng;
                    if (mMap != null) {
                        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 17f));
                    }
                }
            }
        };
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            return;
        }
        mMap.setMyLocationEnabled(true);
        startLocationUpdates();
    }

    private void startLocationUpdates() {
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMinUpdateIntervalMillis(500)
                .build();

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            if (bufferIndex < inputBuffer.length - 3) {
                inputBuffer[bufferIndex++] = event.values[0];
                inputBuffer[bufferIndex++] = event.values[1];
                inputBuffer[bufferIndex++] = event.values[2];
            } else {
                runInference();
                bufferIndex = 0;
            }
        }
    }

    private void runInference() {
        float[] results = classify(inputBuffer);
        if (results != null && results.length > 0) {
            int maxIdx = 0;
            float maxVal = -1;
            for (int i = 0; i < results.length; i++) {
                if (results[i] > maxVal) {
                    maxVal = results[i];
                    maxIdx = i;
                }
            }

            // 3. Map Model Output to App Logic
            Terrain detected;
            switch (maxIdx) {
                case 0: // Circle -> Smooth
                    detected = Terrain.SMOOTH;
                    break;
                case 1: // Idle -> Stationary
                    detected = Terrain.IDLE;
                    break;
                case 2: // Leftright -> Tougher
                    detected = Terrain.TOUGHER;
                    break;
                case 3: // Updown -> Rough
                    detected = Terrain.ROUGH;
                    break;
                default:
                    detected = Terrain.IDLE;
            }

            // 4. Apply Smoothing (Majority Vote)
            recentTerrains.add(detected);
            if (recentTerrains.size() > SMOOTHING_WINDOW_SIZE) {
                recentTerrains.removeFirst();
            }

            Terrain smoothedTerrain = getMostFrequent(recentTerrains);
            lastClassificationColor = smoothedTerrain.color;

            float finalMaxVal = maxVal;
            runOnUiThread(() -> statusText.setText(String.format(Locale.US, "[%s] Confidence: %.2f", smoothedTerrain.label, finalMaxVal)));
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
        fusedLocationClient.removeLocationUpdates(locationCallback);
    }

    static {
        System.loadLibrary("ruker");
    }

    public native float[] classify(float[] input);
}
