package com.justdataplease.boringtravel.ui.onboarding

import com.justdataplease.boringtravel.google.DriveStep
import org.junit.Assert.assertEquals
import org.junit.Test

/** The one Google button: once signed in, a no to Drive is no error; a Drive failure says why. */
class GoogleConnectTest {
    @Test fun theDriveStepDecidesHowTheTapEnds() {
        assertEquals(GoogleConnect.Connected, GoogleConnect.afterDrive(DriveStep.Connected))
        assertEquals(GoogleConnect.NoDrive(null), GoogleConnect.afterDrive(DriveStep.Cancelled))
        assertEquals(GoogleConnect.NoDrive("No connection to Google."), GoogleConnect.afterDrive(DriveStep.Failed("No connection to Google.")))
    }
}
