package com.paperpocket.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Checks the navigation defect reported by phone testers on a real Android window. */
@RunWith(AndroidJUnit4.class)
public class NavigationLayoutTest {
    @Test public void navigationLabelsAlignAndTabsOpen() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(instrumentation.getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Activity activity = instrumentation.startActivitySync(intent);
        try {
            instrumentation.waitForIdleSync();
            String[] labels = {"Home", "Pages", "My PDFs"};
            // Navigation must remain usable even while the first draft load is finishing.
            awaitSelection(instrumentation, activity, "Pages");
            awaitSelection(instrumentation, activity, "Home");
            instrumentation.runOnMainSync(() -> {
                int referenceWidth = -1;
                float density = activity.getResources().getDisplayMetrics().density;
                for (String label : labels) {
                    View tab = findTab(activity.getWindow().getDecorView(), label);
                    assertNotNull("Accessible tab missing: " + label, tab);
                    assertTrue("Tab must be clickable", tab.isClickable());
                    assertTrue("Tab is below the minimum touch size", tab.getHeight() >= 48 * density);
                    assertTrue(tab.getWidth() >= 48 * density);
                    if (referenceWidth >= 0) assertTrue("Tabs should have equal width", Math.abs(tab.getWidth() - referenceWidth) <= 1);
                    referenceWidth = tab.getWidth();
                    TextView caption = findCaption(tab, label);
                    assertNotNull("Navigation label missing", caption);
                    assertEquals(Gravity.CENTER_HORIZONTAL, caption.getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK);
                    View icon = ((ViewGroup) tab).getChildAt(0);
                    assertTrue("Icon must appear above its label", icon.getBottom() <= caption.getTop());
                    assertTrue("Icon and label centers should line up",
                            Math.abs((icon.getLeft() + icon.getRight()) / 2f - (caption.getLeft() + caption.getRight()) / 2f) <= 1);
                    assertEquals("Navigation label should not be truncated", 0, caption.getLayout().getEllipsisCount(0));
                }
            });
            capture(instrumentation, "01-home.png");
            awaitSelection(instrumentation, activity, "Pages");
            capture(instrumentation, "02-pages.png");
            awaitSelection(instrumentation, activity, "My PDFs");
            capture(instrumentation, "03-library.png");
        } finally {
            instrumentation.runOnMainSync(activity::finish);
            instrumentation.waitForIdleSync();
        }
    }

    private static void awaitSelection(Instrumentation instrumentation, Activity activity, String label) {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        AtomicBoolean selected = new AtomicBoolean();
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync(() -> {
                View tab = findTab(activity.getWindow().getDecorView(), label);
                assertNotNull("Missing navigation: " + label, tab);
                if (!tab.isSelected()) tab.performClick();
                View current = findTab(activity.getWindow().getDecorView(), label);
                selected.set(current != null && current.isSelected() && current.isLaidOut()
                        && current.getWidth() > 0 && current.getHeight() > 0);
            });
            instrumentation.waitForIdleSync();
            if (selected.get()) return;
            SystemClock.sleep(50);
        }
        fail("Navigation did not open: " + label);
    }

    private static View findTab(View view, String label) {
        if (view.isClickable() && label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = findTab(group.getChildAt(i), label);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static TextView findCaption(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView result = findCaption(group.getChildAt(i), label);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static void capture(Instrumentation instrumentation, String name) throws Exception {
        instrumentation.waitForIdleSync();
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("Native screenshot unavailable", bitmap);
        try {
            File folder = new File(instrumentation.getTargetContext().getFilesDir(), "ui-captures");
            assertTrue(folder.isDirectory() || folder.mkdirs());
            try (FileOutputStream stream = new FileOutputStream(new File(folder, name))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
            }
        } finally { bitmap.recycle(); }
    }
}
