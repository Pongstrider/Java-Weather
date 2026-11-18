package com.example.demo1;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hämtar vindhastighet (m/s) från MET Norway-API:et.
 */
public final class WindSpeed implements AutoCloseable {

    private static final String USER_AGENT =
            "Java-Weather/1.0 https://github.com/orbeg/Java-Weather";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean closed;

    public interface Callback {
        void onSuccess(double windSpeed, String timeIso);
        void onError(Exception error);
    }

    /**
     * Hämtar vindhastigheten för den aktuella (första tillgängliga) tiden.
     */
    public void fetchCurrentWindSpeed(String forecastUrl, Callback callback) {
        fetchWindSpeedForTime(forecastUrl, null, callback);
    }

    /**
     * Hämtar vindhastigheten för en vald tid.
     * @param forecastUrl URL:en till MET.no-prognosen
     * @param targetIsoTime Önskad tid i ISO-format (t.ex. "2026-11-18T15:00:00Z"),
     *                      eller null för att ta första tillgängliga tidpunkten.
     * @param callback Callback som tar emot resultatet på UI-tråden.
     */
    public void fetchWindSpeedForTime(String forecastUrl, String targetIsoTime, Callback callback) {
        if (closed) {
            return;
        }

        executor.execute(() -> {
            try {
                WindData result = parseWindSpeed(forecastUrl, targetIsoTime);
                mainHandler.post(() -> {
                    if (!closed) {
                        callback.onSuccess(result.windSpeed, result.timeIso);
                    }
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (!closed) {
                        callback.onError(error);
                    }
                });
            }
        });
    }

    private static class WindData {
        final double windSpeed;
        final String timeIso;

        WindData(double windSpeed, String timeIso) {
            this.windSpeed = windSpeed;
            this.timeIso = timeIso;
        }
    }

    private static WindData parseWindSpeed(String forecastUrl, String targetIsoTime) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(forecastUrl).openConnection();
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/json");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);

        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_NOT_AUTHORITATIVE) {
                throw new IOException("Väder-API svarade med HTTP " + status);
            }

            StringBuilder json = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    json.append(line);
                }
            }

            JSONArray timeseries = new JSONObject(json.toString())
                    .getJSONObject("properties")
                    .getJSONArray("timeseries");

            if (timeseries.length() == 0) {
                throw new IOException("Väderprognosen är tom");
            }

            JSONObject matchedEntry = null;

            // Om användaren har valt en specifik tid, leta upp den i tidsserien
            if (targetIsoTime != null && !targetIsoTime.isEmpty()) {
                for (int i = 0; i < timeseries.length(); i++) {
                    JSONObject entry = timeseries.getJSONObject(i);
                    String time = entry.optString("time", "");
                    if (time.startsWith(targetIsoTime)) {
                        matchedEntry = entry;
                        break;
                    }
                }
            }

            // Om ingen specifik tid valdes eller inte hittades, ta första bästa (nuvarande tid)
            if (matchedEntry == null) {
                if (targetIsoTime != null && !targetIsoTime.isEmpty()) {
                    throw new IOException("Kunde inte hitta prognos för angiven tid: " + targetIsoTime);
                }
                matchedEntry = timeseries.getJSONObject(0);
            }

            String foundTime = matchedEntry.optString("time", "");
            double speed = matchedEntry.getJSONObject("data")
                    .getJSONObject("instant")
                    .getJSONObject("details")
                    .getDouble("wind_speed");

            return new WindData(speed, foundTime);
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public void close() {
        closed = true;
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }
}