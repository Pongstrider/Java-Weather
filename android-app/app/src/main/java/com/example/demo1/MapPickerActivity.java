package com.example.demo1;

import android.content.Intent;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MapPickerActivity extends AppCompatActivity {
    static final String LATITUDE = "latitude";
    static final String LONGITUDE = "longitude";
    static final String PLACE_NAME = "place_name";
    static final String FOCUS_SEARCH = "focus_search";

    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();
    private MapView map;
    private Marker marker;
    private EditText searchBox;
    private Button searchButton;
    private Button confirm;
    private TextView coordinates;
    private GeoPoint selectedPoint;
    private String selectedName;

    @Override
    protected void onCreate(Bundle state) {
        var appearance = getSharedPreferences("appearance", MODE_PRIVATE);
        if (appearance.contains("dark_theme")) {
            getDelegate().setLocalNightMode(appearance.getBoolean("dark_theme", false)
                    ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        }
        super.onCreate(state);

        var config = Configuration.getInstance();
        config.load(getApplicationContext(), getSharedPreferences("osmdroid", MODE_PRIVATE));
        File cache = new File(getCacheDir(), "osmdroid");
        config.setOsmdroidBasePath(cache);
        config.setOsmdroidTileCache(new File(cache, "tiles"));
        config.setUserAgentValue("Java-Weather/1.0 https://github.com/orbeg/Java-Weather");
        config.setTileFileSystemCacheMaxBytes(50L * 1024 * 1024);
        config.setTileFileSystemCacheTrimBytes(40L * 1024 * 1024);

        setContentView(R.layout.activity_map_picker);
        map = findViewById(R.id.map);
        coordinates = findViewById(R.id.coordinates);
        confirm = findViewById(R.id.confirm);
        searchBox = findViewById(R.id.search_box);
        searchButton = findViewById(R.id.search_button);
        confirm.setOnClickListener(view -> confirmLocation());
        searchButton.setOnClickListener(view -> searchLocation());
        searchBox.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                searchLocation();
                return true;
            }
            return false;
        });
        findViewById(R.id.report_issue).setOnClickListener(view ->
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.openstreetmap.org/fixthemap"))));

        map.setTileSource(new XYTileSource("OpenTopoMap", 0, 17, 256, ".png",
                new String[]{"https://tile.opentopomap.org/"},
                "© OpenStreetMap contributors, SRTM | OpenTopoMap (CC BY-SA)"));
        map.setMultiTouchControls(true);
        map.getController().setZoom(12.0);
        map.getController().setCenter(new GeoPoint(62.3908, 17.3069));
        map.getOverlays().add(new MapEventsOverlay(new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint point) {
                selectLocation(point, getString(R.string.map_selected_place));
                return true;
            }

            @Override
            public boolean longPressHelper(GeoPoint point) {
                return false;
            }
        }));

        if (state != null && state.containsKey(LATITUDE)) {
            GeoPoint point = new GeoPoint(state.getDouble(LATITUDE), state.getDouble(LONGITUDE));
            selectLocation(point, state.getString(PLACE_NAME));
            map.getController().setCenter(point);
        } else if (state == null && getIntent().getBooleanExtra(FOCUS_SEARCH, false)) {
            searchBox.requestFocus();
            getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
    }

    private void selectLocation(GeoPoint point, String name) {
        if (marker != null) {
            map.getOverlays().remove(marker);
        }
        marker = new Marker(map);
        marker.setPosition(point);
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        marker.setTitle(name);
        marker.setDraggable(true);
        marker.setOnMarkerDragListener(new Marker.OnMarkerDragListener() {
            @Override public void onMarkerDragStart(Marker marker) {}
            @Override public void onMarkerDrag(Marker marker) {}
            @Override public void onMarkerDragEnd(Marker marker) {
                String name = getString(R.string.map_selected_place);
                marker.setTitle(name);
                updateSelection(marker.getPosition(), name);
            }
        });
        map.getOverlays().add(marker);
        updateSelection(point, name);
        map.invalidate();
    }

    private void updateSelection(GeoPoint point, String name) {
        selectedPoint = point;
        selectedName = name;
        coordinates.setText(String.format(Locale.getDefault(), "%.4f, %.4f",
                point.getLatitude(), point.getLongitude()));
        confirm.setEnabled(true);
    }

    private void confirmLocation() {
        if (selectedPoint == null) {
            return;
        }
        Intent result = new Intent();
        result.putExtra(LATITUDE, selectedPoint.getLatitude());
        result.putExtra(LONGITUDE, selectedPoint.getLongitude());
        result.putExtra(PLACE_NAME, selectedName);
        setResult(RESULT_OK, result);
        finish();
    }

    @SuppressWarnings("deprecation")
    private void searchLocation() {
        String query = searchBox.getText().toString().trim();
        if (query.isEmpty() || !searchButton.isEnabled()) {
            return;
        }
        searchButton.setEnabled(false);
        getSystemService(InputMethodManager.class)
                .hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
        searchExecutor.execute(() -> {
            try {
                List<Address> matches = new Geocoder(getApplicationContext(), Locale.getDefault())
                        .getFromLocationName(query, 1);
                Address address = matches == null || matches.isEmpty() ? null : matches.get(0);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    searchButton.setEnabled(true);
                    if (address == null || !address.hasLatitude() || !address.hasLongitude()) {
                        Toast.makeText(this, R.string.map_no_result, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    GeoPoint point = new GeoPoint(address.getLatitude(), address.getLongitude());
                    String name = address.getLocality() == null ? query : address.getLocality();
                    map.getController().setZoom(14.0);
                    map.getController().animateTo(point);
                    selectLocation(point, name);
                });
            } catch (IOException | IllegalArgumentException error) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        searchButton.setEnabled(true);
                        Toast.makeText(this, R.string.map_search_error, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (selectedPoint != null) {
            outState.putDouble(LATITUDE, selectedPoint.getLatitude());
            outState.putDouble(LONGITUDE, selectedPoint.getLongitude());
            outState.putString(PLACE_NAME, selectedName);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        map.onResume();
    }

    @Override protected void onPause() {
        map.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        searchExecutor.shutdownNow();
        map.onDetach();
        super.onDestroy();
    }
}
