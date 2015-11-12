package org.altbeacon.beacon.service.scanner;/* Created by ${user} on ${month}/${year}. */

import android.content.Context;

import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.Region;
import org.altbeacon.beacon.logging.LogManager;
import org.altbeacon.beacon.service.Callback;
import org.altbeacon.beacon.service.MonitoringData;
import org.altbeacon.beacon.service.RegionMonitoringState;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static android.content.Context.MODE_PRIVATE;

public class MonitoringManager { // TODO: 2015-11-12 Better name?
    private static MonitoringManager instance;
    private static final String TAG = MonitoringManager.class.getSimpleName();
    private static final String DEFAULT_SCAN_STATE_FILE_NAME = "beacon_monitoring_state";
    private final Map<Region, RegionMonitoringState> regionsStates
            = new HashMap<Region, RegionMonitoringState>();

    private Context context;

    private int monitoringRegionsCount = 0;
    private boolean statePreservationIsOn = true;

    public static MonitoringManager getInstanceForApplication(Context context) {
        if (instance == null) {
            synchronized (MonitoringManager.class) {
                if (instance == null) {
                    instance = new MonitoringManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    public MonitoringManager(Context context) {
        this.context = context;
        restoreMonitoringState();
    }

    public synchronized void addMonitoringRegion(Region region) {
        if (regionsStates.containsKey(region)) return;
        regionsStates.put(region, new RegionMonitoringState(new Callback(context.getPackageName())));
        monitoringRegionsCount++;
        saveMonitoringStateIfOn();
    }

    public synchronized void remove(Region region) {
        regionsStates.remove(region);
        monitoringRegionsCount--;
        saveMonitoringStateIfOn();
    }

    public synchronized int count() {
        return monitoringRegionsCount;
    }

    public synchronized Set<Region> regions() {
        return regionsStates.keySet();
    }

    public synchronized RegionMonitoringState stateOf(Region region) {
        return regionsStates.get(region);
    }

    public synchronized void updateStatesFindNewOutside() {
        Iterator<Region> monitoredRegionIterator = regions().iterator();
        boolean needsMonitoringStateSaving = false;
        while (monitoredRegionIterator.hasNext()) {
            Region region = monitoredRegionIterator.next();
            RegionMonitoringState state = stateOf(region);
            if (state.isNewlyOutside()) {
                needsMonitoringStateSaving = true;
                LogManager.d(TAG, "found a monitor that expired: %s", region);
                state.getCallback().call(context, "monitoringData", new MonitoringData(state.isInside(), region));
            }
        }
        if (needsMonitoringStateSaving) saveMonitoringStateIfOn();
    }

    public synchronized void updateStateOfRegionsMatchingTo(Beacon beacon) {
        List<Region> matchingRegions = regionsMatchingTo(beacon);
        boolean needsMonitoringStateSaving = false;
        for(Region region : matchingRegions) {
            RegionMonitoringState state = regionsStates.get(region);
            if (state != null && state.markInside()) {
                needsMonitoringStateSaving = true;
                state.getCallback().call(context, "monitoringData",
                        new MonitoringData(state.isInside(), region));
            }
        }
        if (needsMonitoringStateSaving) saveMonitoringStateIfOn();
    }

    private List<Region> regionsMatchingTo(Beacon beacon) {
        List<Region> matched = new ArrayList<Region>();
        for (Region region : regions()) {
            if (region.matchesBeacon(beacon)) {
                matched.add(region);
            } else {
                LogManager.d(TAG, "This region (%s) does not match beacon: %s", region, beacon);
            }
        }
        return matched;
    }

    private void saveMonitoringStateIfOn() {
        if(!statePreservationIsOn) return;
        LogManager.e(TAG, "saveMonitoringStateIfOn()" );
        FileOutputStream outputStream = null;
        ObjectOutputStream objectOutputStream = null;
        try {
            outputStream = context.openFileOutput(DEFAULT_SCAN_STATE_FILE_NAME, MODE_PRIVATE);
            objectOutputStream = new ObjectOutputStream(outputStream);
            objectOutputStream.writeObject(regionsStates);

        } catch (IOException e) {
            LogManager.e(TAG, "Error while saving monitored region states to file. %s ", e.getMessage());
        } finally {
            if (null != outputStream) {
                try {
                    outputStream.close();
                } catch (IOException ignored) {
                }
            }
            if (objectOutputStream != null) {
                try {
                    objectOutputStream.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void restoreMonitoringState() {
        FileInputStream inputStream = null;
        ObjectInputStream objectInputStream = null;
        try {
            inputStream = context.openFileInput(DEFAULT_SCAN_STATE_FILE_NAME);
            objectInputStream = new ObjectInputStream(inputStream);
            Map<Region, RegionMonitoringState> obj = (Map<Region, RegionMonitoringState>) objectInputStream.readObject();
            regionsStates.putAll(obj);

        } catch (IOException | ClassNotFoundException | ClassCastException e) {
            if (e instanceof InvalidClassException) {
                LogManager.d(TAG, "Serialized Monitoring State has wrong class. Just ignoring saved state..." );
            } else LogManager.e(TAG, "Deserialization exception, message: $s", e.getMessage());
        } finally {
            if (null != inputStream) {
                try {
                    inputStream.close();
                } catch (IOException ignored) {
                }
            }
            if (objectInputStream != null) {
                try {
                    objectInputStream.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public synchronized void stopStatePreservationOnManagerDestruction() {
        context.deleteFile(DEFAULT_SCAN_STATE_FILE_NAME);
        this.statePreservationIsOn = false;
    }
}
