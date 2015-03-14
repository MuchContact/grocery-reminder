package com.groceryreminder.testUtils;

import android.content.ContentValues;

import com.groceryreminder.data.ReminderContract;

public class LocationValuesBuilder {

    private static int uniqueness = 0;

    private ContentValues locationValues;

    public LocationValuesBuilder() {
        this.locationValues = new ContentValues();
    }

    public LocationValuesBuilder createDefaultLocationValues() {
        locationValues.put(ReminderContract.Locations.NAME, "location_name");
        locationValues.put(ReminderContract.Locations.PLACES_ID, "places_id" + uniqueness);
        locationValues.put(ReminderContract.Locations.LATITUDE, "latitude");
        locationValues.put(ReminderContract.Locations.LONGITUDE, "longitude");
        uniqueness += 1;

        return this;
    }

    public ContentValues build() {
        return locationValues;
    }

}
