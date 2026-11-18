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
 * Hämtar eller beräknar "Känns som"-temperatur (°C) från MET Norway-API:et.
 * Baserat på MET Norways / Yr.no:s standardformler för Wind Chill (vid kyla och vind)
 * och Heat Index (vid värme och fukt).
 */
public final class FeelsLikeTemperature implements AutoCloseable {

    private static final String USER_AGENT =
            "Java-Weather/1.0 https://github.com/orbeg/Java-Weather";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean closed;

    public interface Callback {
        void onSuccess(double feelsLikeTemperature, String timeIso);
        void onError(Exception error);
    }

    /**
     * Hämtar "känns som"-temperaturen för aktuell tid (första i prognosen).
     */
    public void fetchCurrentFeelsLikeTemperature(String forecastUrl, Callback callback) {
        fetchFeelsLikeTemperatureForTime(forecastUrl, null, callback);
    }

    /**
     * Hämtar "känns som"-temperaturen för en vald tid.
     * @param forecastUrl URL:en till MET.no-prognosen
     * @param targetIsoTime Önskad tid i ISO-format (t.ex. "2026-11-18T15:00:00Z"),
     *                      eller null för att ta första tillgängliga tidpunkten.
     * @param callback Callback som tar emot resultatet på UI-tråden.
     */
    public void fetchFeelsLikeTemperatureForTime(String forecastUrl, String targetIsoTime, Callback callback) {
        if (closed) {
            return;
        }

        executor.execute(() -> {
            try {
                FeelsLikeData result = parseFeelsLikeTemperature(forecastUrl, targetIsoTime);
                mainHandler.post(() -> {
                    if (!closed) {
                        callback.onSuccess(result.feelsLikeTemp, result.timeIso);
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

    private static class FeelsLikeData {
        final double feelsLikeTemp;
        final String timeIso;

        FeelsLikeData(double feelsLikeTemp, String timeIso) {
            this.feelsLikeTemp = feelsLikeTemp;
            this.timeIso = timeIso;
        }
    }

    private static FeelsLikeData parseFeelsLikeTemperature(String forecastUrl, String targetIsoTime) throws Exception {
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
            JSONObject details = matchedEntry.getJSONObject("data")
                    .getJSONObject("instant")
                    .getJSONObject("details");

            double feelsLike;

            // Om MET.no skickar apparent_air_temperature direkt använder vi den
            if (details.has("apparent_air_temperature")) {
                feelsLike = details.getDouble("apparent_air_temperature");
            } else {
                // Annars beräknar vi enligt Yr.no / MET Norway-standarden
                double temperature = details.getDouble("air_temperature");
                double windSpeed = details.optDouble("wind_speed", 0.0);
                double humidity = details.optDouble("relative_humidity", 0.0);

                feelsLike = calculateApparentTemperature(temperature, windSpeed, humidity);
            }

            // Avrunda till en decimal
            double rounded = Math.round(feelsLike * 10.0) / 10.0;
            return new FeelsLikeData(rounded, foundTime);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Beräknar upplevd temperatur enligt MET Norway / Yr.no-standarden:
     * 1. Wind Chill (kyleffekt) om T < 10°C och vind > 1.33 m/s.
     * 2. Heat Index (värmeindex) om T > 26°C och luftfuktighet > 40%.
     * 3. Faktisk temperatur annars.
     */
    public static double calculateApparentTemperature(double temperature, double windSpeedMs, double relativeHumidity) {
        // Wind Chill formel (används vid kyla och vind)
        if (temperature < 10.0 && windSpeedMs > 1.33) {
            double windKmh = windSpeedMs * 3.6;
            return 13.12 + (0.6215 * temperature)
                    - (11.37 * Math.pow(windKmh, 0.16))
                    + (0.3965 * temperature * Math.pow(windKmh, 0.16));
        }

        // Heat Index (används vid höga temperaturer och fuktighet)
        if (temperature > 26.0 && relativeHumidity > 40.0) {
            // Rothfusz regression för Heat Index i Celsius
            double t = temperature;
            double r = relativeHumidity;
            return -8.78469475556
                    + 1.61139411 * t
                    + 2.33854883889 * r
                    - 0.14611605 * t * r
                    - 0.012308094 * (t * t)
                    - 0.0164248277778 * (r * r)
                    + 0.002211732 * (t * t) * r
                    + 0.00072546 * t * (r * r)
                    - 0.000003582 * (t * t) * (r * r);
        }

        return temperature;
    }

    @Override
    public void close() {
        closed = true;
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }
}
