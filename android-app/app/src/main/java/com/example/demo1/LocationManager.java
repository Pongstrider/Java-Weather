package com.example.demo1;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.Locale;

public class LocationManager {

    private final Context context;
    private final FusedLocationProviderClient fusedLocationClient;

    public interface LocationCallback {
        void onLocationReceived(double latitude, double longitude);
        void onLocationError(String error);
    }

    public interface WeatherUrlCallback {
        void onUrlReady(String url);
        void onError(String error);
    }

    public LocationManager(Context context) {
        this.context = context.getApplicationContext();
        this.fusedLocationClient = LocationServices.getFusedLocationProviderClient(this.context);
    }

    /**
     * Bygger API-länken till MET.no baserat på latitud och longitud.
     * Koordinaterna avrundas till 4 decimaler enligt met.no-specifikationen,
     * och Locale.US säkerställer att punkt (.) används som decimalavgränsare.
     */
    public static String buildWeatherApiUrl(double latitude, double longitude) {
        return String.format(Locale.US, "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.4f&lon=%.4f", latitude, longitude);
    }

    /**
     * Tar telefonens platsinformation och returnerar den färdiga API-länken asynkront via callback.
     */
    public void getWeatherApiUrl(WeatherUrlCallback callback) {
        getCurrentLocation(new LocationCallback() {
            @Override
            public void onLocationReceived(double latitude, double longitude) {
                String apiUrl = buildWeatherApiUrl(latitude, longitude);
                callback.onUrlReady(apiUrl);
            }

            @Override
            public void onLocationError(String error) {
                callback.onError(error);
            }
        });
    }

    public void getCurrentLocation(LocationCallback callback) {
        boolean finePermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;

        boolean coarsePermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;

        if (!finePermission && !coarsePermission) {
            callback.onLocationError("Location permission not granted");
            return;
        }

        fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                null
        ).addOnSuccessListener(location -> {
            if (location != null) {
                double latitude = location.getLatitude();
                double longitude = location.getLongitude();
                callback.onLocationReceived(latitude, longitude);
            } else {
                callback.onLocationError("Could not get location");
            }
        }).addOnFailureListener(e ->
                callback.onLocationError(e.getMessage())
        );
    }
}
