package com.paperpocket.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Button;
import android.widget.EditText;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Checks the navigation defect reported by phone testers on a real Android window. */
@RunWith(AndroidJUnit4.class)
public class NavigationLayoutTest {
    @Test public void largeFontsOnNarrowPhoneKeepNavigationAndControlsReadable() throws Exception {
        checkDisplay(720, 1280, 320, 2.0f);
    }

    @Test public void tabletWithEnlargedFontsKeepsNavigationAndControlsReadable() throws Exception {
        checkDisplay(1200, 1600, 320, 1.3f);
    }

    /** Applies actual Android display settings, and restores every override even on failure. */
    private static void checkDisplay(int width, int height, int density, float fontScale) throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        String oldSize = override(shell(instrumentation, "wm size"), "Override size:");
        String oldDensity = override(shell(instrumentation, "wm density"), "Override density:");
        String oldFont = shell(instrumentation, "settings get system font_scale").trim();
        Activity activity = null;
        try {
            shell(instrumentation, "wm size " + width + "x" + height);
            shell(instrumentation, "wm density " + density);
            shell(instrumentation, "settings put system font_scale " + fontScale);
            // Resource updates are asynchronous. Launch only after the target context observes them.
            long deadline = SystemClock.uptimeMillis() + 10_000;
            while (Math.abs(instrumentation.getTargetContext().getResources().getConfiguration().fontScale - fontScale) > .02f
                    && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100);
            Intent intent = new Intent(instrumentation.getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity = instrumentation.startActivitySync(intent);
            final Activity tested = activity;
            for (String label : new String[]{"Home", "Pages", "My PDFs"}) {
                awaitSelection(instrumentation, tested, label);
                instrumentation.runOnMainSync(() -> {
                    assertEquals("Test must use the requested font scale", fontScale,
                            tested.getResources().getConfiguration().fontScale, .02f);
                    int actualWidth = tested.getResources().getConfiguration().screenWidthDp;
                    assertTrue("Test must exercise the requested screen width", Math.abs(actualWidth - width * 160 / density) <= 8);
                    assertNavigation(tested);
                    assertControlsFit(tested.getWindow().getDecorView());
                });
            }
        } finally {
            if (activity != null) {
                Activity finishing = activity;
                instrumentation.runOnMainSync(finishing::finish);
                instrumentation.waitForIdleSync();
            }
            shell(instrumentation, "wm size " + (oldSize == null ? "reset" : oldSize));
            shell(instrumentation, "wm density " + (oldDensity == null ? "reset" : oldDensity));
            if (oldFont.matches("[0-9]+(\\.[0-9]+)?")) shell(instrumentation, "settings put system font_scale " + oldFont);
            else shell(instrumentation, "settings delete system font_scale");
            instrumentation.waitForIdleSync();
        }
    }

    private static void assertNavigation(Activity activity) {
        for (String label : new String[]{"Home", "Pages", "My PDFs"}) {
            View tab = findTab(activity.getWindow().getDecorView(), label);
            assertNotNull("Accessible tab missing: " + label, tab);
            TextView caption = findCaption(tab, label);
            assertNotNull(caption);
            assertNotNull(caption.getLayout());
            assertEquals("Navigation label truncated: " + label, 0, caption.getLayout().getEllipsisCount(0));
            assertTrue("Navigation label clipped vertically: " + label,
                    caption.getLayout().getHeight() <= caption.getHeight() - caption.getCompoundPaddingTop() - caption.getCompoundPaddingBottom());
            View icon = ((ViewGroup) tab).getChildAt(0);
            assertTrue("Navigation icon and caption must stay aligned",
                    Math.abs((icon.getLeft() + icon.getRight()) / 2f - (caption.getLeft() + caption.getRight()) / 2f) <= 1);
            assertTrue(icon.getBottom() <= caption.getTop());
        }
    }

    private static void assertControlsFit(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof Button || view instanceof EditText) {
            TextView control = (TextView) view;
            assertNotNull("Control must be laid out", control.getLayout());
            assertTrue("Control text is clipped vertically: " + control.getText(),
                    control.getLayout().getHeight() <= control.getHeight() - control.getCompoundPaddingTop() - control.getCompoundPaddingBottom());
            for (int line = 0; line < control.getLayout().getLineCount(); line++) {
                assertEquals("Control text truncated: " + control.getText(), 0, control.getLayout().getEllipsisCount(line));
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) assertControlsFit(group.getChildAt(index));
        }
    }

    private static String shell(Instrumentation instrumentation, String command) throws Exception {
        try (ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(command);
             FileInputStream input = new FileInputStream(descriptor.getFileDescriptor());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int count;
            while ((count = input.read(buffer)) != -1) {
                assertTrue("Unexpected shell output", output.size() + count <= 16_384);
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String override(String output, String prefix) {
        for (String line : output.split("\\n")) if (line.startsWith(prefix)) {
            String value = line.substring(prefix.length()).trim();
            return value.matches("[0-9]+(x[0-9]+)?") ? value : null;
        }
        return null;
    }

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
            // Gradle removes the test app after instrumentation. Shell-owned
            // captures survive that cleanup and can be pulled by the CI runner.
            shell(instrumentation, "mkdir -p /sdcard/Download/PaperPocket-ui");
            shell(instrumentation, "screencap -p /sdcard/Download/PaperPocket-ui/" + name);
        } finally { bitmap.recycle(); }
    }
}
