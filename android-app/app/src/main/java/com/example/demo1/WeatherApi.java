package com.example.demo1;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Serializable;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Hämtar prognostiserad temperatur från MET Norway. */
public final class WeatherApi implements AutoCloseable {
    private static final String DEFAULT_FORECAST_URL =
            "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=62.39&lon=17.31";
    // Byt till projektets publika kontaktsida om adressen ändras.
    private static final String USER_AGENT =
            "Java-Weather/1.0 https://github.com/orbeg/Java-Weather";

    private static String cachedUrl;
    private static Forecast cachedForecast;
    private static long expiresAt;
    private static long lastModified;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean closed;

    public static final class Forecast implements Serializable {
        private static final long serialVersionUID = 2L;
        public final double temperature;
        public final String symbolCode;

        public final double windSpeed;
        public final double apparentTemperature;
        public final List<Hour> hours;
        public final List<DailyForecast> days;

        private Forecast(double temperature, String symbolCode, double windSpeed,
                         double apparentTemperature, List<Hour> hours, List<DailyForecast> days) {
            this.temperature = temperature;
            this.symbolCode = symbolCode;
            this.windSpeed = windSpeed;
            this.apparentTemperature = apparentTemperature;
            this.hours = Collections.unmodifiableList(hours);
            this.days = Collections.unmodifiableList(days);
        }
    }

    public static final class Hour implements Serializable {
        private static final long serialVersionUID = 1L;
        public final String time;
        public final double temperature;
        public final String symbolCode;

        private Hour(String time, double temperature, String symbolCode) {
            this.time = time;
            this.temperature = temperature;
            this.symbolCode = symbolCode;
        }
    }

    public interface Callback {
        void onSuccess(Forecast forecast);
        void onError(Exception error);
    }

    /** Hämtar väder för standardplatsen (Sundsvall). */
    public void fetchWeather(Callback callback) {
        fetchWeather(DEFAULT_FORECAST_URL, callback);
    }

    /** Nätverksanropet körs i bakgrunden med angiven URL. Resultatet levereras på UI-tråden. */
    public void fetchWeather(String forecastUrl, Callback callback) {
        // Upplevd temperatur ingår i MET:s fullständiga prognos.
        String completeUrl = forecastUrl.replace("/2.0/compact?", "/2.0/complete?");
        if (closed) {
            return;
        }
        executor.execute(() -> {
            try {
                Forecast forecast = fetchForecast(completeUrl);
                mainHandler.post(() -> {
                    if (!closed) {
                        callback.onSuccess(forecast);
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

    private static synchronized Forecast fetchForecast(String forecastUrl) throws Exception {
        if (Objects.equals(forecastUrl, cachedUrl) && cachedForecast != null && System.currentTimeMillis() < expiresAt) {
            return cachedForecast;
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(forecastUrl).openConnection();
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/json");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        if (Objects.equals(forecastUrl, cachedUrl) && cachedForecast != null && lastModified > 0) {
            connection.setIfModifiedSince(lastModified);
        }

        try {
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED && cachedForecast != null) {
                expiresAt = connection.getExpiration();
                return cachedForecast;
            }
            if (status != HttpURLConnection.HTTP_OK
                    && status != HttpURLConnection.HTTP_NOT_AUTHORITATIVE) {
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

            JSONArray forecasts = new JSONObject(json.toString())
                    .getJSONObject("properties").getJSONArray("timeseries");
            if (forecasts.length() == 0) {
                throw new IOException("Väderprognosen är tom");
            }
            JSONObject data = forecasts.getJSONObject(0).getJSONObject("data");
            JSONObject details = data.getJSONObject("instant").getJSONObject("details");
            List<Hour> hours = new ArrayList<>(24);
            List<DailyForecast> days = new ArrayList<>(5);
            SimpleDateFormat utc = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
            utc.setTimeZone(TimeZone.getTimeZone("UTC"));
            SimpleDateFormat localDate = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
            SimpleDateFormat weekday = new SimpleDateFormat("EEE", Locale.getDefault());
            SimpleDateFormat localHour = new SimpleDateFormat("H", Locale.ROOT);
            long startTime = -1;
            String firstDate = null;
            String activeDate = null;
            String dayName = "";
            String daySymbol = "";
            int closestToNoon = Integer.MAX_VALUE;
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;

            for (int i = 0; i < forecasts.length(); i++) {
                JSONObject entry = forecasts.getJSONObject(i);
                String time = entry.getString("time");
                Date date = utc.parse(time);
                if (date == null) {
                    continue;
                }
                String dateKey = localDate.format(date);
                if (firstDate == null) {
                    firstDate = dateKey;
                    startTime = date.getTime();
                }
                JSONObject hourData = entry.getJSONObject("data");
                JSONObject instant = hourData.optJSONObject("instant");
                JSONObject hourDetails = instant == null ? null : instant.optJSONObject("details");
                double value = hourDetails == null ? Double.NaN
                        : hourDetails.optDouble("air_temperature");
                String symbol = readSymbol(hourData);
                if (date.getTime() - startTime < 24 * 60 * 60 * 1000L) {
                    hours.add(new Hour(time, value, symbol));
                }

                // Börja i morgon så att dagens redan passerade timmar inte saknas.
                if (dateKey.equals(firstDate)) {
                    continue;
                }
                if (!dateKey.equals(activeDate)) {
                    if (Double.isFinite(min)) {
                        days.add(new DailyForecast(dayName, max, min, daySymbol));
                    }
                    if (days.size() == 5) {
                        break;
                    }
                    activeDate = dateKey;
                    dayName = weekday.format(date);
                    daySymbol = "";
                    closestToNoon = Integer.MAX_VALUE;
                    min = Double.POSITIVE_INFINITY;
                    max = Double.NEGATIVE_INFINITY;
                }
                if (Double.isFinite(value)) {
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                }
                int distanceToNoon = Math.abs(Integer.parseInt(localHour.format(date)) - 12);
                if (!symbol.isEmpty() && distanceToNoon < closestToNoon) {
                    daySymbol = symbol;
                    closestToNoon = distanceToNoon;
                }
            }
            if (days.size() < 5 && Double.isFinite(min)) {
                days.add(new DailyForecast(dayName, max, min, daySymbol));
            }

            Forecast forecast = new Forecast(details.getDouble("air_temperature"),
                    readSymbol(data), details.optDouble("wind_speed"),
                    details.optDouble("apparent_air_temperature"), hours, days);
            cachedUrl = forecastUrl;
            cachedForecast = forecast;
            expiresAt = connection.getExpiration();
            lastModified = connection.getLastModified();
            return forecast;
        } finally {
            connection.disconnect();
        }
    }

    private static String readSymbol(JSONObject data) {
        for (String period : new String[]{"next_1_hours", "next_6_hours", "next_12_hours"}) {
            JSONObject interval = data.optJSONObject(period);
            JSONObject summary = interval == null ? null : interval.optJSONObject("summary");
            if (summary != null) {
                String symbol = summary.optString("symbol_code");
                if (!symbol.isEmpty()) {
                    return symbol;
                }
            }
        }
        return "";
    }

    @Override
    public void close() {
        closed = true;
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }
}
