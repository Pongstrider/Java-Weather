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
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hämtar väderprognos timme för timme för det nuvarande dygnet från MET Norway.
 */
public final class HourlyForecast implements AutoCloseable {

    private static final String USER_AGENT =
            "Java-Weather/1.0 https://github.com/orbeg/Java-Weather";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean closed;

    /**
     * Representerar väderprognosen för en enskild timme.
     */
    public static final class HourWeather {
        public final String timeIso;       // t.ex. "2026-11-18T16:00:00Z"
        public final int hour;             // timme på dygnet (0-23) i lokal tid
        public final double temperature;   // temperatur i °C
        public final double windSpeed;     // vindhastighet i m/s
        public final double feelsLike;     // upplevd temperatur i °C
        public final String symbolCode;    // vädersymbol (t.ex. "clearsky_day", "rain")

        public HourWeather(String timeIso, int hour, double temperature, double windSpeed, double feelsLike, String symbolCode) {
            this.timeIso = timeIso;
            this.hour = hour;
            this.temperature = temperature;
            this.windSpeed = windSpeed;
            this.feelsLike = feelsLike;
            this.symbolCode = symbolCode;
        }
    }

    public interface Callback {
        void onSuccess(List<HourWeather> hourlyForecasts);
        void onError(Exception error);
    }

    /**
     * Hämtar alla prognoser timme för timme för innevarande dygn.
     * Resultatet levereras på UI-tråden via Callback.
     */
    public void fetchTodayHourlyForecast(String forecastUrl, Callback callback) {
        if (closed) {
            return;
        }

        executor.execute(() -> {
            try {
                List<HourWeather> result = parseHourlyForecast(forecastUrl);
                mainHandler.post(() -> {
                    if (!closed) {
                        callback.onSuccess(result);
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

    private static List<HourWeather> parseHourlyForecast(String forecastUrl) throws Exception {
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

            List<HourWeather> hourlyList = new ArrayList<>();

            // ISO 8601 parser för MET.no-tider i UTC
            SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            isoFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

            // Dagens datum i enhetens lokala tidszon
            Calendar todayCal = Calendar.getInstance();
            int todayYear = todayCal.get(Calendar.YEAR);
            int todayDayOfYear = todayCal.get(Calendar.DAY_OF_YEAR);

            Calendar entryCal = Calendar.getInstance();

            for (int i = 0; i < timeseries.length(); i++) {
                JSONObject entry = timeseries.getJSONObject(i);
                String timeIso = entry.optString("time", "");

                if (timeIso.isEmpty()) {
                    continue;
                }

                Date parsedDate = isoFormat.parse(timeIso);
                if (parsedDate == null) {
                    continue;
                }

                entryCal.setTime(parsedDate);
                int entryYear = entryCal.get(Calendar.YEAR);
                int entryDayOfYear = entryCal.get(Calendar.DAY_OF_YEAR);

                // Kontrollera om prognosen tillhör dagens datum
                if (entryYear == todayYear && entryDayOfYear == todayDayOfYear) {
                    JSONObject data = entry.getJSONObject("data");
                    JSONObject details = data.getJSONObject("instant").getJSONObject("details");

                    double temp = details.getDouble("air_temperature");
                    double wind = details.optDouble("wind_speed", 0.0);
                    double humidity = details.optDouble("relative_humidity", 0.0);

                    double feelsLike;
                    if (details.has("apparent_air_temperature")) {
                        feelsLike = details.getDouble("apparent_air_temperature");
                    } else {
                        feelsLike = FeelsLikeTemperature.calculateApparentTemperature(temp, wind, humidity);
                    }
                    feelsLike = Math.round(feelsLike * 10.0) / 10.0;

                    // Hämta vädersymbol (next_1_hours är standarden för timprognoser)
                    String symbolCode = "";
                    for (String period : new String[]{"next_1_hours", "next_6_hours", "next_12_hours"}) {
                        JSONObject interval = data.optJSONObject(period);
                        JSONObject summary = interval == null ? null : interval.optJSONObject("summary");
                        if (summary != null) {
                            symbolCode = summary.optString("symbol_code", "");
                            if (!symbolCode.isEmpty()) {
                                break;
                            }
                        }
                    }

                    int hour = entryCal.get(Calendar.HOUR_OF_DAY);
                    hourlyList.add(new HourWeather(timeIso, hour, temp, wind, feelsLike, symbolCode));
                } else if (parsedDate.after(todayCal.getTime()) && (entryYear > todayYear || entryDayOfYear > todayDayOfYear)) {
                    // Vi har nått nästa dygn, avbryt
                    break;
                }
            }

            return hourlyList;
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
