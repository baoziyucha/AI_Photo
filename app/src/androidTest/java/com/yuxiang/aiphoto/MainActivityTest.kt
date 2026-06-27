package com.yuxiang.aiphoto

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val scenarioRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun showsCoreControls() {
        onView(withId(R.id.previewView)).check(matches(isDisplayed()))
        onView(withId(R.id.captureButton)).check(matches(isDisplayed()))
        onView(withId(R.id.aiAssistSwitch)).check(matches(isDisplayed()))
        onView(withId(R.id.settingsButton)).check(matches(isDisplayed()))
    }
}
