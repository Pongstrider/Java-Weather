package com.example.demo1;

import android.Manifest;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.Toast;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Button;
import android.content.res.Configuration;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "weather_settings";
    private static final String PREF_NIGHT_MODE = "night_mode";
    private LocationManager locationManager;
    private final WeatherApi weatherApi = new WeatherApi();
    private TextView temperatureView;
    private LinearLayout dailyForecastContainer;
    private ImageView weatherIcon;
    private WeatherBackgroundView weatherBackground;
    private Button themeButton;

    private final ActivityResultLauncher<String[]> locationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                Boolean fineGranted = result.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false);
                Boolean coarseGranted = result.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false);

                if (Boolean.TRUE.equals(fineGranted) || Boolean.TRUE.equals(coarseGranted)) {
                    fetchWeatherForCurrentLocation();
                } else {
                    Toast.makeText(this, "Platsbehörighet krävs för att hämta väder baserat på din position", Toast.LENGTH_SHORT).show();
                    temperatureView.setText("Platsbehörighet saknas");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        int savedNightMode = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt(PREF_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        if (savedNightMode != AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) {
            AppCompatDelegate.setDefaultNightMode(savedNightMode);
        }
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        temperatureView = findViewById(R.id.tvTemperature);
        dailyForecastContainer = findViewById(R.id.dailyForecastContainer);
        weatherIcon = findViewById(R.id.ivWeatherIcon);
        weatherBackground = findViewById(R.id.weatherBackground);
        themeButton = findViewById(R.id.theme_button);
        updateThemeButton();
        themeButton.setOnClickListener(view -> toggleTheme());
        locationManager = new LocationManager(this);

        requestLocationAndFetchWeather();
    }

    private void requestLocationAndFetchWeather() {
        locationPermissionLauncher.launch(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        });
    }

    private void fetchWeatherForCurrentLocation() {
        temperatureView.setText("Hämtar plats…");
        weatherIcon.setImageResource(R.drawable.placeholder);
        weatherIcon.setContentDescription("Väder hämtas");
        locationManager.getWeatherApiUrl(new LocationManager.WeatherUrlCallback() {
            @Override
            public void onUrlReady(String url) {
                Log.d(TAG, "Anropar MET API med URL: " + url);
                temperatureView.setText("Hämtar väder…");

                weatherApi.fetchWeather(url, new WeatherApi.Callback() {
                    @Override
                    public void onSuccess(WeatherApi.Forecast forecast) {
                        temperatureView.setText(String.format(
                                Locale.getDefault(), "%.1f °C", forecast.temperature));
                        weatherIcon.setImageResource(weatherDrawableFor(forecast.symbolCode));
                        weatherIcon.setContentDescription(forecast.symbolCode.isEmpty()
                                ? "Vädersymbol saknas" : forecast.symbolCode.replace('_', ' '));
                        showDailyForecast(forecast);
                        weatherBackground.setWeather(forecast.symbolCode);
                    }

                    @Override
                    public void onError(Exception error) {
                        temperatureView.setText("Kunde inte hämta väder");
                        weatherIcon.setImageResource(R.drawable.placeholder);
                        weatherIcon.setContentDescription("Väder saknas");
                        weatherBackground.setWeather("");
                        Log.e(TAG, "Kunde inte hämta prognosen", error);
                    }
                });
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Kunde inte hämta plats/URL: " + error);
                Toast.makeText(MainActivity.this, error, Toast.LENGTH_SHORT).show();
                temperatureView.setText("Kunde inte hämta plats");
            }
        });
    }

    private void updateThemeButton() {
        boolean darkTheme = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        themeButton.setText(darkTheme
                ? R.string.theme_switch_to_light
                : R.string.theme_switch_to_dark);
    }

    private void toggleTheme() {
        boolean darkTheme = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int nightMode = darkTheme
                ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_YES;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putInt(PREF_NIGHT_MODE, nightMode)
                .apply();
        AppCompatDelegate.setDefaultNightMode(nightMode);
    }

    private void showDailyForecast(WeatherApi.Forecast forecast) {
        dailyForecastContainer.removeAllViews();
        if (forecast.dailyForecasts == null || forecast.dailyForecasts.isEmpty()) {
            TextView emptyMessage = new TextView(this);
            emptyMessage.setText(R.string.daily_forecast_unavailable);
            emptyMessage.setTextColor(getColor(R.color.white));
            dailyForecastContainer.addView(emptyMessage);
            return;
        }

        int dayCount = Math.min(5, forecast.dailyForecasts.size());
        for (int i = 0; i < dayCount; i++) {
            DailyForecast day = forecast.dailyForecasts.get(i);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(dp(6), dp(8), dp(6), dp(8));

            TextView dayLabel = new TextView(this);
            dayLabel.setText(day.getDayName());
            dayLabel.setTextColor(getColor(R.color.white));
            dayLabel.setTextSize(14);
            dayLabel.setGravity(Gravity.CENTER);
            card.addView(dayLabel, new LinearLayout.LayoutParams(dp(54),
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            ImageView icon = new ImageView(this);
            icon.setImageResource(weatherDrawableFor(day.getSymbolCode()));
            icon.setContentDescription(day.getSymbolCode().replace('_', ' '));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(40), dp(40));
            iconParams.topMargin = dp(4);
            card.addView(icon, iconParams);

            TextView temperatures = new TextView(this);
            temperatures.setText(String.format(Locale.getDefault(), "↑ %.0f°\n↓ %.0f°",
                    day.getMaxTemp(), day.getMinTemp()));
            temperatures.setTextColor(getColor(R.color.white));
            temperatures.setTextSize(14);
            temperatures.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams temperatureParams = new LinearLayout.LayoutParams(
                    dp(54), ViewGroup.LayoutParams.WRAP_CONTENT);
            temperatureParams.topMargin = dp(4);
            card.addView(temperatures, temperatureParams);

            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    dp(58), ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.setMarginEnd(dp(1));
            dailyForecastContainer.addView(card, cardParams);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** MET:s symbolkoder matchar filnamnen i drawable, även för dag och natt. */
    private static int weatherDrawableFor(String symbolCode) {
        if (symbolCode == null) {
            return R.drawable.placeholder;
        }
        switch (symbolCode) {
            case "cloudy": return R.drawable.cloudy;
            case "fog": return R.drawable.fog;
            case "heavyrain": return R.drawable.heavyrain;
            case "heavyrainandthunder": return R.drawable.heavyrainandthunder;
            case "heavysleet": return R.drawable.heavysleet;
            case "heavysleetandthunder": return R.drawable.heavysleetandthunder;
            case "heavysnow": return R.drawable.heavysnow;
            case "heavysnowandthunder": return R.drawable.heavysnowandthunder;
            case "lightrain": return R.drawable.lightrain;
            case "lightrainandthunder": return R.drawable.lightrainandthunder;
            case "lightsleet": return R.drawable.lightsleet;
            case "lightsleetandthunder": return R.drawable.lightsleetandthunder;
            case "lightsnow": return R.drawable.lightsnow;
            case "lightsnowandthunder": return R.drawable.lightsnowandthunder;
            case "rain": return R.drawable.rain;
            case "rainandthunder": return R.drawable.rainandthunder;
            case "sleet": return R.drawable.sleet;
            case "sleetandthunder": return R.drawable.sleetandthunder;
            case "snow": return R.drawable.snow;
            case "snowandthunder": return R.drawable.snowandthunder;
            case "clearsky_day": return R.drawable.clearsky_day;
            case "clearsky_night": return R.drawable.clearsky_night;
            case "clearsky_polartwilight": return R.drawable.clearsky_polartwilight;
            case "fair_day": return R.drawable.fair_day;
            case "fair_night": return R.drawable.fair_night;
            case "fair_polartwilight": return R.drawable.fair_polartwilight;
            case "partlycloudy_day": return R.drawable.partlycloudy_day;
            case "partlycloudy_night": return R.drawable.partlycloudy_night;
            case "partlycloudy_polartwilight": return R.drawable.partlycloudy_polartwilight;
            case "heavyrainshowers_day": return R.drawable.heavyrainshowers_day;
            case "heavyrainshowers_night": return R.drawable.heavyrainshowers_night;
            case "heavyrainshowers_polartwilight": return R.drawable.heavyrainshowers_polartwilight;
            case "heavyrainshowersandthunder_day": return R.drawable.heavyrainshowersandthunder_day;
            case "heavyrainshowersandthunder_night": return R.drawable.heavyrainshowersandthunder_night;
            case "heavyrainshowersandthunder_polartwilight": return R.drawable.heavyrainshowersandthunder_polartwilight;
            case "heavysleetshowers_day": return R.drawable.heavysleetshowers_day;
            case "heavysleetshowers_night": return R.drawable.heavysleetshowers_night;
            case "heavysleetshowers_polartwilight": return R.drawable.heavysleetshowers_polartwilight;
            case "heavysleetshowersandthunder_day": return R.drawable.heavysleetshowersandthunder_day;
            case "heavysleetshowersandthunder_night": return R.drawable.heavysleetshowersandthunder_night;
            case "heavysleetshowersandthunder_polartwilight": return R.drawable.heavysleetshowersandthunder_polartwilight;
            case "heavysnowshowers_day": return R.drawable.heavysnowshowers_day;
            case "heavysnowshowers_night": return R.drawable.heavysnowshowers_night;
            case "heavysnowshowers_polartwilight": return R.drawable.heavysnowshowers_polartwilight;
            case "heavysnowshowersandthunder_day": return R.drawable.heavysnowshowersandthunder_day;
            case "heavysnowshowersandthunder_night": return R.drawable.heavysnowshowersandthunder_night;
            case "heavysnowshowersandthunder_polartwilight": return R.drawable.heavysnowshowersandthunder_polartwilight;
            case "lightrainshowers_day": return R.drawable.lightrainshowers_day;
            case "lightrainshowers_night": return R.drawable.lightrainshowers_night;
            case "lightrainshowers_polartwilight": return R.drawable.lightrainshowers_polartwilight;
            case "lightrainshowersandthunder_day": return R.drawable.lightrainshowersandthunder_day;
            case "lightrainshowersandthunder_night": return R.drawable.lightrainshowersandthunder_night;
            case "lightrainshowersandthunder_polartwilight": return R.drawable.lightrainshowersandthunder_polartwilight;
            case "lightsleetshowers_day": return R.drawable.lightsleetshowers_day;
            case "lightsleetshowers_night": return R.drawable.lightsleetshowers_night;
            case "lightsleetshowers_polartwilight": return R.drawable.lightsleetshowers_polartwilight;
            case "lightsnowshowers_day": return R.drawable.lightsnowshowers_day;
            case "lightsnowshowers_night": return R.drawable.lightsnowshowers_night;
            case "lightsnowshowers_polartwilight": return R.drawable.lightsnowshowers_polartwilight;
            case "lightssleetshowersandthunder_day": return R.drawable.lightssleetshowersandthunder_day;
            case "lightssleetshowersandthunder_night": return R.drawable.lightssleetshowersandthunder_night;
            case "lightssleetshowersandthunder_polartwilight": return R.drawable.lightssleetshowersandthunder_polartwilight;
            case "lightssnowshowersandthunder_day": return R.drawable.lightssnowshowersandthunder_day;
            case "lightssnowshowersandthunder_night": return R.drawable.lightssnowshowersandthunder_night;
            case "lightssnowshowersandthunder_polartwilight": return R.drawable.lightssnowshowersandthunder_polartwilight;
            case "rainshowers_day": return R.drawable.rainshowers_day;
            case "rainshowers_night": return R.drawable.rainshowers_night;
            case "rainshowers_polartwilight": return R.drawable.rainshowers_polartwilight;
            case "rainshowersandthunder_day": return R.drawable.rainshowersandthunder_day;
            case "rainshowersandthunder_night": return R.drawable.rainshowersandthunder_night;
            case "rainshowersandthunder_polartwilight": return R.drawable.rainshowersandthunder_polartwilight;
            case "sleetshowers_day": return R.drawable.sleetshowers_day;
            case "sleetshowers_night": return R.drawable.sleetshowers_night;
            case "sleetshowers_polartwilight": return R.drawable.sleetshowers_polartwilight;
            case "sleetshowersandthunder_day": return R.drawable.sleetshowersandthunder_day;
            case "sleetshowersandthunder_night": return R.drawable.sleetshowersandthunder_night;
            case "sleetshowersandthunder_polartwilight": return R.drawable.sleetshowersandthunder_polartwilight;
            case "snowshowers_day": return R.drawable.snowshowers_day;
            case "snowshowers_night": return R.drawable.snowshowers_night;
            case "snowshowers_polartwilight": return R.drawable.snowshowers_polartwilight;
            case "snowshowersandthunder_day": return R.drawable.snowshowersandthunder_day;
            case "snowshowersandthunder_night": return R.drawable.snowshowersandthunder_night;
            case "snowshowersandthunder_polartwilight": return R.drawable.snowshowersandthunder_polartwilight;
            default: return R.drawable.placeholder;
        }
    }

    @Override
    protected void onDestroy() {
        weatherApi.close();
        super.onDestroy();
    }
}
