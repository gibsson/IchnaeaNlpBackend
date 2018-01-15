package gapchenko.llttz;

import gapchenko.llttz.stores.*;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.TimeZone;

/**
 * @author artemgapchenko
 * Created on 18.04.14.
 */
public class Converter implements IConverter {
    private TimeZoneStore tzStore;
    private static Converter instance = null;
    private Context mContext = null;

    private Converter(Class clazz, Context ctx) {
        if (!TimeZoneStore.class.isAssignableFrom(clazz)) {
            throw new IllegalArgumentException("Illegal store provided: " + clazz.getName());
        }
        try {
            mContext = ctx;
            tzStore = (TimeZoneStore) clazz.newInstance();
            loadData();
        } catch (InstantiationException | IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    public static Converter getInstance(final Class clazz, Context ctx) {
        if (instance == null || !instance.getStoreClass().equals(clazz))
            instance = new Converter(clazz, ctx);
        return instance;
    }

    public Class getStoreClass() {
        return tzStore.getClass();
    }

    @Override
    public TimeZone getTimeZone(final double lat, final double lon) {
        return tzStore.nearestTimeZone(new Location(new double[]{lat, lon}));
    }

    private void loadData() {
        InputStream in = null;

        in = mContext.getResources().openRawResource(
                mContext.getResources().getIdentifier("timezones",
                    "raw", mContext.getPackageName()));

        BufferedReader br = new BufferedReader(new InputStreamReader(in));

        try {
            String line;
            String[] location;

            while ((line = br.readLine()) != null) {
                location = line.split(";");
                tzStore.insert(new Location(Double.valueOf(location[1]), Double.valueOf(location[2]), location[0]));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
