/*
 * Copyright 2014 samuelcampos.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.samuelcampos.usbdrivedetector;

import lombok.extern.slf4j.Slf4j;
import net.samuelcampos.usbdrivedetector.detectors.AbstractStorageDeviceDetector;
import net.samuelcampos.usbdrivedetector.events.DeviceEventType;
import net.samuelcampos.usbdrivedetector.events.IUSBDriveListener;
import net.samuelcampos.usbdrivedetector.events.USBStorageEvent;
import net.samuelcampos.usbdrivedetector.unmounters.AbstractStorageDeviceUnmounter;

import java.io.Closeable;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * @author samuelcampos
 */
@Slf4j
public class USBDeviceDetectorManager implements Closeable {

    /**
     * The default polling interval is 5 seconds
     */
    private static final long DEFAULT_POLLING_INTERVAL_MILLIS = 5000;

    private final Set<USBStorageDevice> connectedDevices;
    private final List<IUSBDriveListener> listeners;

    private long currentPollingInterval;
    private final ScheduledExecutorService taskExecutor;
    private ScheduledFuture<?> listenerTaskFuture;

    public USBDeviceDetectorManager() {
        this(DEFAULT_POLLING_INTERVAL_MILLIS);
    }

    /**
     * Creates a new USBDeviceDetectorManager
     * <p>
     * The polling interval is used as the update frequency for any attached
     * listeners.
     * </p>
     * <p>
     * Polling doesn't happen until at least one listener is attached.
     * </p>
     *
     * @param pollingInterval the interval in milliseconds to poll for the USB
     *                        storage devices on the system.
     */
    public USBDeviceDetectorManager(final long pollingInterval) {
        listeners = new ArrayList<>();
        connectedDevices = new HashSet<>();

        currentPollingInterval = pollingInterval;

        taskExecutor = Executors.newSingleThreadScheduledExecutor();
    }

    /**
     * Sets the polling interval
     *
     * @param pollingInterval the interval in milliseconds to poll for the USB
     *                        storage devices on the system.
     */
    public synchronized void setPollingInterval(final long pollingInterval) {
        if (pollingInterval <= 0) {
            throw new IllegalArgumentException("'pollingInterval' must be greater than 0");
        }

        currentPollingInterval = pollingInterval;

        if (!listeners.isEmpty()) {
            stop();
            start();
        }
    }

    /**
     * Start polling to update listeners
     * <p>
     * This method only needs to be called if {@link #stop() stop()} has been
     * called after listeners have been added.
     * </p>
     */
    private synchronized void start() {
        if (listenerTaskFuture == null || listenerTaskFuture.isDone()) {
            listenerTaskFuture = taskExecutor.scheduleAtFixedRate(new ListenerTask(), 0, currentPollingInterval, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Forces the polling to stop, even if there are still listeners attached
     */
    private synchronized void stop() {
        if (listenerTaskFuture != null) {
            // If this task is currently running, it will not interrupt it
            listenerTaskFuture.cancel(false);
        }
    }

    /**
     * Adds an IUSBDriveListener.
     * <p>
     * The polling timer is automatically started as needed when a listener is
     * added.
     * </p>
     *
     * @param listener the listener to be updated with the attached drives
     * @return true if the listener was not in the list and was successfully
     * added
     */
    public synchronized boolean addDriveListener(final IUSBDriveListener listener) {
        if (listeners.contains(listener)) {
            return false;
        }

        listeners.add(listener);
        start();
        return true;
    }

    /**
     * Removes an IUSBDriveListener.
     * <p>
     * The polling timer is automatically stopped if this is the last listener
     * being removed.
     * </p>
     *
     * @param listener the listener to remove
     * @return true if the listener existed in the list and was successfully
     * removed
     */
    public synchronized boolean removeDriveListener(final IUSBDriveListener listener) {
        final boolean removed = listeners.remove(listener);
        if (listeners.isEmpty()) {
            stop();
        }

        return removed;
    }

    /**
     * Gets a list of currently attached USB storage devices.
     * <p>
     * This method has no effect on polling or listeners being updated
     * </p>
     *
     * @return list of attached USB storage devices.
     */
    public List<USBStorageDevice> getRemovableDevices() {
        return AbstractStorageDeviceDetector.getInstance().getStorageDevices();
    }

    public void unmountStorageDevice(USBStorageDevice usbStorageDevice) throws IOException {
        AbstractStorageDeviceUnmounter.getInstance().unmount(usbStorageDevice);
    }

    /**
     * Updates the internal state of this manager and sends
     *
     * @param currentConnectedDevices a list with the currently connected USB storage devices
     */
    private void updateConnectedDevices(final List<USBStorageDevice> currentConnectedDevices) {
        final List<USBStorageDevice> removedDevices = new ArrayList<>();

        synchronized (this) {
            final Iterator<USBStorageDevice> itConnectedDevices = connectedDevices.iterator();

            while (itConnectedDevices.hasNext()) {
                final USBStorageDevice device = itConnectedDevices.next();

                if (currentConnectedDevices.contains(device)) {
                    currentConnectedDevices.remove(device);
                } else {
                    removedDevices.add(device);

                    itConnectedDevices.remove();
                }
            }

            connectedDevices.addAll(currentConnectedDevices);
        }

        currentConnectedDevices.forEach(device ->
                sendEventToListeners(new USBStorageEvent(device, DeviceEventType.CONNECTED)));

        removedDevices.forEach(device ->
                sendEventToListeners(new USBStorageEvent(device, DeviceEventType.REMOVED)));
    }

    private void sendEventToListeners(final USBStorageEvent event) {
        /*
         Make this thread safe, so we deal with a copy of listeners so any
         listeners being added or removed don't cause a ConcurrentModificationException.
         Also allows listeners to remove themselves while processing the event
         */
        final List<IUSBDriveListener> listenersCopy;
        synchronized (listeners) {
            listenersCopy = new ArrayList<>(listeners);
        }

        for (IUSBDriveListener listener : listenersCopy) {
            try {
                listener.usbDriveEvent(event);
            } catch (Exception ex) {
                log.error("An IUSBDriveListener threw an exception", ex);
            }
        }
    }

    @Override
    public void close() throws IOException {
        taskExecutor.shutdown();

        try {
            taskExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.warn("Unable to wait for taskExecutor termination", e);
        }
    }

    private class ListenerTask implements Runnable {

        @Override
        public void run() {
            try {
                log.trace("Polling refresh task is running");

                List<USBStorageDevice> actualConnectedDevices = getRemovableDevices();

                updateConnectedDevices(actualConnectedDevices);
            } catch (Exception e) {
                log.error("Error while refreshing device list", e);
            }
        }
    }
}
