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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Hämtar väderprognos för flera dagar framåt från MET Norway. */
public final class daysapi implements AutoCloseable {
    private static final String DEFAULT_FORECAST_URL =
            "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=62.39&lon=17.31";
    private static final String USER_AGENT =
            "Java-Weather/1.0 https://github.com/orbeg/Java-Weather";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean closed;

    public static final class DayForecast {
        public final String date;
        public final String dayName;
        public final double temperature;
        public final String symbolCode;

        public DayForecast(String date, String dayName, double temperature, String symbolCode) {
            this.date = date;
            this.dayName = dayName;
            this.temperature = temperature;
            this.symbolCode = symbolCode;
        }
    }

    public interface Callback {
        void onSuccess(List<DayForecast> forecasts);
        void onError(Exception error);
    }

    public void fetchDaysForecast(Callback callback) {
        fetchDaysForecast(DEFAULT_FORECAST_URL, callback);
    }

    public void fetchDaysForecast(String forecastUrl, Callback callback) {
        if (closed) return;
        executor.execute(() -> {
            try {
                List<DayForecast> forecasts = parseDaysForecast(forecastUrl);
                mainHandler.post(() -> {
                    if (!closed) {
                        callback.onSuccess(forecasts);
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

    private static List<DayForecast> parseDaysForecast(String forecastUrl) throws Exception {
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
                    .getJSONObject("properties").getJSONArray("timeseries");
            if (timeseries.length() == 0) {
                throw new IOException("Väderprognosen är tom");
            }

            java.util.Map<String, DayCandidate> dailyMap = new java.util.LinkedHashMap<>();

            SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            SimpleDateFormat dateOnlyFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            SimpleDateFormat dayOfWeekFormat = new SimpleDateFormat("EEE", new Locale("sv", "SE"));

            for (int i = 0; i < timeseries.length(); i++) {
                JSONObject entry = timeseries.getJSONObject(i);
                String timeStr = entry.optString("time", "");
                if (timeStr.length() >= 19) {
                    String cleanTime = timeStr.substring(0, 19);
                    try {
                        Date date = isoFormat.parse(cleanTime);
                        if (date != null) {
                            String dateKey = dateOnlyFormat.format(date);
                            Calendar cal = Calendar.getInstance();
                            cal.setTime(date);
                            int hour = cal.get(Calendar.HOUR_OF_DAY);

                            JSONObject data = entry.getJSONObject("data");
                            double temp = data.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature");

                            String symbolCode = "";
                            for (String period : new String[]{"next_1_hours", "next_6_hours", "next_12_hours"}) {
                                JSONObject interval = data.optJSONObject(period);
                                JSONObject summary = interval == null ? null : interval.optJSONObject("summary");
                                if (summary != null) {
                                    symbolCode = summary.optString("symbol_code", "");
                                    if (!symbolCode.isEmpty()) break;
                                }
                            }

                            int hourDiff = Math.abs(hour - 12);
                            DayCandidate existing = dailyMap.get(dateKey);
                            if (existing == null || hourDiff < existing.hourDiff) {
                                String dayName = dayOfWeekFormat.format(date);
                                if (!dayName.isEmpty()) {
                                    dayName = dayName.substring(0, 1).toUpperCase(new Locale("sv", "SE")) + dayName.substring(1);
                                }
                                dailyMap.put(dateKey, new DayCandidate(dateKey, dayName, temp, symbolCode, hourDiff));
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }

            List<DayForecast> result = new ArrayList<>();
            int count = 0;
            for (DayCandidate candidate : dailyMap.values()) {
                result.add(new DayForecast(candidate.date, candidate.dayName, candidate.temperature, candidate.symbolCode));
                count++;
                if (count >= 5) break;
            }

            return result;
        } finally {
            connection.disconnect();
        }
    }

    private static class DayCandidate {
        final String date;
        final String dayName;
        final double temperature;
        final String symbolCode;
        final int hourDiff;

        DayCandidate(String date, String dayName, double temperature, String symbolCode, int hourDiff) {
            this.date = date;
            this.dayName = dayName;
            this.temperature = temperature;
            this.symbolCode = symbolCode;
            this.hourDiff = hourDiff;
        }
    }

    @Override
    public void close() {
        closed = true;
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }
}
