package com.example.demo1;

import java.io.Serializable;

/** En dags väderprognos i femdagarsöversikten. */
public final class DailyForecast implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String dayName;
    private final double maxTemp;
    private final double minTemp;
    private final String symbolCode;

    public DailyForecast(String dayName, double maxTemp, double minTemp, String symbolCode) {
        this.dayName = dayName;
        this.maxTemp = maxTemp;
        this.minTemp = minTemp;
        this.symbolCode = symbolCode;
    }

    public String getDayName() {
        return dayName;
    }

    public double getMaxTemp() {
        return maxTemp;
    }

    public double getMinTemp() {
        return minTemp;
    }

    public String getSymbolCode() {
        return symbolCode;
    }
}
