package com.paperpocket.app;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** Runs the real review dialog and private multipage reader before committing a result. */
@RunWith(AndroidJUnit4.class)
public final class PdfCompressionUiTest {
    @Test public void viewingACompressedCandidateDoesNotSaveUntilTheUserChoosesSave() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        PdfStore store = new PdfStore(context);
        List<String> previousIds = new ArrayList<>();
        for(PdfRecord record:store.list())previousIds.add(record.id);
        File image = new File(context.getCacheDir(), "compression-ui-" + UUID.randomUUID() + ".png");
        File converted = null;
        MainActivity activity = null;
        PdfCompressionCandidate candidate = null;
        try {
            writeImage(image);
            PdfConverter.Options options = new PdfConverter.Options();
            options.profile=PdfConverter.Profile.HIGH;options.jpegQuality=98;
            converted=PdfConverter.convert(context,Collections.singletonList(new PageItem(image.getAbsolutePath())),options,null);
            PdfRecord original=store.save(converted,"Compression review test",1);
            byte[] originalBytes=Files.readAllBytes(original.file.toPath());
            activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            final MainActivity tested=activity;
            awaitMain(instrumentation,()->!flag(tested,"loading")&&!flag(tested,"busy"));
            Method reduce=MainActivity.class.getDeclaredMethod("reducePdf",PdfRecord.class,PdfSharePreset.class);
            reduce.setAccessible(true);
            instrumentation.runOnMainSync(()->{try{reduce.invoke(tested,original,PdfSharePreset.CHAT);}
                catch(Exception failure){throw new AssertionError(failure);}});
            awaitMain(instrumentation,()->field(tested,"pendingCompression")!=null&&!flag(tested,"busy"));
            candidate=(PdfCompressionCandidate)field(tested,"pendingCompression");
            assertTrue(candidate.isSmaller());
            assertEquals("Generating and reviewing a candidate must not create a library copy",previousIds.size()+1,store.list().size());
            AlertDialog review=(AlertDialog)field(tested,"compressionReview");
            assertNotNull(review);
            instrumentation.runOnMainSync(()->review.getButton(AlertDialog.BUTTON_NEUTRAL).performClick());
            awaitAccessible(instrumentation,"Unsaved preview");
            awaitAccessible(instrumentation,"1 / 1");
            awaitAccessible(instrumentation,"PDF page 1 of 1");
            assertEquals(previousIds.size()+1,store.list().size());
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            awaitAccessible(instrumentation,"Review compressed copy");
            instrumentation.runOnMainSync(()->review.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            awaitMain(instrumentation,()->!flag(tested,"busy")&&field(tested,"pendingCompression")==null);
            assertEquals(previousIds.size()+2,store.list().size());
            assertFalse(candidate.file.exists());
            assertArrayEquals("Preview and explicit save must preserve the original",originalBytes,Files.readAllBytes(original.file.toPath()));
        } finally {
            if(activity!=null){MainActivity closing=activity;ExecutorService work=(ExecutorService)field(closing,"work");
                instrumentation.runOnMainSync(closing::finish);instrumentation.waitForIdleSync();
                assertTrue("Compression work must stop before fixture cleanup",work.awaitTermination(15,TimeUnit.SECONDS));}
            if(candidate!=null)candidate.discard();
            for(PdfRecord record:store.list())if(!previousIds.contains(record.id))store.delete(record);
            if(converted!=null)converted.delete();image.delete();
        }
    }

    private interface Condition { boolean ready() throws Exception; }
    private static void awaitMain(Instrumentation instrumentation,Condition condition) {
        long deadline=SystemClock.uptimeMillis()+60_000;AtomicBoolean ready=new AtomicBoolean();
        while(SystemClock.uptimeMillis()<deadline){instrumentation.runOnMainSync(()->{try{ready.set(condition.ready());}
            catch(Exception failure){throw new AssertionError(failure);}});if(ready.get())return;SystemClock.sleep(25);}
        fail("Compression UI did not finish the expected state transition");
    }
    private static void awaitAccessible(Instrumentation instrumentation,String text) {
        long deadline=SystemClock.uptimeMillis()+20_000;
        while(SystemClock.uptimeMillis()<deadline){AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();
            if(root!=null){List<AccessibilityNodeInfo> found=root.findAccessibilityNodeInfosByText(text);boolean present=!found.isEmpty();
                for(AccessibilityNodeInfo node:found)node.recycle();root.recycle();if(present)return;}SystemClock.sleep(50);}
        fail("Expected visible compression reader control: "+text);
    }
    private static Object field(Object target,String name)throws Exception {
        Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private static boolean flag(Object target,String name)throws Exception {return(boolean)field(target,name);}
    private static void writeImage(File image)throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(1600,2200,Bitmap.Config.ARGB_8888);
        try{int[] row=new int[1600];for(int y=0;y<2200;y++){for(int x=0;x<1600;x++){
            int value=(x/3+1)*0x45d9f3b^(y/3+7)*0x119de1f3;value^=value>>>16;
            row[x]=Color.rgb(32+(value&255)%192,32+((value>>>8)&255)%192,32+((value>>>16)&255)%192);}
            bitmap.setPixels(row,0,row.length,0,y,row.length,1);}
            try(FileOutputStream output=new FileOutputStream(image)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}
        }finally{bitmap.recycle();}
    }
}
