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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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

    public static final class Forecast {
        public final double temperature;
        public final String symbolCode;
        public final List<DailyForecast> dailyForecasts;

        public Forecast(double temperature, String symbolCode, List<DailyForecast> dailyForecasts) {
            this.temperature = temperature;
            this.symbolCode = symbolCode;
            this.dailyForecasts = dailyForecasts;
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
        if (closed) {
            return;
        }
        executor.execute(() -> {
            try {
                Forecast forecast = fetchForecast(forecastUrl);
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

            JSONObject currentData = forecasts.getJSONObject(0).getJSONObject("data");
            double currentTemperature = currentData.getJSONObject("instant")
                    .getJSONObject("details")
                    .getDouble("air_temperature");

            String currentSymbolCode = extractSymbolCode(currentData);
            List<DailyForecast> dailyForecasts = parseDailyForecasts(forecasts);

            cachedUrl = forecastUrl;
            Forecast forecast = new Forecast(currentTemperature, currentSymbolCode, dailyForecasts);
            cachedForecast = forecast;
            expiresAt = connection.getExpiration();
            lastModified = connection.getLastModified();
            return forecast;
        } finally {
            connection.disconnect();
        }
    }

    private static String extractSymbolCode(JSONObject data) {
        for (String period : new String[]{"next_1_hours", "next_6_hours", "next_12_hours"}) {
            JSONObject interval = data.optJSONObject(period);
            JSONObject summary = interval == null ? null : interval.optJSONObject("summary");
            if (summary != null) {
                String symbolCode = summary.optString("symbol_code", "");
                if (!symbolCode.isEmpty()) {
                    return symbolCode;
                }
            }
        }
        return "";
    }

    private static List<DailyForecast> parseDailyForecasts(JSONArray timeseries) {
        Map<String, List<JSONObject>> entriesByDate = new LinkedHashMap<>();

        for (int i = 0; i < timeseries.length(); i++) {
            try {
                JSONObject item = timeseries.getJSONObject(i);
                String time = item.getString("time");
                if (time.length() >= 10) {
                    String dateKey = time.substring(0, 10);
                    entriesByDate.computeIfAbsent(dateKey, k -> new ArrayList<>()).add(item);
                }
            } catch (Exception ignored) {}
        }

        List<DailyForecast> dailyList = new ArrayList<>();

        for (Map.Entry<String, List<JSONObject>> entry : entriesByDate.entrySet()) {
            if (dailyList.size() >= 5) {
                break;
            }

            String dateKey = entry.getKey();
            List<JSONObject> items = entry.getValue();

            double maxTemp = -Double.MAX_VALUE;
            double minTemp = Double.MAX_VALUE;
            String bestSymbolCode = "";
            int closestHourDiff = 99;

            for (JSONObject item : items) {
                try {
                    JSONObject data = item.getJSONObject("data");
                    JSONObject instantDetails = data.getJSONObject("instant").getJSONObject("details");
                    double temp = instantDetails.getDouble("air_temperature");

                    if (temp > maxTemp) maxTemp = temp;
                    if (temp < minTemp) minTemp = temp;

                    String timeStr = item.getString("time");
                    int hour = 12;
                    if (timeStr.length() >= 13) {
                        try {
                            hour = Integer.parseInt(timeStr.substring(11, 13));
                        } catch (Exception ignored) {}
                    }

                    int hourDiff = Math.abs(hour - 12);
                    String symbol = extractSymbolCode(data);

                    if (!symbol.isEmpty() && hourDiff < closestHourDiff) {
                        closestHourDiff = hourDiff;
                        bestSymbolCode = symbol;
                    } else if (bestSymbolCode.isEmpty() && !symbol.isEmpty()) {
                        bestSymbolCode = symbol;
                    }
                } catch (Exception ignored) {}
            }

            if (maxTemp == -Double.MAX_VALUE) continue;

            String dayName = formatDayName(dateKey);
            dailyList.add(new DailyForecast(dayName, maxTemp, minTemp, bestSymbolCode));
        }

        return dailyList;
    }

    private static String formatDayName(String dateKey) {
        try {
            SimpleDateFormat inFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date date = inFormat.parse(dateKey);
            if (date != null) {
                Calendar targetCal = Calendar.getInstance();
                targetCal.setTime(date);

                Calendar todayCal = Calendar.getInstance();

                if (targetCal.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR) &&
                        targetCal.get(Calendar.DAY_OF_YEAR) == todayCal.get(Calendar.DAY_OF_YEAR)) {
                    return "Idag";
                }

                SimpleDateFormat dayOfWeekFormat = new SimpleDateFormat("EEE", new Locale("sv", "SE"));
                String dayName = dayOfWeekFormat.format(date);
                if (dayName.endsWith(".")) {
                    dayName = dayName.substring(0, dayName.length() - 1);
                }
                if (!dayName.isEmpty()) {
                    return dayName.substring(0, 1).toUpperCase(new Locale("sv", "SE")) + dayName.substring(1);
                }
            }
        } catch (Exception ignored) {}
        return dateKey;
    }

    @Override
    public void close() {
        closed = true;
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }
}
