package com.example.demo1;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

public final class WeatherBackgroundView extends View {
    private static final int PARTICLE_COUNT = 72;
    private final Paint sky = new Paint();
    private final Paint particle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] x = new float[PARTICLE_COUNT];
    private final float[] y = new float[PARTICLE_COUNT];
    private final float[] speed = new float[PARTICLE_COUNT];
    private final float density;
    private boolean rain;
    private boolean snow;
    private long previousFrame;

    public WeatherBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        Random random = new Random();
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            x[i] = random.nextFloat();
            y[i] = random.nextFloat();
            speed[i] = 0.6f + random.nextFloat() * 0.8f;
        }
        particle.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setWeather(String symbolCode) {
        String code = symbolCode == null ? "" : symbolCode;
        rain = code.contains("rain") || code.contains("sleet");
        snow = code.contains("snow") || code.contains("sleet");
        previousFrame = 0;
        updateSky();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        updateSky();
    }

    private void updateSky() {
        if (getHeight() == 0) {
            return;
        }
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int top = dark ? 0xff0b1326 : 0xff1557a0;
        int bottom = dark ? 0xff1e304c : 0xff3477ae;
        if (rain || snow) {
            top = dark ? 0xff111723 : 0xff354b65;
            bottom = dark ? 0xff28364a : 0xff536b85;
        }
        sky.setShader(new LinearGradient(0, 0, 0, getHeight(), top, bottom, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getHeight(), sky);
        if ((!rain && !snow) || getWidth() == 0 || getHeight() == 0) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        float seconds = previousFrame == 0 ? 0 : Math.min(now - previousFrame, 32) / 1000f;
        previousFrame = now;

        float span = getHeight() + 48 * density;
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            boolean flake = snow && (!rain || i % 2 == 0);
            float fallSpeed = flake ? 52 : 520;
            y[i] = (y[i] + seconds * fallSpeed * density * speed[i] / span) % 1;
            float py = y[i] * span - 24 * density;
            float px = x[i] * getWidth();
            if (flake) {
                float drift = (float) Math.sin(y[i] * 18 + i) * 9 * density;
                particle.setColor(0xbbffffff);
                canvas.drawCircle(px + drift, py, speed[i] * 2.4f * density, particle);
            } else {
                particle.setColor(0x99bde4ff);
                particle.setStrokeWidth(1.3f * density);
                canvas.drawLine(px, py, px - 5 * density, py + 17 * density, particle);
            }
        }
        if (isShown() && getWindowVisibility() == VISIBLE) {
            postInvalidateOnAnimation();
        }
    }
}
