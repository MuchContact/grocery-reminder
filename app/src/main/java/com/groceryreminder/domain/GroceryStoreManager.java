package com.groceryreminder.domain;

import android.app.Application;
import android.app.PendingIntent;
import android.content.ContentProviderOperation;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.groceryreminder.R;
import com.groceryreminder.data.ReminderContract;
import com.groceryreminder.injection.ForApplication;
import com.groceryreminder.services.GroceryStoreLocationListener;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import se.walkercrou.places.GooglePlacesInterface;
import se.walkercrou.places.Place;

public class GroceryStoreManager implements GroceryStoreManagerInterface {

    private static final String TAG = "StoreManager";
    private final LocationManager locationManager;
    private GooglePlacesInterface googlePlaces;
    private Application context;
    private LocationListener locationListener;
    private Location currentLocation;
    private long lastUpdateTime;

    @Inject
    public GroceryStoreManager(@ForApplication Application applicationContext, LocationManager locationManager, GooglePlacesInterface googlePlaces) {
        this.context = applicationContext;
        this.locationManager = locationManager;
        this.googlePlaces = googlePlaces;
    }

    @Override
    public void findStoresByLocation(Location location) {
        Log.d(TAG, "Location: " + location);

        SharedPreferences sharedPreferences = context.getSharedPreferences(context.getString(R.string.reminder_pref_key), Context.MODE_PRIVATE);
        long lastPollTime = sharedPreferences.getLong(GroceryReminderConstants.LAST_GOOGLE_PLACES_POLL_TIME, 0);

        if (System.currentTimeMillis() - lastPollTime > GroceryReminderConstants.MIN_LOCATION_UPDATE_TIME_MILLIS) {
            GooglePlacesNearbySearchTask googlePlacesNearbySearchTask = new GooglePlacesNearbySearchTask(googlePlaces, this);
            googlePlacesNearbySearchTask.execute(location);
            sharedPreferences.edit().putLong(GroceryReminderConstants.LAST_GOOGLE_PLACES_POLL_TIME, System.currentTimeMillis()).commit();
        }
    }

    @Override
    public List<Place> filterPlacesByDistance(Location location, List<Place> places, double distanceInMeters) {
        List<Place> filteredPlaces = new ArrayList<Place>();
        for (Place place : places) {
            float[] distanceArray = new float[1];
            Location.distanceBetween(location.getLatitude(), location.getLongitude(), place.getLatitude(), place.getLongitude(), distanceArray);
            if (distanceArray[0] <= (float) distanceInMeters) {
                filteredPlaces.add(place);
            }
        }
        return filteredPlaces;
    }

    @Override
    public void persistGroceryStores(List<Place> places) {
        List<ContentValues> contentValuesList = new ArrayList<ContentValues>();
        for (Place place : places) {
            Log.d(TAG, "Found places");
            ContentValues values = BuildLocationContentValues(place);
            contentValuesList.add(values);
        }

        ArrayList<ContentProviderOperation> operations = new ArrayList<ContentProviderOperation>();
        for (ContentValues contentValues : contentValuesList) {
            operations.add(ContentProviderOperation.newInsert(ReminderContract.Locations.CONTENT_URI)
                            .withValues(contentValues).build()
            );
        }

        applyBatchOperations(operations);

    }

    @Override
    public void deleteStoresByLocation(Location location) {
        Cursor cursor = context.getContentResolver().query(ReminderContract.Locations.CONTENT_URI, ReminderContract.Locations.PROJECT_ALL, null, null, ReminderContract.Locations.SORT_ORDER_DEFAULT);

        ArrayList<ContentProviderOperation> operations = new ArrayList<ContentProviderOperation>();
        while (cursor.moveToNext()) {
            float[] distanceArray = new float[1];
            double latitude = Double.parseDouble(cursor.getString((cursor.getColumnIndex(ReminderContract.Locations.LATITUDE))));
            double longitude = Double.parseDouble(cursor.getString((cursor.getColumnIndex(ReminderContract.Locations.LONGITUDE))));
            Location.distanceBetween(location.getLatitude(), location.getLongitude(), latitude, longitude, distanceArray);

            if (distanceArray[0] > (float) GroceryReminderConstants.LOCATION_SEARCH_RADIUS_METERS) {
                Uri deletionUri = ContentUris.withAppendedId(ReminderContract.Locations.CONTENT_URI, cursor.getInt(0));
                operations.add(ContentProviderOperation.newDelete(deletionUri).build());
            }
        }

        applyBatchOperations(operations);
    }

    @Override
    public void addProximityAlerts(List<Place> places) {
        int requestCode = 0;
        for (Place place : places) {
            Log.d(TAG, "Adding proximity alert");
            Intent proximityAlertIntent = new Intent(GroceryReminderConstants.ACTION_STORE_PROXIMITY_EVENT);
            proximityAlertIntent.putExtra(ReminderContract.Locations.NAME, place.getName());
            locationManager.addProximityAlert(place.getLatitude(), place.getLongitude(),
                    GroceryReminderConstants.LOCATION_GEOFENCE_RADIUS_METERS, GroceryReminderConstants.PROXIMITY_ALERT_EXPIRATION,
                    PendingIntent.getBroadcast(context, requestCode++, proximityAlertIntent,
                            PendingIntent.FLAG_CANCEL_CURRENT));
        }
    }

    @Override
    public void listenForLocationUpdates(boolean listenForGPSUpdates) {
        if (this.locationListener == null) {
            this.locationListener = createLocationListener();
            if (listenForGPSUpdates) {
                addLocationListenerForProvider(LocationManager.GPS_PROVIDER, locationListener, GroceryReminderConstants.MIN_LOCATION_UPDATE_TIME_MILLIS);
            }
            addLocationListenerForProvider(LocationManager.NETWORK_PROVIDER, locationListener, GroceryReminderConstants.NETWORK_MIN_UPDATE_TIME);
            addLocationListenerForProvider(LocationManager.PASSIVE_PROVIDER, locationListener, GroceryReminderConstants.PASSIVE_MIN_UPDATE_TIME);
        }
    }

    private void addLocationListenerForProvider(String provider, LocationListener locationListener, long minUpdateTime) {
        if (locationManager.isProviderEnabled(provider)) {
            Log.d(TAG, "Provider is enabled");
            locationManager.requestLocationUpdates(provider, minUpdateTime, (float) GroceryReminderConstants.LOCATION_SEARCH_RADIUS_METERS, locationListener);
        }
    }

    @Override
    public Location getCurrentLocation() {
        return currentLocation;
    }

    @Override
    public void removeGPSListener() {
        Log.d(TAG, "Removing GPS");
        locationManager.removeUpdates(locationListener);
        addLocationListenerForProvider(LocationManager.NETWORK_PROVIDER, locationListener, GroceryReminderConstants.NETWORK_MIN_UPDATE_TIME);
        addLocationListenerForProvider(LocationManager.PASSIVE_PROVIDER, locationListener, GroceryReminderConstants.PASSIVE_MIN_UPDATE_TIME);
    }

    private LocationListener createLocationListener() {
        return new GroceryStoreLocationListener(this);
    }

    private void applyBatchOperations(ArrayList<ContentProviderOperation> operations) {
        try {
            context.getContentResolver().applyBatch(ReminderContract.REMINDER_LOCATION_AUTHORITY, operations);
        } catch (RemoteException e) {
            e.printStackTrace();
        } catch (OperationApplicationException e) {
            e.printStackTrace();
        }
    }

    private ContentValues BuildLocationContentValues(Place place) {
        ContentValues values = new ContentValues();
        values.put(ReminderContract.Locations.NAME, place.getName());
        Log.d(TAG, "Place from service call: " + place.getName());
        values.put(ReminderContract.Locations.PLACES_ID, place.getPlaceId());
        values.put(ReminderContract.Locations.LATITUDE, place.getLatitude());
        values.put(ReminderContract.Locations.LONGITUDE, place.getLongitude());

        return values;
    }

    @Override
    public void handleLocationUpdated(Location location) {
        Log.d(TAG, "Hitting the handleLocationUpdated");
        if (this.currentLocation == null) {
            Log.d(TAG, "Current location is null");
            this.currentLocation = location;
            this.lastUpdateTime = SystemClock.currentThreadTimeMillis();
            updateStoreLocations(location);
        } else if (minimumUpdateTimeHasPassed(location)) {
            Log.d(TAG, "Minimum update time has passed");
            this.currentLocation = location;
            this.lastUpdateTime = SystemClock.currentThreadTimeMillis();
            updateStoreLocations(location);
        }
    }

    private boolean minimumUpdateTimeHasPassed(Location location) {
        return location.getTime() - lastUpdateTime >= GroceryReminderConstants.MIN_LOCATION_UPDATE_TIME_MILLIS;
    }

    private void updateStoreLocations(Location location) {
        deleteStoresByLocation(location);
        findStoresByLocation(location);
    }

    public void onStoreLocationsUpdated(Location location, List<Place> updatedPlaces) {
        List<Place> places = filterPlacesByDistance(location, updatedPlaces, GroceryReminderConstants.LOCATION_SEARCH_RADIUS_METERS);

        Log.d(TAG, "Places count: " + places.size());
        persistGroceryStores(places);
        addProximityAlerts(places);
    }

    @Override
    public boolean isBetterThanCurrentLocation(Location location) {
        if (!isAccurate(location)) {
            Log.d(TAG, "Location accuracy is not good enough");
            return false;
        }

        if (currentLocation != null) {
            return compareLocations(currentLocation, location);
        }

        return true;
    }

    @Override
    public boolean isAccurate(Location location) {
        return location.getAccuracy() <= GroceryReminderConstants.MAXIMUM_ACCURACY_IN_METERS;
    }

    private boolean compareLocations(Location currentLocation, Location updateLocation) {
        if (isSignificantlyNewerLocation(currentLocation, updateLocation)) {
            return true;
        } else if (isSignificantlyMoreAccurate(currentLocation, updateLocation)) {
            return true;
        }

        return false;
    }

    private boolean isSignificantlyMoreAccurate(Location currentLocation, Location updateLocation) {
        float accuracyRatio = updateLocation.getAccuracy() / currentLocation.getAccuracy();

        boolean isMoreAccurate = accuracyRatio <= SIGNIFICANT_LOCATION_ACCURACY_RATIO;
        Log.d(TAG, "New location accuracyRatio: " + accuracyRatio);
        Log.d(TAG, "New location is significantly more accurate: " + isMoreAccurate);
        Log.d(TAG, "Significant location accuracy is: " + SIGNIFICANT_LOCATION_ACCURACY_RATIO);

        return isMoreAccurate;
    }

    private boolean isSignificantlyNewerLocation(Location currentLocation, Location updateLocation) {
        long timeDelta = updateLocation.getTime() - currentLocation.getTime();

        boolean isNewerLocation = timeDelta >= SIGNIFICANT_LOCATION_TIME_DELTA;

        Log.d(TAG, "New location is more recent: " + isNewerLocation);
        Log.d(TAG, "Time delta since current location: " + timeDelta);
        Log.d(TAG, "Significant time delta is: " + SIGNIFICANT_LOCATION_TIME_DELTA);

        return isNewerLocation;
    }

    public void setLocation(Location location) {
        this.currentLocation = location;
    }

}
