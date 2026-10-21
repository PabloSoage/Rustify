package com.varuna.rustify.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceNameTest {
    @Test
    fun theMakerIsCapitalisedAndPrefixed() {
        assertEquals("Samsung SM-X700", DeviceName.fromModel("samsung", "SM-X700"))
    }

    @Test
    fun aModelThatAlreadyNamesTheMakerIsNotDoubled() {
        assertEquals("Google Pixel 9", DeviceName.fromModel("Google", "Google Pixel 9"))
        assertEquals("motorola edge", DeviceName.fromModel("Motorola", "motorola edge"))
    }

    @Test
    fun missingPartsStillGiveAName() {
        assertEquals("Xiaomi", DeviceName.fromModel("xiaomi", ""))
        assertEquals("Android", DeviceName.fromModel(null, null))
    }
}
