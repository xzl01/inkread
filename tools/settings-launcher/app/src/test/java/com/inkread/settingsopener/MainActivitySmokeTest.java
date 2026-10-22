package com.inkread.settingsopener;

import static org.junit.Assert.assertNotNull;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** 冒烟测试：确认「打开设置」在 Robolectric 环境下能正常启动、不崩溃。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivitySmokeTest {

    @Test
    public void launchesWithoutCrash() {
        assertNotNull(ApplicationProvider.getApplicationContext());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity.getWindow().getDecorView());
                assertNotNull(activity.findViewById(android.R.id.content));
            });
        }
    }
}
