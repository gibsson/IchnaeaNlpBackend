/*
 * Copyright (C) 2013-2017 microG Project Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.microg.nlp.backend.ichnaea;

import gapchenko.llttz.*;
import gapchenko.llttz.stores.*;

import android.app.AlarmManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Build;
import android.os.Process;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.microg.nlp.api.CellBackendHelper;
import org.microg.nlp.api.HelperLocationBackendService;
import org.microg.nlp.api.LocationHelper;
import org.microg.nlp.api.WiFiBackendHelper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Set;
import java.util.TimeZone;

import static org.microg.nlp.api.CellBackendHelper.Cell;
import static org.microg.nlp.api.WiFiBackendHelper.WiFi;

public class BackendService extends HelperLocationBackendService
        implements WiFiBackendHelper.Listener, CellBackendHelper.Listener {

    private static final String TAG = "IchnaeaBackendService";
    private static final String SERVICE_URL = "https://location.services.mozilla.com/v1/geolocate?key=%s";
    private static final String API_KEY = "068ab754-c06b-473d-a1e5-60e7b1a2eb77";
    private static final String PROVIDER = "ichnaea";
    private static final int RATE_LIMIT_MS = 10000;

    private static BackendService instance;

    private boolean running = false;
    private Set<WiFi> wiFis;
    private Set<Cell> cells;
    private Thread thread;
    private long lastRequestTime = 0;

    private boolean useWiFis = true;
    private boolean useCells = true;

    private boolean replay = false;
    private String lastRequest = null;
    private Location lastResponse = null;

    private ContentResolver mCr = null;
    private Context mContext = null;
    private AlarmManager mAlarmManager = null;

    @Override
    public synchronized void onCreate() {
        super.onCreate();
        reloadSettings();
        reloadInstanceSettings();
        mContext = this;
        mAlarmManager =
            (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mCr = mContext.getContentResolver();
    }

    @Override
    protected synchronized void onOpen() {
        super.onOpen();
        reloadSettings();
        instance = this;
        running = true;
        Log.d(TAG, "Activating instance at process " + Process.myPid());
    }

    public static void reloadInstanceSettings() {
        if (instance != null) {
            instance.reloadSettings();
        } else {
            Log.d(TAG, "No instance found active.");
        }
    }

    private void reloadSettings() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        removeHelpers();
        if (preferences.getBoolean("use_cells", true) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            addHelper(new CellBackendHelper(this, this));
        } else {
            cells = null;
        }
        if (preferences.getBoolean("use_wifis", true)) {
            addHelper(new WiFiBackendHelper(this, this));
        } else {
            wiFis = null;
        }
    }

    @Override
    protected synchronized void onClose() {
        super.onClose();
        running = false;
        if (instance == this) {
            instance = null;
            Log.d(TAG, "Deactivating instance at process " + Process.myPid());
        }
    }

    @Override
    public void onWiFisChanged(Set<WiFi> wiFis) {
        this.wiFis = wiFis;
        if (running) startCalculate();
    }

    @Override
    public void onCellsChanged(Set<Cell> cells) {
        this.cells = cells;
        Log.d(TAG, "Cells: " + cells.size());
        if (running) startCalculate();
    }

    @Override
    protected synchronized Location update() {
        replay = true; // We need to replay to ensure apps think they are up-to-date.
        return super.update();
    }

    private boolean getAutoTimeZone() {
        try {
            Log.d(TAG, "Trying to get timeZone setting");
            return Settings.Global.getInt(mCr, Settings.Global.AUTO_TIME_ZONE) > 0;
        } catch (Settings.SettingNotFoundException snfe) {
            return true;
        }
    }

    private synchronized void startCalculate() {
        if (thread != null) return;
        if (lastRequestTime + RATE_LIMIT_MS > System.currentTimeMillis()) return;
        final Set<WiFi> wiFis = this.wiFis;
        final Set<Cell> cells = this.cells;
        if ((cells == null || cells.isEmpty()) && (wiFis == null || wiFis.size() < 2)) return;
        try {
            final String request = createRequest(cells, wiFis);
            if (request.equals(lastRequest)) {
                if (replay) {
                    Log.d(TAG, "No data changes, replaying location " + lastResponse);
                    lastResponse = LocationHelper.create(PROVIDER, lastResponse.getLatitude(), lastResponse.getLongitude(), lastResponse.getAccuracy());
                    report(lastResponse);
                }
                return;
            }
            replay = false;
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    HttpURLConnection conn = null;
                    Location response = null;
                    try {
                        conn = (HttpURLConnection) new URL(String.format(SERVICE_URL, API_KEY)).openConnection();
                        conn.setDoOutput(true);
                        conn.setDoInput(true);
                        Log.d(TAG, "request: " + request);
                        conn.getOutputStream().write(request.getBytes());
                        String r = new String(readStreamToEnd(conn.getInputStream()));
                        Log.d(TAG, "response: " + r);
                        JSONObject responseJson = new JSONObject(r);
                        double lat = responseJson.getJSONObject("location").getDouble("lat");
                        double lon = responseJson.getJSONObject("location").getDouble("lng");
                        double acc = responseJson.getDouble("accuracy");
                        response = LocationHelper.create(PROVIDER, lat, lon, (float) acc);
                        report(response);
                        if (getAutoTimeZone()) {
                            Log.d(TAG, "Looking for TZ");
                            IConverter iconv = Converter.getInstance(TimeZoneListStore.class, mContext);
                            TimeZone tz = iconv.getTimeZone(lat, lon);
                            Log.d(TAG, "Found TZ: " + tz.getID());
                            mAlarmManager.setTimeZone(tz.getID());
                        }
                    } catch (IOException | JSONException e) {
                        if (conn != null) {
                            InputStream is = conn.getErrorStream();
                            if (is != null) {
                                try {
                                    String error = new String(readStreamToEnd(is));
                                    Log.w(TAG, "Error: " + error);
                                } catch (Exception ignored) {
                                }
                            }
                        }
                        Log.w(TAG, e);
                    }

                    lastRequest = request;
                    lastResponse = response;
                    lastRequestTime = System.currentTimeMillis();
                    thread = null;
                }
            });
            thread.start();
        } catch (Exception e) {
            Log.w(TAG, e);
        }
    }

    private static byte[] readStreamToEnd(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (is != null) {
            byte[] buff = new byte[1024];
            while (true) {
                int nb = is.read(buff);
                if (nb < 0) {
                    break;
                }
                bos.write(buff, 0, nb);
            }
            is.close();
        }
        return bos.toByteArray();
    }

    /**
     * see https://mozilla-ichnaea.readthedocs.org/en/latest/cell.html
     */
    @SuppressWarnings("MagicNumber")
    private static int calculateAsu(Cell cell) {
        switch (cell.getType()) {
            case GSM:
                return Math.max(0, Math.min(31, (cell.getSignal() + 113) / 2));
            case UMTS:
                return Math.max(-5, Math.max(91, cell.getSignal() + 116));
            case LTE:
                return Math.max(0, Math.min(95, cell.getSignal() + 140));
            case CDMA:
                int signal = cell.getSignal();
                if (signal >= -75) {
                    return 16;
                }
                if (signal >= -82) {
                    return 8;
                }
                if (signal >= -90) {
                    return 4;
                }
                if (signal >= -95) {
                    return 2;
                }
                if (signal >= -100) {
                    return 1;
                }
                return 0;
        }
        return 0;
    }

    private static String getRadioType(Cell cell) {
        switch (cell.getType()) {
            case CDMA:
                return "cdma";
            case LTE:
                return "lte";
            case UMTS:
                return "wcdma";
            case GSM:
            default:
                return "gsm";
        }
    }

    private static String createRequest(Set<Cell> cells, Set<WiFi> wiFis) throws JSONException {
        JSONObject jsonObject = new JSONObject();
        JSONArray cellTowers = new JSONArray();

        if (cells != null) {
            Cell.CellType lastType = null;
            for (Cell cell : cells) {
                if (cell.getType() == Cell.CellType.CDMA) {
                    jsonObject.put("radioType", "cdma");
                } else if (lastType != null && lastType != cell.getType()) {
                    // We can't contribute if different cell types are mixed.
                    jsonObject.put("radioType", null);
                } else {
                    jsonObject.put("radioType", getRadioType(cell));
                }
                lastType = cell.getType();
                JSONObject cellTower = new JSONObject();
                cellTower.put("radioType", getRadioType(cell));
                cellTower.put("mobileCountryCode", cell.getMcc());
                cellTower.put("mobileNetworkCode", cell.getMnc());
                cellTower.put("locationAreaCode", cell.getLac());
                cellTower.put("cellId", cell.getCid());
                cellTower.put("signalStrength", cell.getSignal());
                if (cell.getPsc() != -1)
                    cellTower.put("psc", cell.getPsc());
                cellTower.put("asu", calculateAsu(cell));
                cellTowers.put(cellTower);
            }
        }
        JSONArray wifiAccessPoints = new JSONArray();
        if (wiFis != null) {
            for (WiFi wiFi : wiFis) {
                JSONObject wifiAccessPoint = new JSONObject();
                wifiAccessPoint.put("macAddress", wiFi.getBssid());
                //wifiAccessPoint.put("age", age);
                if (wiFi.getChannel() != -1) wifiAccessPoint.put("channel", wiFi.getChannel());
                if (wiFi.getFrequency() != -1)
                    wifiAccessPoint.put("frequency", wiFi.getFrequency());
                wifiAccessPoint.put("signalStrength", wiFi.getRssi());
                //wifiAccessPoint.put("signalToNoiseRatio", signalToNoiseRatio);
                wifiAccessPoints.put(wifiAccessPoint);
            }
        }
        jsonObject.put("cellTowers", cellTowers);
        jsonObject.put("wifiAccessPoints", wifiAccessPoints);
        jsonObject.put("fallbacks", new JSONObject().put("lacf", true).put("ipf", false));
        return jsonObject.toString();
    }
}
