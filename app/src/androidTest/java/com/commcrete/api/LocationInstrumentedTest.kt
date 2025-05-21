package com.commcrete.bittell

import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocationManagerInstrumentedTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION
    )

    @Test
    fun testLocationManagerProvidesLocation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val providers = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER
        )

        var location: Location? = null
        for (provider in providers) {
            if (locationManager.isProviderEnabled(provider)) {
                val loc = locationManager.getLastKnownLocation(provider)
                if (loc != null) {
                    location = loc
                    break
                }
            }
        }

        if (location != null) {
            println("Location from ${location.provider}: ${location.latitude}, ${location.longitude}")
        } else {
            println("No location found from any provider.")
        }

        assertNotNull("LocationManager did not return a location", location)
    }
}