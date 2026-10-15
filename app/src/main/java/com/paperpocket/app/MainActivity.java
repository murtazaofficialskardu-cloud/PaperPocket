package com.paperpocket.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.content.pm.PackageManager;
import android.widget.ProgressBar;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.util.AtomicFile;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner;
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;

/** Native, local-first document workspace. All image/PDF work happens off the UI thread. */
public class MainActivity extends Activity {
    private static final int IMPORT = 100, SCAN = 101, CAMERA = 102, CROP = 103, EXPORT = 104, TEXT_EXPORT = 105;
    private static final int PAGE_LIMIT = 100, NOTIFICATION_PERMISSION = 200;
    private static final int GREEN = Color.rgb(23,77,57), BG = Color.rgb(247,248,244);
    private static final int INK = Color.rgb(32,43,36), MUTED = Color.rgb(102,116,106);
    private static final int LINE = Color.rgb(222,229,221), PALE = Color.rgb(230,238,228);
    private static final Object DRAFT_LOCK = BackgroundPdfJobs.DRAFT_LOCK;
    private final ArrayList<PageItem> pages = new ArrayList<>();
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final ExecutorService previews = Executors.newSingleThreadExecutor();
    private List<PdfRecord> documents = new ArrayList<>();
    private List<TrashRecord> trashed = new ArrayList<>();
    private List<FolderRecord> folders = new ArrayList<>();
    private String libraryFilter = "";
    private LinearLayout backgroundBanner;
    private String bannerJobId;
    private boolean bannerActive;
    private ProgressBar jobProgress;
    private TextView jobTitle, jobCaption;
    private Button jobCancel;
    private BackgroundPdfJobs.Status backgroundStatus;
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService statusReader = Executors.newSingleThreadExecutor();
    private boolean resumed, statusReadPending;
    private int statusGeneration;
    private String clearedJobId;
    private final Runnable statusPoll = new Runnable() {
        @Override public void run() { if (resumed && !destroyed) pollBackgroundStatus(); }
    };
    private PdfStore store;
    private LinearLayout root, body, bottom, libraryList;
    private TextView libraryCount;
    private String screen = "home", query = "";
    private boolean alphabetical, busy, loading = true;
    private volatile boolean destroyed;
    private int libraryGeneration;
    private volatile int previewGeneration;
    private final ArrayList<PendingResult> pendingResults = new ArrayList<>();
    private ProgressDialog progress;
    private WorkSession activeTask;
    private SharedPreferences preferences;
    private static final class WorkSession {
        final Object lock=new Object();
        volatile boolean canceled,committing,started,completed;
        volatile Future<?> future;
        Runnable success;
        Runnable completionCleanup;
    }
    private interface TaskBody { void run(WorkSession task) throws Exception; }
    private static final class PendingResult {
        final int request, result; final Intent data;
        PendingResult(int request, int result, Intent data) { this.request=request; this.result=result; this.data=data; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(Build.VERSION.SDK_INT>=33){
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::navigateBack);
        }
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        preferences = getSharedPreferences("workspace", MODE_PRIVATE);
        store = new PdfStore(this);
        if (state != null) {
            screen = state.getString("screen", "home");
            query = state.getString("query", "");
            alphabetical = state.getBoolean("alphabetical");
            libraryFilter = state.getString("library_filter", "");
            ArrayList<Bundle> pending = state.getParcelableArrayList("pending_results");
            if(pending!=null) for(Bundle item:pending) pendingResults.add(new PendingResult(item.getInt("request"),item.getInt("result"),item.getParcelable("data")));
        }
        if ("com.paperpocket.app.SHOW_LIBRARY".equals(getIntent().getAction())) {screen="library";query="";libraryFilter="";}
        render();
        work.execute(() -> {
            String failure = null;
            ArrayList<PageItem> restored=new ArrayList<>();
            BackgroundPdfJobs.Status restoredStatus=null;
            try { restored=loadDraft(); } catch (Exception e) { failure = "Your saved draft could not be restored. Your PDFs are still safe."; }
            try { restoredStatus=BackgroundPdfJobs.read(this); } catch(Exception e) { if(failure==null)failure="Your conversion status could not be read. Your draft and saved PDFs are kept."; }
            final String message = failure;
            final ArrayList<PageItem> restoredPages=restored;
            final BackgroundPdfJobs.Status savedStatus=restoredStatus;
            runOnUiThread(() -> {
                if (isDestroyed()) return; pages.clear();pages.addAll(restoredPages);backgroundStatus=savedStatus;clearMatchingCompletedDraft();loading = false; render();
                if (message != null) showError(message); refreshDocuments();
                ArrayList<PendingResult> ready = new ArrayList<>(pendingResults); pendingResults.clear();
                for (PendingResult result : ready) handleActivityResult(result.request,result.result,result.data);
                if(resumed){statusHandler.removeCallbacks(statusPoll);statusHandler.post(statusPoll);}
            });
        });
    }

    @Override protected void onResume() {
        super.onResume(); resumed=true; statusHandler.removeCallbacks(statusPoll); statusHandler.post(statusPoll);
        if (store != null && !loading) refreshDocuments();
    }
    @Override protected void onPause() { resumed=false;statusHandler.removeCallbacks(statusPoll);super.onPause(); }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);setIntent(intent);
        if("com.paperpocket.app.SHOW_LIBRARY".equals(intent.getAction())){screen="library";query="";libraryFilter="";render();if(!loading)refreshDocuments();}
    }
    @Override public void onConfigurationChanged(Configuration configuration) { super.onConfigurationChanged(configuration); render(); }
    @Override public void onSaveInstanceState(Bundle state) {
        state.putString("screen", screen); state.putString("query", query); state.putBoolean("alphabetical", alphabetical);state.putString("library_filter",libraryFilter);
        ArrayList<Bundle> pending=new ArrayList<>();
        for(PendingResult result:pendingResults){Bundle item=new Bundle();item.putInt("request",result.request);item.putInt("result",result.result);item.putParcelable("data",result.data);pending.add(item);}
        state.putParcelableArrayList("pending_results",pending);
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        destroyed=true;resumed=false;statusHandler.removeCallbacksAndMessages(null);statusReader.shutdownNow();
        WorkSession task=activeTask;
        boolean queued=false;
        if(task!=null){synchronized(task.lock){if(!task.committing){task.canceled=true;queued=!task.started;if(task.future!=null)task.future.cancel(true);}}}
        if(queued)completeTask(task,null);
        if (progress != null) progress.dismiss();
        if(task!=null&&task.committing)work.shutdown();else work.shutdownNow();previews.shutdownNow();
        super.onDestroy();
    }
    // API 33+ uses the native OnBackInvokedCallback registered in onCreate.
    // This override preserves hardware/legacy back behavior on API 26–32.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        navigateBack();
    }
    private void navigateBack() {
        if (busy) return;
        if(screen.equals("trash")){screen="library";render();}
        else if (!screen.equals("home")) { screen = "home"; render(); } else finish();
    }

    private int dp(float n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setFontFeatureSettings("kern"); t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setIncludeFontPadding(false); return t;
    }
    private void gap(LinearLayout parent, int height) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private LinearLayout card() { LinearLayout c = column(); c.setPadding(dp(20),dp(20),dp(20),dp(20)); c.setBackground(shape(Color.WHITE,20)); return c; }
    private Button button(String title, boolean primary, View.OnClickListener action) {
        Button b = new Button(this); b.setAllCaps(false); b.setText(title); b.setTextSize(15); b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(primary ? Color.WHITE : GREEN); b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48));
        b.setPadding(dp(16),dp(10),dp(16),dp(10)); b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), shape(primary ? GREEN : PALE,12),null));
        b.setOnClickListener(action); return b;
    }
    private void fullButton(LinearLayout parent, String title, boolean primary, View.OnClickListener action) {
        Button control=button(title,primary,action);control.setMinHeight(dp(52));
        parent.addView(control,new LinearLayout.LayoutParams(-1,-2));
    }
    private void render() {
        if (isDestroyed()) return;
        ++previewGeneration; libraryList=null; libraryCount=null;
        root = column(); root.setBackgroundColor(BG); setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)-> {
            Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom); return insets;
        });
        ViewCompat.requestApplyInsets(root);
        LinearLayout header = row(); header.setPadding(dp(22),dp(16),dp(22),dp(16));
        IconView brand = new IconView("leaf",GREEN); header.addView(brand,new LinearLayout.LayoutParams(dp(32),dp(32)));
        TextView name = text("PaperPocket",21,INK,true);name.setPadding(dp(10),0,0,0);
        name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(header);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        body = column(); body.setPadding(dp(22),dp(6),dp(22),dp(24)); scroll.addView(body);
        bannerJobId=null;jobProgress=null;jobTitle=null;jobCaption=null;jobCancel=null;backgroundBanner=column();backgroundBanner.setPadding(0,0,0,dp(12));body.addView(backgroundBanner);renderBackgroundBanner();
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if (loading) { gap(body,50); body.addView(text("Getting your workspace ready…",17,MUTED,false)); }
        else if (screen.equals("trash")) renderTrash();else if (screen.equals("library")) renderLibrary(); else if (screen.equals("draft")) renderDraft(); else renderHome();
        bottom = row(); bottom.setBaselineAligned(false); bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(dp(12),dp(8),dp(12),dp(8)); bottom.setBackgroundColor(Color.WHITE);
        nav("home","Home","home"); nav("draft","Pages","image"); nav("library","My PDFs","library");
        root.addView(bottom);
    }
    private void nav(String destination,String label,String icon) {
        LinearLayout item = column(); item.setGravity(Gravity.CENTER); item.setBaselineAligned(false);
        item.setPadding(dp(4),dp(8),dp(4),dp(8));
        boolean selected = screen.equals(destination)||(destination.equals("library")&&screen.equals("trash"));
        item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18000000),shape(selected?PALE:Color.WHITE,14),null));
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(dp(24),dp(24));
        iconSize.gravity=Gravity.CENTER_HORIZONTAL;
        item.addView(new IconView(icon,selected?GREEN:MUTED),iconSize);
        gap(item,6);
        TextView caption=text(label,12,selected?GREEN:MUTED,selected);
        caption.setGravity(Gravity.CENTER);caption.setSingleLine(true);caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int lineHeight=caption.getPaint().getFontMetricsInt(null);
        caption.setMinHeight(lineHeight);
        item.addView(caption,new LinearLayout.LayoutParams(-1,-2));
        item.setMinimumHeight(Math.max(dp(64),dp(46)+lineHeight));
        item.setSelected(selected);item.setFocusable(true);item.setContentDescription(label);
        item.setOnClickListener(v -> { if(loading||busy)return; screen=destination; render(); });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,-2,1); p.setMargins(dp(3),0,dp(3),0); bottom.addView(item,p);
    }
    private void renderHome() {
        gap(body,14); body.addView(text("Create a PDF",30,INK,true)); gap(body,12);
        TextView intro=text("Import photos or scan a document, then save or share your PDF.",16,MUTED,false); intro.setLineSpacing(dp(4),1); body.addView(intro); gap(body,24);
        LinearLayout actions=row();
        actionCard(actions,"image","Import photos","From your gallery",v->importImages());
        actionCard(actions,"scan","Scan","Use your camera",v->startScanner());
        body.addView(actions); gap(body,22);
        if (!pages.isEmpty()) {
            LinearLayout draft=card(); LinearLayout heading=row(); heading.addView(text("Pick up where you left off",17,INK,true),new LinearLayout.LayoutParams(0,-2,1));
            heading.addView(text(String.valueOf(pages.size()),18,GREEN,true)); draft.addView(heading); gap(draft,8);
            draft.addView(text(pages.size()+" page"+(pages.size()==1?"":"s")+" ready",14,MUTED,false)); gap(draft,16);
            fullButton(draft,"Continue",true,v->{screen="draft";render();}); body.addView(draft); gap(body,24);
        } else {
            LinearLayout note=card(); note.setBackground(shape(PALE,20)); note.addView(text("Ready in a few steps",18,GREEN,true)); gap(note,10);
            TextView t=text("Add pages, adjust them if needed, and create your PDF.",14,GREEN,false); t.setLineSpacing(dp(3),1); note.addView(t); body.addView(note); gap(body,24);
        }
        LinearLayout recent=row(); recent.addView(text("Recent documents",19,INK,true),new LinearLayout.LayoutParams(0,-2,1));
        TextView all=text("View all →",13,GREEN,true); all.setPadding(dp(8),dp(14),0,dp(14)); all.setOnClickListener(v->{screen="library";render();}); recent.addView(all); body.addView(recent);
        if(documents.isEmpty()) { gap(body,12); body.addView(text("Your first PDF belongs here. Import or scan to get started.",14,MUTED,false)); }
        else for(int i=0;i<Math.min(3,documents.size());i++){gap(body,10);body.addView(documentCard(documents.get(i)));}
        gap(body,22); body.addView(text("Made for your documents. Stored on your device.",11,MUTED,false));
        TextView privacy=text("Privacy & support",13,GREEN,true);privacy.setMinHeight(dp(48));privacy.setGravity(Gravity.CENTER_VERTICAL);
        privacy.setContentDescription("Read privacy policy and support contact");privacy.setOnClickListener(v->showPrivacy());body.addView(privacy);
    }
    private void showPrivacy() {
        String policy;
        try(InputStream input=getAssets().open("privacy_policy.txt");ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1){if(bytes.size()+count>128*1024)throw new java.io.IOException("Privacy policy is too large");bytes.write(buffer,0,count);}
            policy=bytes.toString("UTF-8");
        }catch(Exception e){showError("The privacy policy could not be opened. Please reinstall the latest app version.");return;}
        ScrollView scroll=new ScrollView(this);TextView content=text(policy,14,INK,false);content.setPadding(dp(24),dp(16),dp(24),dp(20));content.setLineSpacing(dp(4),1);
        content.setTextIsSelectable(true);android.text.util.Linkify.addLinks(content,android.text.util.Linkify.WEB_URLS|android.text.util.Linkify.EMAIL_ADDRESSES);content.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        scroll.addView(content);new AlertDialog.Builder(this).setTitle("Privacy & support").setView(scroll).setPositiveButton("Close",null)
                .setNeutralButton("Contact support",(dialog,which)->{
                    try{startActivity(new Intent(Intent.ACTION_SENDTO,Uri.parse("mailto:murtazaofficialskardu@gmail.com")).putExtra(Intent.EXTRA_SUBJECT,"PaperPocket support"));}
                    catch(Exception e){showError("No email app is available. You can contact murtazaofficialskardu@gmail.com.");}
                }).show();
    }
    private void actionCard(LinearLayout parent,String icon,String title,String subtitle,View.OnClickListener listener) {
        LinearLayout c=card(); c.setPadding(dp(16),dp(18),dp(12),dp(18));
        FrameLayout circle=new FrameLayout(this); circle.setBackground(shape(icon.equals("scan")?0xFFF6EDD9:PALE,14));
        IconView i=new IconView(icon,GREEN); FrameLayout.LayoutParams ip=new FrameLayout.LayoutParams(dp(28),dp(28),Gravity.CENTER); circle.addView(i,ip);
        c.addView(circle,new LinearLayout.LayoutParams(dp(48),dp(48))); gap(c,18); c.addView(text(title,16,INK,true)); gap(c,7); c.addView(text(subtitle,12,MUTED,false));
        c.setOnClickListener(listener); c.setContentDescription(title+". "+subtitle); c.setFocusable(true);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1); p.setMargins(icon.equals("scan")?dp(6):0,0,icon.equals("scan")?0:dp(6),0); parent.addView(c,p);
    }
    private void renderDraft() {
        gap(body,10); body.addView(text("Pages",30,INK,true)); gap(body,8); body.addView(text("Arrange your pages, then create a PDF.",15,MUTED,false)); gap(body,20);
        if(draftLocked()){
            LinearLayout locked=card();locked.addView(text("Your PDF is being created",20,INK,true));gap(locked,10);
            locked.addView(text("These pages are kept unchanged until conversion finishes. You can leave this screen or minimize the app.",15,MUTED,false));
            gap(locked,16);fullButton(locked,"View My PDFs",false,v->{screen="library";render();});body.addView(locked);return;
        }
        LinearLayout add=row(); Button importButton=button("Add photos",false,v->importImages()); Button scanButton=button("Scan",false,v->startScanner());
        LinearLayout.LayoutParams a=new LinearLayout.LayoutParams(0,-2,1);a.setMargins(0,0,dp(6),0); add.addView(importButton,a);
        LinearLayout.LayoutParams b=new LinearLayout.LayoutParams(0,-2,1);b.setMargins(dp(6),0,0,0);add.addView(scanButton,b);body.addView(add);gap(body,20);
        if(pages.isEmpty()) {
            LinearLayout empty=card();gap(empty,15); IconView icon=new IconView("image",GREEN);empty.addView(icon,new LinearLayout.LayoutParams(dp(48),dp(48)));gap(empty,20);
            empty.addView(text("Start with a photo",22,INK,true));gap(empty,10);empty.addView(text("Choose images from your device, or scan a document. You can crop, rotate and arrange each page before creating your PDF.",15,MUTED,false));gap(empty,15);body.addView(empty);return;
        }
        LinearLayout count=row();count.addView(text(pages.size()+" page"+(pages.size()==1?"":"s"),15,INK,true),new LinearLayout.LayoutParams(0,-2,1));
        TextView clear=text("Clear draft",13,MUTED,false);clear.setPadding(dp(8),dp(14),0,dp(14));clear.setOnClickListener(v->confirmClear());count.addView(clear);body.addView(count);
        for(int position=0;position<pages.size();position++) { final PageItem page=pages.get(position); final int index=position;
            LinearLayout c=card();c.setPadding(dp(12),dp(12),dp(12),dp(12)); LinearLayout top=row();
            ImageView preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setBackground(shape(BG,8));preview.setContentDescription("Preview of page "+(position+1));
            top.addView(preview,new LinearLayout.LayoutParams(dp(76),dp(98)));loadPreview(page,preview);
            LinearLayout info=column();info.setPadding(dp(14),0,0,0);info.addView(text("Page "+(position+1),17,INK,true));gap(info,7);info.addView(text(filterLabel(page.filter),13,MUTED,false));gap(info,12);
            LinearLayout move=row();Button up=button("↑",false,v->movePage(index,-1));up.setContentDescription("Move page "+(index+1)+" earlier");up.setEnabled(index>0);up.setAlpha(index>0?1:.35f);
            Button down=button("↓",false,v->movePage(index,1));down.setContentDescription("Move page "+(index+1)+" later");down.setEnabled(index<pages.size()-1);down.setAlpha(index<pages.size()-1?1:.35f);
            Button remove=button("×",false,v->removePage(page));remove.setContentDescription("Remove page "+(index+1));
            up.setPadding(dp(6),dp(10),dp(6),dp(10));down.setPadding(dp(6),dp(10),dp(6),dp(10));remove.setPadding(dp(6),dp(10),dp(6),dp(10));
            move.addView(up,new LinearLayout.LayoutParams(dp(50),-2));LinearLayout.LayoutParams small=new LinearLayout.LayoutParams(dp(50),-2);small.setMargins(dp(6),0,0,0);move.addView(down,small);
            LinearLayout.LayoutParams small2=new LinearLayout.LayoutParams(dp(50),-2);small2.setMargins(dp(6),0,0,0);move.addView(remove,small2);info.addView(move);top.addView(info,new LinearLayout.LayoutParams(0,-2,1));c.addView(top);gap(c,12);
            LinearLayout tools=row();miniTool(tools,"Crop",v->crop(page));miniTool(tools,"Rotate",v->{if(draftLocked())return;int before=page.rotation;page.rotation=(page.rotation+90)%360;if(!persistDraft())page.rotation=before;render();});miniTool(tools,"Filter",v->chooseFilter(page));c.addView(tools);body.addView(c);gap(body,12);
        }
        gap(body,8);fullButton(body,"Create PDF",true,v->conversionOptions());gap(body,10);
        fullButton(body,"Create ID-card PDF",false,v->idCardOptions());
    }
    private void miniTool(LinearLayout tools,String label,View.OnClickListener action) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(dp(3),0,dp(3),0);tools.addView(button(label,false,action),p); }
    private String filterLabel(PageItem.Filter filter) { return filter==PageItem.Filter.ENHANCED?"Enhanced clarity":filter==PageItem.Filter.BLACK_WHITE?"Black & white":"Original color"; }
    private void movePage(int index,int offset) { if(draftLocked()||index<0||index>=pages.size()||index+offset<0||index+offset>=pages.size())return;Collections.swap(pages,index,index+offset);if(!persistDraft())Collections.swap(pages,index,index+offset);render(); }
    private void removePage(PageItem page) { if(draftLocked())return;int index=pages.indexOf(page);if(index<0)return;pages.remove(index);if(persistDraft())deleteDraftImage(page.path);else pages.add(index,page);render(); }
    private void chooseFilter(PageItem page) { if(draftLocked())return;String[] labels={"Original color","Enhanced clarity","Black & white"};new AlertDialog.Builder(this).setTitle("Page filter").setSingleChoiceItems(labels,page.filter.ordinal(),(d,which)->{if(draftLocked()){d.dismiss();return;}PageItem.Filter before=page.filter;page.filter=PageItem.Filter.values()[which];if(!persistDraft())page.filter=before;render();d.dismiss();}).setNegativeButton("Cancel",null).show(); }
    private void confirmClear() { if(draftLocked())return;new AlertDialog.Builder(this).setTitle("Clear this draft?").setMessage("The images in this draft will be removed. Your generated PDFs stay in My PDFs.").setNegativeButton("Keep draft",null).setPositiveButton("Clear",(d,w)->clearDraft()).show(); }
    private void clearDraft() { if(draftLocked())return;ArrayList<PageItem> old=new ArrayList<>(pages);pages.clear();if(persistDraft()){for(PageItem page:old)deleteDraftImage(page.path);}else pages.addAll(old);render(); }

    private void renderLibrary() {
        gap(body,10);body.addView(text("My PDFs",30,INK,true));gap(body,8);libraryCount=text("",15,MUTED,false);updateLibraryCount();body.addView(libraryCount);gap(body,16);
        LinearLayout tools=row();miniTool(tools,"Merge PDFs",v->chooseMerge());miniTool(tools,"Trash",v->{screen="trash";render();refreshDocuments();});body.addView(tools);gap(body,12);
        ArrayList<String> filterLabels=new ArrayList<>();filterLabels.add("All PDFs");filterLabels.add("Favorites");
        ArrayList<String> filterIds=new ArrayList<>();filterIds.add("");filterIds.add("*");for(FolderRecord folder:folders){filterLabels.add(folder.name);filterIds.add(folder.id);}
        int selected=filterIds.indexOf(libraryFilter);if(selected<0){libraryFilter="";selected=0;}
        Spinner filter=spinner(filterLabels.toArray(new String[0]));filter.setContentDescription("Filter PDFs by folder or Favorites");filter.setSelection(selected);
        body.addView(filter,new LinearLayout.LayoutParams(-1,-2));
        filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> parent,View view,int position,long id){libraryFilter=filterIds.get(position);renderLibraryRows();}public void onNothingSelected(AdapterView<?> parent){}});
        Button manageFolders=button("Manage folders",false,v->manageFolders());body.addView(manageFolders,new LinearLayout.LayoutParams(-1,-2));gap(body,12);
        EditText search=new EditText(this);search.setSingleLine(true);search.setTextSize(15);search.setTextColor(INK);search.setHintTextColor(MUTED);search.setHint("Search your documents");search.setPadding(dp(16),dp(12),dp(16),dp(12));search.setBackground(shape(Color.WHITE,14));search.setText(query);
        search.setMinHeight(dp(52));body.addView(search,new LinearLayout.LayoutParams(-1,-2));gap(body,10);LinearLayout sort=row();sort.addView(text("YOUR DOCUMENTS",11,MUTED,true),new LinearLayout.LayoutParams(0,-2,1));
        TextView ordering=text(alphabetical?"Name A–Z ↓":"Newest first ↓",13,GREEN,true);ordering.setPadding(dp(8),dp(14),0,dp(14));ordering.setOnClickListener(v->{alphabetical=!alphabetical;ordering.setText(alphabetical?"Name A–Z ↓":"Newest first ↓");renderLibraryRows();});sort.addView(ordering);body.addView(sort);
        libraryList=column();body.addView(libraryList);renderLibraryRows();search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){query=s.toString();renderLibraryRows();}public void afterTextChanged(Editable e){}});
    }
    private void renderLibraryRows() {
        updateLibraryCount();
        if(libraryList==null)return;libraryList.removeAllViews();ArrayList<PdfRecord> visible=new ArrayList<>();for(PdfRecord r:documents)if(matchesLibraryFilter(r)&&r.name.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))visible.add(r);
        if(alphabetical)visible.sort(Comparator.comparing(r->r.name.toLowerCase(Locale.ROOT)));
        if(visible.isEmpty()){gap(libraryList,24);LinearLayout empty=card();empty.addView(text(documents.isEmpty()?"A home for your PDFs":"No matching documents",21,INK,true));gap(empty,10);empty.addView(text(documents.isEmpty()?"Create your first PDF from images or a scan. It will be saved here automatically.":"Try another search or choose All PDFs.",15,MUTED,false));libraryList.addView(empty);}
        else for(PdfRecord r:visible){libraryList.addView(documentCard(r));gap(libraryList,10);}
    }
    private boolean matchesLibraryFilter(PdfRecord record){return libraryFilter.isEmpty()||("*".equals(libraryFilter)?record.favorite:libraryFilter.equals(record.folderId));}
    private void updateLibraryCount() {if(libraryCount!=null){int count=0;for(PdfRecord record:documents)if(matchesLibraryFilter(record))count++;libraryCount.setText(count+" document"+(count==1?"":"s")+" · stored on this device");}}
    private LinearLayout documentCard(PdfRecord record) {
        LinearLayout c=row();c.setPadding(dp(14),dp(16),dp(10),dp(16));c.setBackground(shape(Color.WHITE,16));FrameLayout tile=new FrameLayout(this);tile.setBackground(shape(PALE,12));tile.addView(new IconView("pdf",GREEN),new FrameLayout.LayoutParams(dp(28),dp(30),Gravity.CENTER));c.addView(tile,new LinearLayout.LayoutParams(dp(48),dp(56)));
        LinearLayout label=column();label.setPadding(dp(14),0,dp(6),0);TextView title=text((record.favorite?"★ ":"")+record.name,16,INK,true);title.setMaxLines(2);label.addView(title);gap(label,7);
        String details=record.pageCount+" page"+(record.pageCount==1?"":"s")+" · "+size(record.sizeBytes)+"\n"+DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(record.createdAt));
        if(record.folderId!=null)for(FolderRecord folder:folders)if(folder.id.equals(record.folderId)){details+="\n"+folder.name;break;}
        TextView metadata=text(details,12,MUTED,false);metadata.setLineSpacing(dp(3),1);label.addView(metadata);c.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        TextView menu=text("⋮",28,MUTED,true);menu.setGravity(Gravity.CENTER);menu.setContentDescription("Actions for "+record.name);menu.setOnClickListener(v->documentActions(record));c.addView(menu,new LinearLayout.LayoutParams(dp(48),dp(56)));
        c.setOnClickListener(v->openPdf(record));c.setContentDescription("Open "+record.name+", "+record.pageCount+" pages");return c;
    }
    private String size(long bytes) { return bytes<1024*1024?String.format(Locale.US,"%.0f KB",bytes/1024.0):String.format(Locale.US,"%.1f MB",bytes/(1024.0*1024)); }
    private void documentActions(PdfRecord r) {if(busy)return;new AlertDialog.Builder(this).setTitle(r.name).setItems(new String[]{"Open","Share","Save a copy",r.favorite?"Remove from Favorites":"Add to Favorites","Move to folder","Reduce file size","Extract pages","Extract text","Rename","Move to Trash"},(dialog,which)->{switch(which){case 0:openPdf(r);break;case 1:share(r);break;case 2:export(r);break;case 3:folderMutation("Updating Favorites…",()->{store.setFavorite(r,!r.favorite);return null;},"Favorites updated");break;case 4:moveToFolder(r);break;case 5:reductionOptions(r);break;case 6:extractionOptions(r);break;case 7:ocrOptions(r);break;case 8:rename(r);break;case 9:delete(r);break;}}).show();}
    private boolean sameFolders(List<FolderRecord> first,List<FolderRecord> second){
        if(first.size()!=second.size())return false;for(int i=0;i<first.size();i++)if(!first.get(i).id.equals(second.get(i).id)||!first.get(i).name.equals(second.get(i).name))return false;return true;
    }
    private void manageFolders(){
        if(loading||busy)return;ArrayList<FolderRecord> available=new ArrayList<>(folders);String[] choices=new String[available.size()+1];choices[0]="Create folder";for(int i=0;i<available.size();i++)choices[i+1]=available.get(i).name;
        new AlertDialog.Builder(this).setTitle("Manage folders").setItems(choices,(dialog,which)->{if(which==0)folderNameDialog(null);else folderActions(available.get(which-1));}).setNegativeButton("Close",null).show();
    }
    private void folderActions(FolderRecord folder){
        new AlertDialog.Builder(this).setTitle(folder.name).setItems(new String[]{"Rename folder","Delete folder"},(dialog,which)->{
            if(which==0)folderNameDialog(folder);else new AlertDialog.Builder(this).setTitle("Delete folder?")
                .setMessage("Delete "+folder.name+"? All PDFs are kept and remain available in All PDFs.")
                .setNegativeButton("Cancel",null).setPositiveButton("Delete folder",(d,w)->folderMutation("Deleting folder…",()->{store.deleteFolder(folder.id);return null;},"Folder deleted; PDFs kept")).show();
        }).setNegativeButton("Cancel",null).show();
    }
    private void folderNameDialog(FolderRecord folder){
        EditText name=new EditText(this);name.setSingleLine(true);name.setMinHeight(dp(52));name.setHint("Folder name");if(folder!=null){name.setText(folder.name);name.setSelectAllOnFocus(true);}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(folder==null?"Create folder":"Rename folder").setView(name).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String title=name.getText().toString().trim();if(title.isEmpty()){name.setError("Enter a folder name");return;}if(title.codePointCount(0,title.length())>80){name.setError("Use up to 80 characters");return;}
            dialog.dismiss();folderMutation("Saving folder…",()->{if(folder==null)return store.createFolder(title);store.renameFolder(folder.id,title);return null;},"Folder saved");}));dialog.show();
    }
    private void moveToFolder(PdfRecord record){
        ArrayList<FolderRecord> available=new ArrayList<>(folders);String[] choices=new String[available.size()+1];choices[0]="No folder";int selection=0;for(int i=0;i<available.size();i++){choices[i+1]=available.get(i).name;if(available.get(i).id.equals(record.folderId))selection=i+1;}
        new AlertDialog.Builder(this).setTitle("Move to folder").setSingleChoiceItems(choices,selection,(dialog,which)->{
            String id=which==0?null:available.get(which-1).id;dialog.dismiss();folderMutation("Moving PDF…",()->{store.moveToFolder(record,id);return null;},"PDF moved");
        }).setNegativeButton("Cancel",null).setNeutralButton("Create folder",(dialog,which)->folderNameDialog(null)).show();
    }
    private void folderMutation(String title,Callable<?> action,String success){
        if(busy||destroyed||work.isShutdown())return;beginWork(title);work.execute(()->{try{action.call();runOnUiThread(()->{if(destroyed)return;endWork();refreshDocuments();toast(success);});}
            catch(Exception e){String detail=e.getMessage();failure(detail==null||detail.isEmpty()?"Folders could not be updated. Check available storage and try again.":detail,e);}});
    }
    private void openPdf(PdfRecord r) {startActivity(new Intent(this,PdfViewerActivity.class).putExtra("path",r.file.getAbsolutePath()).putExtra("name",r.name));}
    private void share(PdfRecord r) {try{Uri uri=store.uri(r);Intent intent=new Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri(r.name,uri));startActivity(Intent.createChooser(intent,"Share PDF"));}catch(Exception e){showError("No app is available to share this PDF.");}}
    private void rename(PdfRecord r) {
        EditText name=new EditText(this);name.setSingleLine(true);name.setText(r.name.replaceFirst("(?i)\\.pdf$",""));name.setSelectAllOnFocus(true);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Rename PDF").setView(name).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{if(name.getText().toString().trim().isEmpty()){name.setError("Enter a name");return;}final String requestedName=name.getText().toString();dialog.dismiss();beginWork("Renaming PDF…");work.execute(()->{try{store.rename(r,requestedName);runOnUiThread(()->{endWork();refreshDocuments();});}catch(Exception e){failure("Could not rename this PDF.",e);}});}));dialog.show();
    }
    private void delete(PdfRecord r) {if(busy||destroyed)return;new AlertDialog.Builder(this).setTitle("Move to Trash?").setMessage(r.name+"\nYou can restore this PDF from Trash.").setNegativeButton("Cancel",null).setPositiveButton("Move to Trash",(d,w)->{if(busy||destroyed||work.isShutdown())return;beginWork("Moving to Trash…");work.execute(()->{try{TrashRecord item=store.moveToTrash(r);runOnUiThread(()->{if(isDestroyed())return;endWork();refreshDocuments();new AlertDialog.Builder(this).setTitle("Moved to Trash").setMessage(r.name).setNegativeButton("Done",null).setPositiveButton("Undo",(dialog,which)->restoreTrash(item.id)).show();});}catch(Exception e){failure("Could not move this PDF to Trash. Your original is kept.",e);}});}).show();}
    private void export(PdfRecord r) {preferences.edit().putString("export_path",r.file.getAbsolutePath()).apply();Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf").putExtra(Intent.EXTRA_TITLE,r.name);try{startActivityForResult(i,EXPORT);}catch(Exception e){showError("Your device has no file picker to save a copy.");}}

    private void renderTrash() {
        gap(body,10);body.addView(text("Trash",30,INK,true));gap(body,8);
        body.addView(text(trashed.size()+" item"+(trashed.size()==1?"":"s")+" · restore or delete permanently",14,MUTED,false));gap(body,16);
        fullButton(body,"Back to My PDFs",false,v->{screen="library";render();});gap(body,18);
        if(trashed.isEmpty()){body.addView(text("Trash is empty",19,INK,true));return;}
        for(TrashRecord item:trashed){LinearLayout c=card();c.addView(text(item.name,17,INK,true));gap(c,8);
            c.addView(text(item.pageCount+" pages · "+size(item.sizeBytes)+"\nMoved "+DateFormat.getDateInstance().format(new Date(item.deletedAt)),13,MUTED,false));gap(c,12);
            fullButton(c,"Restore",true,v->restoreTrash(item.id));gap(c,8);fullButton(c,"Delete permanently",false,v->confirmPurge(item));body.addView(c);gap(body,12);
        }
        gap(body,8);fullButton(body,"Empty Trash",false,v->new AlertDialog.Builder(this).setTitle("Empty Trash?").setMessage("Permanently delete all "+trashed.size()+" files in Trash? This cannot be undone.").setNegativeButton("Cancel",null).setPositiveButton("Delete permanently",(d,w)->trashMutation("Emptying Trash…",()->{store.emptyTrash();return null;},"Trash emptied")).show());
    }
    private void restoreTrash(String id) {if(busy)return;trashMutation("Restoring PDF…",()->store.restore(id),"PDF restored");}
    private void confirmPurge(TrashRecord item) {if(busy)return;new AlertDialog.Builder(this).setTitle("Delete permanently?").setMessage(item.name+"\nThis cannot be undone.").setNegativeButton("Cancel",null).setPositiveButton("Delete permanently",(d,w)->trashMutation("Deleting permanently…",()->{store.deletePermanently(item.id);return null;},"PDF deleted permanently")).show();}
    private void trashMutation(String title,Callable<?> action,String success) {
        if(busy||destroyed||work.isShutdown())return;beginWork(title);work.execute(()->{try{action.call();runOnUiThread(()->{if(isDestroyed())return;endWork();refreshDocuments();toast(success);});}catch(Exception e){failure("Trash could not be updated. Check available storage and try again.",e);}});
    }
    private void chooseMerge() {
        if(loading||busy)return;
        if(documents.size()<2){showError("Create at least two PDFs before merging.");return;}
        ArrayList<PdfRecord> available=new ArrayList<>(documents);boolean[] selected=new boolean[available.size()];String[] labels=new String[available.size()];
        for(int i=0;i<labels.length;i++)labels[i]=available.get(i).name+" · "+available.get(i).pageCount+" pages";
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Choose PDFs to merge").setMultiChoiceItems(labels,selected,(d,which,checked)->selected[which]=checked).setNegativeButton("Cancel",null).setPositiveButton("Next",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{ArrayList<PdfRecord> ordered=new ArrayList<>();int count=0;for(int i=0;i<selected.length;i++)if(selected[i]){ordered.add(available.get(i));count+=available.get(i).pageCount;}
            if(ordered.size()<2){toast("Choose at least two PDFs");return;}if(count>PdfStore.MAX_PAGES){showError("The merged PDF can contain up to "+PdfStore.MAX_PAGES+" pages.");return;}dialog.dismiss();mergeOrder(ordered);
        }));dialog.show();
    }
    private void mergeOrder(ArrayList<PdfRecord> ordered) {
        LinearLayout options=column();options.setPadding(dp(22),dp(10),dp(22),dp(10));options.addView(text("Document name",13,MUTED,true));
        EditText name=new EditText(this);name.setSingleLine(true);name.setText("Merged document");name.setMinHeight(dp(52));options.addView(name);gap(options,12);
        options.addView(text("Files will appear in this order. Your originals are kept.",14,MUTED,false));gap(options,12);
        LinearLayout order=column();options.addView(order);populateMergeOrder(order,ordered);ScrollView scroll=new ScrollView(this);scroll.addView(options);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Merge PDFs").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Merge",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String title=name.getText().toString().trim();if(title.isEmpty()){name.setError("Enter a document name");return;}dialog.dismiss();mergePdfs(new ArrayList<>(ordered),title);}));dialog.show();
    }
    private void populateMergeOrder(LinearLayout container,ArrayList<PdfRecord> ordered) {
        container.removeAllViews();for(int i=0;i<ordered.size();i++){final int index=i;PdfRecord record=ordered.get(i);LinearLayout line=row();line.setPadding(0,dp(6),0,dp(6));
            TextView name=text((i+1)+". "+record.name,15,INK,true);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);line.addView(name,new LinearLayout.LayoutParams(0,-2,1));
            Button up=button("↑",false,v->{Collections.swap(ordered,index,index-1);populateMergeOrder(container,ordered);});up.setEnabled(i>0);up.setContentDescription("Move "+record.name+" earlier");up.setPadding(dp(6),dp(10),dp(6),dp(10));
            Button down=button("↓",false,v->{Collections.swap(ordered,index,index+1);populateMergeOrder(container,ordered);});down.setEnabled(i<ordered.size()-1);down.setContentDescription("Move "+record.name+" later");down.setPadding(dp(6),dp(10),dp(6),dp(10));
            line.addView(up,new LinearLayout.LayoutParams(dp(48),-2));LinearLayout.LayoutParams next=new LinearLayout.LayoutParams(dp(48),-2);next.setMargins(dp(6),0,0,0);line.addView(down,next);container.addView(line);
        }
    }
    private void mergePdfs(List<PdfRecord> ordered,String name) {
        ArrayList<File> files=new ArrayList<>();int pages=0;for(PdfRecord record:ordered){files.add(record.file);pages+=record.pageCount;}final int count=pages;
        runTask("Merging PDFs",count,"Could not merge these PDFs. Your originals are kept.",task->{File temporary=null;try{
            temporary=PdfOperations.merge(this,files,(done,total)->taskProgress(task,done,total));checkTask(task);final File complete=temporary;
            PdfRecord saved=commitTask(task,()->store.save(complete,name,count));task.success=()->showSavedCopy(saved,"Merged PDF ready");
        }finally{if(temporary!=null)temporary.delete();}});
    }
    private void extractionOptions(PdfRecord record) {
        if(busy)return;LinearLayout options=column();options.setPadding(dp(22),dp(10),dp(22),dp(10));
        options.addView(text("Create a new PDF from selected pages. The original is kept.",14,MUTED,false));gap(options,14);
        options.addView(text("Pages (1–"+record.pageCount+")",13,MUTED,true));EditText selection=new EditText(this);selection.setSingleLine(true);selection.setMinHeight(dp(52));selection.setText(record.pageCount==1?"1":"1-"+record.pageCount);selection.setHint("For example: 1-3, 5");options.addView(selection);gap(options,6);
        options.addView(text("Use commas and ranges, in the order you want.",12,MUTED,false));gap(options,14);
        options.addView(text("Document name",13,MUTED,true));EditText name=new EditText(this);name.setSingleLine(true);name.setMinHeight(dp(52));name.setText(derivedName(record.name," - pages"));options.addView(name);
        ScrollView scroll=new ScrollView(this);scroll.addView(options);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Extract pages").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Create PDF",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{int[] selected;try{selected=PdfOperations.parsePageSelection(selection.getText().toString(),record.pageCount);}catch(Exception e){selection.setError(e.getMessage());return;}String title=name.getText().toString().trim();if(title.isEmpty()){name.setError("Enter a name");return;}dialog.dismiss();extractPages(record,selected,title);}));dialog.show();
    }
    private void extractPages(PdfRecord original,int[] selected,String name) {
        runTask("Extracting pages",selected.length,"Could not extract these pages. Your original is kept.",task->{File temporary=null;try{
            temporary=PdfOperations.extract(this,original.file,selected,(done,total)->taskProgress(task,done,total));checkTask(task);final File complete=temporary;
            PdfRecord saved=commitTask(task,()->store.save(complete,name,selected.length));task.success=()->showSavedCopy(saved,"Selected pages ready");
        }finally{if(temporary!=null)temporary.delete();}});
    }
    private String derivedName(String original,String suffix) {
        String base=original.replaceFirst("(?i)\\.pdf$","");int maximum=Math.max(1,100-suffix.length());
        if(base.length()>maximum){int end=Character.isHighSurrogate(base.charAt(maximum-1))?maximum-1:maximum;base=base.substring(0,end).trim();}return base+suffix;
    }
    private void showSavedCopy(PdfRecord saved,String title) {
        screen="library";query="";refreshDocuments();render();new AlertDialog.Builder(this).setTitle(title).setMessage(saved.name+"\n"+saved.pageCount+" pages · "+size(saved.sizeBytes)+"\nYour originals are kept in My PDFs.").setNegativeButton("Done",null).setNeutralButton("Share",(d,w)->share(saved)).setPositiveButton("Open PDF",(d,w)->openPdf(saved)).show();
    }
    private void ocrOptions(PdfRecord record) {
        if(busy)return;
        if(record.pageCount>OcrExtractor.MAX_PAGES){showError("Text extraction supports up to "+OcrExtractor.MAX_PAGES+" pages. Extract fewer pages into a new PDF first.");return;}
        new AlertDialog.Builder(this).setTitle("Extract text").setMessage("Reads printed English and Latin-script text on this device. Urdu is not supported yet. Handwriting and unclear scans may contain mistakes; check the result before using it. Your PDF is kept.").setNegativeButton("Cancel",null).setPositiveButton("Extract text",(d,w)->runTask("Recognizing text",record.pageCount,"Could not extract text. Your PDF is kept; try clearer scans or fewer pages.",task->{String result=OcrExtractor.extract(this,record.file,(done,total)->taskProgress(task,done,total));checkTask(task);task.success=()->showTextResult(result,record.name);})).show();
    }
    private void showTextResult(String result,String name) {
        if(result.trim().isEmpty()){showError("No text was found. Try a clearer scan of printed English or Latin-script text.");return;}
        LinearLayout content=column();content.setPadding(dp(22),dp(12),dp(22),dp(12));content.addView(text("Check recognized text for mistakes.",13,MUTED,false));gap(content,12);
        fullButton(content,"Copy text",false,v->{if(result.length()>150_000){showError("This document is too long to copy at once. Share or save the TXT file instead.");return;}
            try{android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText(name,result));toast("Text copied");}catch(RuntimeException e){showError("Text could not be copied. Save or share the TXT file instead.");}});gap(content,14);
        fullButton(content,"Share",false,v->prepareTextExport(result,name,true));gap(content,8);fullButton(content,"Save TXT",true,v->prepareTextExport(result,name,false));gap(content,14);
        String preview=result;if(result.length()>80_000){int end=Character.isHighSurrogate(result.charAt(79_999))?79_999:80_000;preview=result.substring(0,end)+"\n\nPreview shortened. Share or save the TXT file for all recognized text.";}
        TextView recognized=text(preview,15,INK,false);recognized.setTextIsSelectable(true);recognized.setLineSpacing(dp(3),1);content.addView(recognized);ScrollView scroll=new ScrollView(this);scroll.addView(content);
        new AlertDialog.Builder(this).setTitle("Recognized text").setView(scroll).setNegativeButton("Close",null).show();
    }
    private void prepareTextExport(String text,String name,boolean sharing) {
        if(busy)return;beginWork("Preparing text file…");work.execute(()->{File file=null;try{
            File folder=new File(getCacheDir(),"text_exports");if(!folder.isDirectory()&&!folder.mkdirs())throw new java.io.IOException("Text folder unavailable");
            File[] previous=folder.listFiles();if(previous!=null)for(File old:previous)if(old.isFile()&&System.currentTimeMillis()-old.lastModified()>2L*24*60*60*1000)old.delete();
            file=File.createTempFile("recognized-",".txt",folder);try(FileOutputStream output=new FileOutputStream(file)){output.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));output.getFD().sync();}
            if(destroyed||Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Text export cancelled");
            String title=name.replaceFirst("(?i)\\.pdf$","")+".txt";final File ready=file;
            if(!sharing)preferences.edit().putString("text_export_path",ready.getAbsolutePath()).apply();
            runOnUiThread(()->{if(isDestroyed()){ready.delete();if(!sharing&&ready.getAbsolutePath().equals(preferences.getString("text_export_path",null)))preferences.edit().remove("text_export_path").apply();return;}endWork();try{
                if(sharing){Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",ready,title);Intent intent=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri(title,uri));startActivity(Intent.createChooser(intent,"Share recognized text"));}
                else startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE,title),TEXT_EXPORT);
            }catch(Exception e){ready.delete();if(!sharing)preferences.edit().remove("text_export_path").apply();showError("No app is available to share or save this text file.");}});
        }catch(Exception e){if(file!=null)file.delete();failure("Could not prepare the text file. Check available storage and try again.",e);}});
    }

    private void importImages() {
        if(loading||busy||draftLocked())return;if(pages.size()>=PAGE_LIMIT){showError("A PDF can have up to 100 pages. Convert this draft to start another.");return;}
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        try{startActivityForResult(intent,IMPORT);}catch(Exception e){showError("Your device has no image picker. Try scanning a document instead.");}
    }
    private void startScanner() {
        if(loading||busy||draftLocked())return;if(pages.size()>=PAGE_LIMIT){showError("This draft already has 100 pages.");return;}
        GmsDocumentScannerOptions options=new GmsDocumentScannerOptions.Builder().setGalleryImportAllowed(true).setPageLimit(PAGE_LIMIT-pages.size()).setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG).setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL).build();
        GmsDocumentScanner scanner=GmsDocumentScanning.getClient(options);beginWork("Preparing document scanner…");
        scanner.getStartScanIntent(this).addOnSuccessListener(sender->{if(isDestroyed())return;endWork();try{startIntentSenderForResult(sender,SCAN,null,0,0,0);}catch(Exception e){scannerFallback();}}).addOnFailureListener(error->{if(isDestroyed())return;endWork();scannerFallback();});
    }
    private void scannerFallback() {new AlertDialog.Builder(this).setTitle("Use your camera instead").setMessage("The document scanner requires supported Google Play services and a first-time component download. You can take a full-resolution photo now, then crop and enhance it in your workspace.").setNegativeButton("Cancel",null).setPositiveButton("Open camera",(d,w)->capturePhoto()).show();}
    private void capturePhoto() {
        try{File folder=new File(getCacheDir(),"camera");if(!folder.exists()&&!folder.mkdirs())throw new Exception("Camera folder unavailable");File file=new File(folder,UUID.randomUUID()+".jpg");
            preferences.edit().putString("camera_path",file.getAbsolutePath()).apply();Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",file);
            Intent i=new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT,uri).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);i.setClipData(ClipData.newRawUri("Camera photo",uri));startActivityForResult(i,CAMERA);
        }catch(Exception e){showError("No camera app is available. Import an image from your device instead.");}
    }
    private void crop(PageItem page) {if(draftLocked())return;if(page.rotation!=0){new AlertDialog.Builder(this).setTitle("Crop original image?").setMessage("Crop works on the original orientation. Your rotation and filter will be kept when you finish.").setNegativeButton("Cancel",null).setPositiveButton("Crop",(d,w)->launchCrop(page)).show();}else launchCrop(page);}
    private void launchCrop(PageItem page) {if(draftLocked())return;preferences.edit().putString("crop_id",page.id).apply();startActivityForResult(new Intent(this,CropActivity.class).putExtra("path",page.path),CROP);}

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(loading){pendingResults.add(new PendingResult(request,result,data));return;}
        handleActivityResult(request,result,data);
    }
    private void handleActivityResult(int request,int result,Intent data) {
        if(request==TEXT_EXPORT){String path=preferences.getString("text_export_path",null);preferences.edit().remove("text_export_path").apply();
            File source=null;try{if(path!=null){File candidate=new File(path).getCanonicalFile();String directory=new File(getCacheDir(),"text_exports").getCanonicalPath()+File.separator;if(candidate.getPath().startsWith(directory)&&candidate.isFile())source=candidate;}}catch(Exception ignored){}
            if(source==null){if(result==RESULT_OK)showError("This text file is no longer available. Extract the text again.");return;}
            if(result!=RESULT_OK||data==null||data.getData()==null){source.delete();return;}final File textFile=source;Uri destination=data.getData();beginWork("Saving text file…");
            work.execute(()->{try(InputStream input=new FileInputStream(textFile);OutputStream output=getContentResolver().openOutputStream(destination,"wt")){if(output==null)throw new java.io.IOException("Destination unavailable");copy(input,output,8L*1024*1024);runOnUiThread(()->{if(isDestroyed())return;endWork();toast("TXT file saved");});}catch(Exception e){failure("Could not save the text file. Choose another location.",e);}finally{textFile.delete();}});return;
        }
        if(request==CAMERA) {
            String path=preferences.getString("camera_path",null);preferences.edit().remove("camera_path").apply();
            if(path!=null){File file=new File(path);if(result==RESULT_OK&&file.length()>0){ArrayList<Uri> uris=new ArrayList<>();uris.add(Uri.fromFile(file));importUris(uris,true,file);}else file.delete();}return;
        }
        if(result!=RESULT_OK)return;
        if(request==IMPORT&&data!=null){ArrayList<Uri> uris=new ArrayList<>();if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());}else if(data.getData()!=null)uris.add(data.getData());importUris(uris,false,null);}
        else if(request==SCAN&&data!=null){GmsDocumentScanningResult scan=GmsDocumentScanningResult.fromActivityResultIntent(data);if(scan!=null&&scan.getPages()!=null){ArrayList<Uri> uris=new ArrayList<>();for(GmsDocumentScanningResult.Page p:scan.getPages())uris.add(p.getImageUri());importUris(uris,false,null);}else showError("The scanner returned no pages. Please try again.");}
        else if(request==CROP&&data!=null){String id=preferences.getString("crop_id","");String path=data.getStringExtra("path");if(path!=null&&validDraftPath(path)){if(draftLocked()){deleteDraftImage(path);preferences.edit().remove("crop_id").apply();return;}boolean used=false;for(PageItem page:pages)if(page.id.equals(id)){String old=page.path;page.path=path;if(persistDraft()){deleteDraftImage(old);used=true;}else page.path=old;break;}if(!used)deleteDraftImage(path);render();}preferences.edit().remove("crop_id").apply();}
        else if(request==EXPORT&&data!=null&&data.getData()!=null){String path=preferences.getString("export_path",null);Uri destination=data.getData();if(path==null)return;beginWork("Saving a copy…");work.execute(()->{try{File source=new File(path);if(!source.getCanonicalPath().startsWith(new File(getFilesDir(),"pdfs").getCanonicalPath()+File.separator))throw new Exception("Invalid document");try(InputStream in=new FileInputStream(source);OutputStream out=getContentResolver().openOutputStream(destination,"wt")){if(out==null)throw new Exception("Destination unavailable");copy(in,out,Long.MAX_VALUE);}runOnUiThread(()->{endWork();toast("PDF copy saved");});}catch(Exception e){failure("Could not save this copy. Choose another location.",e);}finally{preferences.edit().remove("export_path").apply();}});}
    }
    private void importUris(List<Uri> uris,boolean enhance,File cleanup) {
        if(draftLocked()){if(cleanup!=null)cleanup.delete();showError("Wait for PDF creation to finish before adding pages.");return;}
        if(uris.isEmpty())return;int available=Math.max(0,PAGE_LIMIT-pages.size());List<Uri> accepted=new ArrayList<>(uris.subList(0,Math.min(available,uris.size())));
        if(accepted.isEmpty()){if(cleanup!=null)cleanup.delete();showError("This draft already has 100 pages. Create a PDF before importing more.");return;}
        ArrayList<PageItem> restored=new ArrayList<>();for(PageItem page:pages)restored.add(page.copy());
        runTask("Importing images",accepted.size(),"The imported images could not be saved. Your draft is kept; check device storage and try again.",task->{
            ArrayList<PageItem> added=new ArrayList<>();int failed=0;boolean committed=false;
            try{File folder=new File(getFilesDir(),"draft-pages");if(!folder.isDirectory()&&!folder.mkdirs())throw new java.io.IOException("Image folder unavailable");
                for(int i=0;i<accepted.size();i++){checkTask(task);File file=new File(folder,UUID.randomUUID()+".img");boolean keep=false;
                    try{Uri uri=accepted.get(i);try(InputStream input=getContentResolver().openInputStream(uri);OutputStream output=new FileOutputStream(file)){if(input==null)throw new Exception("Image unavailable");copy(input,output,80L*1024*1024);}
                        checkTask(task);BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);if(bounds.outWidth<=0||bounds.outHeight<=0)throw new Exception("Unsupported image");
                        PageItem page=new PageItem(file.getAbsolutePath());if(enhance)page.filter=PageItem.Filter.ENHANCED;added.add(page);keep=true;
                    }catch(Exception e){checkTask(task);failed++;}finally{if(!keep)file.delete();}
                    taskProgress(task,i+1,accepted.size());
                }
                checkTask(task);restored.addAll(added);
                commitTask(task,()->{writeDraft(restored);return null;});committed=true;
                final int errors=failed;final boolean limited=accepted.size()<uris.size();
                task.success=()->{pages.addAll(added);screen="draft";render();if(errors>0)showError(errors+" image"+(errors==1?"":"s")+" could not be imported. Use JPEG, PNG, WebP, or a format supported by your device, under 80 MB per image.");else if(limited)toast("Imported up to the 100-page limit");else toast(added.size()+" page"+(added.size()==1?"":"s")+" added");};
            }finally{if(!committed)for(PageItem page:added)deleteDraftImage(page.path);if(cleanup!=null)cleanup.delete();}
        },()->{if(cleanup!=null)cleanup.delete();});
    }
    private static void copy(InputStream input,OutputStream output,long maximum) throws Exception {byte[] buffer=new byte[32768];long count=0;int read;while((read=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Operation cancelled");count+=read;if(count>maximum)throw new Exception("File is too large");output.write(buffer,0,read);}output.flush();}

    private void conversionOptions() {
        if(loading||busy||draftLocked()||pages.isEmpty())return;
        LinearLayout options=column();options.setPadding(dp(22),dp(10),dp(22),dp(6));options.addView(text("Document name",13,MUTED,true));gap(options,6);
        EditText name=new EditText(this);name.setSingleLine(true);name.setText("Document "+new SimpleDateFormat("yyyy-MM-dd HHmm",Locale.US).format(new Date()));name.setTextColor(INK);name.setMinHeight(dp(52));options.addView(name,new LinearLayout.LayoutParams(-1,-2));gap(options,14);
        options.addView(text("PDF quality",13,MUTED,true));
        Spinner quality=spinner(new String[]{"Balanced (recommended)","Compact","High"});
        quality.setContentDescription("PDF quality");quality.setSelection(savedProfileIndex());
        options.addView(quality,new LinearLayout.LayoutParams(-1,-2));gap(options,6);
        TextView qualityHint=text("",13,MUTED,false);options.addView(qualityHint);bindProfileHint(quality,qualityHint);
        gap(options,12);
        Button more=button("More options",false,null);options.addView(more,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout advanced=column();advanced.setVisibility(View.GONE);gap(advanced,14);
        advanced.addView(text("Paper size",13,MUTED,true));Spinner paper=spinner(new String[]{"A4","Letter","Fit to image"});paper.setSelection(Math.max(0,Math.min(2,preferences.getInt("paper",0))));advanced.addView(paper,new LinearLayout.LayoutParams(-1,-2));gap(advanced,8);
        CheckBox margin=new CheckBox(this);margin.setText("Add a small page margin");margin.setTextColor(INK);margin.setButtonTintList(ColorStateList.valueOf(GREEN));margin.setChecked(preferences.getBoolean("margin",true));margin.setMinHeight(dp(48));advanced.addView(margin);gap(advanced,10);
        options.addView(advanced);
        more.setOnClickListener(v->{boolean expanded=advanced.getVisibility()==View.VISIBLE;advanced.setVisibility(expanded?View.GONE:View.VISIBLE);more.setText(expanded?"More options":"Fewer options");});
        ScrollView optionsScroll=new ScrollView(this);optionsScroll.addView(options);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Create PDF").setView(optionsScroll).setNegativeButton("Cancel",null).setPositiveButton("Create PDF",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{String title=name.getText().toString().trim();if(title.isEmpty()){name.setError("Enter a document name");return;}
            PdfConverter.Options settings=new PdfConverter.Options();settings.name=title;settings.profile=profileAt(quality.getSelectedItemPosition());settings.paper=new String[]{"A4","LETTER","AUTO"}[paper.getSelectedItemPosition()];settings.margin=margin.isChecked();preferences.edit().putInt("paper",paper.getSelectedItemPosition()).putString("pdf_profile",settings.profile.name()).putBoolean("margin",margin.isChecked()).apply();dialog.dismiss();convert(settings);}));dialog.show();
    }
    private Spinner spinner(String[] options) {Spinner s=new Spinner(this);s.setMinimumHeight(dp(48));ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,options);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(adapter);return s;}
    private PdfConverter.Profile profileAt(int index) {
        if(index==1)return PdfConverter.Profile.COMPACT;
        if(index==2)return PdfConverter.Profile.HIGH;
        return PdfConverter.Profile.BALANCED;
    }
    private int savedProfileIndex() {
        String saved=preferences.getString("pdf_profile","BALANCED");
        if("COMPACT".equals(saved))return 1;if("HIGH".equals(saved))return 2;return 0;
    }
    private void bindProfileHint(Spinner quality,TextView hint) {
        final String[] hints={"Clear pages with a smaller file. Best for everyday documents.","Smallest file for sharing. Fine detail may look less sharp.","Most detail for small text and photos. Creates a larger file."};
        hint.setText(hints[Math.max(0,Math.min(2,quality.getSelectedItemPosition()))]);
        quality.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id){hint.setText(hints[Math.max(0,Math.min(2,position))]);}
            @Override public void onNothingSelected(AdapterView<?> parent){}
        });
    }
    private void reductionOptions(PdfRecord record) {
        if(loading||busy)return;
        LinearLayout options=column();options.setPadding(dp(22),dp(10),dp(22),dp(10));
        options.addView(text("Current size: "+size(record.sizeBytes),14,INK,true));gap(options,8);
        options.addView(text("Save a smaller copy. Your original stays in My PDFs. Text selection and links are not preserved in the copy.",14,MUTED,false));gap(options,18);
        options.addView(text("Copy quality",13,MUTED,true));
        Spinner quality=spinner(new String[]{"Balanced","Compact (recommended)","High"});quality.setSelection(1);quality.setContentDescription("Quality of the smaller PDF copy");
        options.addView(quality,new LinearLayout.LayoutParams(-1,-2));gap(options,6);
        TextView hint=text("",13,MUTED,false);options.addView(hint);bindProfileHint(quality,hint);
        ScrollView scroll=new ScrollView(this);scroll.addView(options);
        new AlertDialog.Builder(this).setTitle("Reduce file size").setView(scroll).setNegativeButton("Cancel",null)
                .setPositiveButton("Save smaller copy",(dialog,which)->reducePdf(record,profileAt(quality.getSelectedItemPosition()))).show();
    }
    private void reducePdf(PdfRecord original,PdfConverter.Profile profile) {
        if(loading||busy)return;
        runTask("Reducing PDF size",original.pageCount,"Could not reduce this PDF. Your original is kept; check free storage and try again.",task->{File temporary=null;try{
            final long originalBytes=original.file.length();
            if(originalBytes<=0)throw new java.io.IOException("The original PDF is unavailable.");
            temporary=PdfRecompressor.recompress(this,original.file,profile,(done,total)->taskProgress(task,done,total));checkTask(task);
            if(temporary==null||!temporary.isFile()||temporary.length()==0)throw new java.io.IOException("The smaller PDF could not be created.");
            if(temporary.length()>=originalBytes){task.success=()->new AlertDialog.Builder(this).setTitle("Already small at this quality").setMessage("No smaller copy was saved. Your original PDF is kept.").setPositiveButton("OK",null).show();return;}
            String baseName=original.name.replaceFirst("(?i)\\.pdf$","");
            if(baseName.length()>90){int end=Character.isHighSurrogate(baseName.charAt(89))?89:90;baseName=baseName.substring(0,end).trim();}
            String name=baseName+" - smaller";
            final File complete=temporary;PdfRecord saved=commitTask(task,()->store.save(complete,name,original.pageCount));
            task.success=()->{screen="library";query="";refreshDocuments();render();
                new AlertDialog.Builder(this).setTitle("Smaller PDF saved").setMessage(saved.name+"\n"+size(originalBytes)+" → "+size(saved.sizeBytes)+"\nSaved "+size(originalBytes-saved.sizeBytes)+". Your original is kept in My PDFs.")
                        .setNegativeButton("Done",null).setNeutralButton("Share",(dialog,which)->share(saved)).setPositiveButton("Open PDF",(dialog,which)->openPdf(saved)).show();};
        }
        finally{if(temporary!=null)temporary.delete();}});
    }
    private void convert(PdfConverter.Options options) {
        if(loading||busy||draftLocked()||pages.isEmpty()||destroyed||isFinishing())return;
        ArrayList<PageItem> selected=new ArrayList<>();for(PageItem page:pages)selected.add(page.copy());
        try{
            BackgroundPdfJobs.start(this,selected,options);++statusGeneration;backgroundStatus=BackgroundPdfJobs.read(this);clearedJobId=null;render();
            explainNotificationPermission();pollBackgroundStatus();
        }catch(Exception e){pollBackgroundStatus();showError("Could not start PDF creation. Your draft is kept; reopen the app and try again.");}
    }
    private void idCardOptions(){
        if(loading||busy||draftLocked())return;
        if(pages.size()!=2){showError("ID-card mode needs exactly two photos: front first, then back. Add or remove pages in your draft to keep two.");return;}
        LinearLayout options=column();options.setPadding(dp(22),dp(10),dp(22),dp(10));
        options.addView(text("Page 1 is the front; page 2 is the back. Both will fit on one A4 page. Crop each photo close to the card edges first.",15,MUTED,false));gap(options,14);
        EditText name=new EditText(this);name.setSingleLine(true);name.setText("ID card "+new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date()));name.setMinHeight(dp(52));options.addView(name,new LinearLayout.LayoutParams(-1,-2));gap(options,12);
        options.addView(text("PDF quality",13,MUTED,true));Spinner quality=spinner(new String[]{"Balanced (recommended)","Compact","High"});quality.setSelection(savedProfileIndex());options.addView(quality,new LinearLayout.LayoutParams(-1,-2));
        TextView hint=text("",13,MUTED,false);options.addView(hint);bindProfileHint(quality,hint);ScrollView scroll=new ScrollView(this);scroll.addView(options);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Create ID-card PDF").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Create PDF",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String title=name.getText().toString().trim();if(title.isEmpty()){name.setError("Enter a document name");return;}
            PdfConverter.Options settings=new PdfConverter.Options();settings.name=title;settings.profile=profileAt(quality.getSelectedItemPosition());settings.idCardMode=true;dialog.dismiss();convert(settings);}));dialog.show();
    }
    private boolean draftLocked(){return backgroundStatus!=null&&backgroundStatus.active;}
    private void explainNotificationPermission(){
        if(Build.VERSION.SDK_INT<33||checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED||preferences.getBoolean("notification_permission_explained",false))return;
        preferences.edit().putBoolean("notification_permission_explained",true).apply();
        new AlertDialog.Builder(this).setTitle("Conversion notifications").setMessage("Allow notifications to see PDF progress and completion while PaperPocket is minimized. Conversion also works without this permission.")
                .setNegativeButton("Not now",null).setPositiveButton("Allow notifications",(d,w)->requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_PERMISSION)).show();
    }
    private void pollBackgroundStatus(){
        if(destroyed||loading||statusReadPending||statusReader.isShutdown())return;statusReadPending=true;final int generation=++statusGeneration;
        statusReader.execute(()->{BackgroundPdfJobs.Status value=null;boolean read=false;try{value=BackgroundPdfJobs.read(this);read=true;}catch(Exception ignored){}
            final BackgroundPdfJobs.Status next=value;final boolean successful=read;
            runOnUiThread(()->{statusReadPending=false;if(destroyed)return;
                if(generation!=statusGeneration){if(resumed){statusHandler.removeCallbacks(statusPoll);statusHandler.post(statusPoll);}return;}
                if(!successful){if(resumed&&draftLocked())statusHandler.postDelayed(statusPoll,1500);return;}BackgroundPdfJobs.Status previous=backgroundStatus;backgroundStatus=next;
                boolean activityChanged=(previous==null?next!=null:next==null||!previous.id.equals(next.id)||previous.active!=next.active);
                boolean cleared=clearMatchingCompletedDraft();
                if(activityChanged||cleared){render();if(previous!=null&&previous.active&&(next==null||!next.active))refreshDocuments();}else renderBackgroundBanner();
                if(resumed&&next!=null&&next.active){statusHandler.removeCallbacks(statusPoll);statusHandler.postDelayed(statusPoll,700);}
            });
        });
    }
    /** A completed older job must never discard a newer or edited draft. */
    private boolean clearMatchingCompletedDraft(){
        BackgroundPdfJobs.Status status=backgroundStatus;
        if(status==null||status.acknowledged||!"completed".equals(status.state)||status.id.equals(clearedJobId))return false;
        try{synchronized(DRAFT_LOCK){
            if(!status.sourceFingerprint.equals(BackgroundPdfJobs.fingerprint(pages))){clearedJobId=status.id;return false;}
            ArrayList<PageItem> saved=loadDraft();if(!status.sourceFingerprint.equals(BackgroundPdfJobs.fingerprint(saved))){clearedJobId=status.id;return false;}
            writeDraft(Collections.emptyList());ArrayList<PageItem> old=new ArrayList<>(pages);pages.clear();for(PageItem page:old)deleteDraftImage(page.path);clearedJobId=status.id;return true;
        }}catch(Exception e){clearedJobId=status.id;showError("Your PDF was saved, but the original draft could not be cleared. You can clear it later from Pages.");return false;}
    }
    private void renderBackgroundBanner(){
        if(backgroundBanner==null)return;BackgroundPdfJobs.Status status=backgroundStatus;
        if(status!=null&&!status.acknowledged&&status.active&&bannerActive&&status.id.equals(bannerJobId)&&jobProgress!=null){updateBackgroundProgress(status);return;}
        backgroundBanner.removeAllViews();bannerJobId=status==null?null:status.id;bannerActive=status!=null&&status.active;jobProgress=null;jobTitle=null;jobCaption=null;jobCancel=null;
        if(status==null||status.acknowledged){backgroundBanner.setVisibility(View.GONE);return;}backgroundBanner.setVisibility(View.VISIBLE);
        LinearLayout content=column();content.setPadding(dp(14),dp(12),dp(14),dp(12));content.setBackground(shape(PALE,14));backgroundBanner.addView(content,new LinearLayout.LayoutParams(-1,-2));
        if(status.active){
            jobTitle=text("Creating your PDF",15,GREEN,true);content.addView(jobTitle);gap(content,8);
            jobProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);content.addView(jobProgress,new LinearLayout.LayoutParams(-1,dp(10)));gap(content,6);
            jobCaption=text("",12,GREEN,false);jobCaption.setMaxLines(2);content.addView(jobCaption);
            jobCancel=button("Cancel conversion",false,v->{try{BackgroundPdfJobs.cancel(this,status.id);++statusGeneration;pollBackgroundStatus();}catch(Exception e){showError("Cancellation could not be requested. Try again shortly.");}});content.addView(jobCancel,new LinearLayout.LayoutParams(-1,-2));updateBackgroundProgress(status);
        }else{
            boolean complete="completed".equals(status.state);String title=complete?"Your PDF is ready":"cancelled".equals(status.state)?"Conversion cancelled":"Conversion stopped";
            content.addView(text(title,15,GREEN,true));gap(content,6);TextView detail=text(complete?status.name+" · saved in My PDFs":status.error==null||status.error.isEmpty()?"Your draft is kept. You can try again from Pages.":status.error,13,GREEN,false);detail.setMaxLines(2);detail.setEllipsize(android.text.TextUtils.TruncateAt.END);content.addView(detail);
            LinearLayout actions=row();if(complete)miniTool(actions,"My PDFs",v->{screen="library";query="";libraryFilter="";render();refreshDocuments();});
            miniTool(actions,"Dismiss",v->{try{BackgroundPdfJobs.acknowledge(this,status.id);++statusGeneration;backgroundStatus=BackgroundPdfJobs.read(this);renderBackgroundBanner();}catch(Exception e){showError("This status could not be dismissed. Try again shortly.");}});content.addView(actions);
        }
    }
    private void updateBackgroundProgress(BackgroundPdfJobs.Status status){
        jobTitle.setText("saving".equals(status.state)?"Saving your PDF…":"cancelling".equals(status.state)?"Cancelling…":"Creating your PDF");
        jobProgress.setMax(Math.max(1,status.total));jobProgress.setProgress(status.done);jobProgress.setIndeterminate("preparing".equals(status.state)||"saving".equals(status.state));
        jobCaption.setText(status.done+" of "+status.total+" photos · you can minimize the app");jobCancel.setEnabled(!"saving".equals(status.state)&&!"cancelling".equals(status.state));
    }
    private void runTask(String title,int total,String errorMessage,TaskBody body) {
        runTask(title,total,errorMessage,body,null);
    }
    private void runTask(String title,int total,String errorMessage,TaskBody body,Runnable completionCleanup) {
        if(busy||destroyed||isFinishing()||work.isShutdown()){if(completionCleanup!=null)completionCleanup.run();return;}
        WorkSession task=new WorkSession();task.completionCleanup=completionCleanup;activeTask=task;busy=true;
        progress=new ProgressDialog(this);progress.setTitle(title);progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setIndeterminate(false);progress.setMax(Math.max(1,total));progress.setProgress(0);progress.setMessage("0 of "+Math.max(1,total)+" pages");progress.setCancelable(false);
        progress.setButton(AlertDialog.BUTTON_NEGATIVE,"Cancel",(dialog,which)->{});progress.show();
        progress.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->cancelTask(task));
        task.future=work.submit(()->{String failureMessage=null;
            try{synchronized(task.lock){if(task.canceled)throw new java.io.InterruptedIOException("Cancelled");task.started=true;}checkTask(task);body.run(task);}
            catch(Exception|OutOfMemoryError e){if(!task.canceled&&!destroyed&&!Thread.currentThread().isInterrupted())failureMessage=e instanceof PdfOperations.UnsupportedPdfException?e.getMessage():errorMessage;}
            finally{completeTask(task,failureMessage);}
        });
    }
    private void cancelTask(WorkSession task) {
        boolean queued;
        synchronized(task.lock){if(task.committing){toast("Finishing the save…");return;}task.canceled=true;queued=!task.started;if(task.future!=null)task.future.cancel(true);}
        if(activeTask==task&&progress!=null){progress.setMessage("Cancelling… Your draft and original PDFs are kept.");progress.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);}
        if(queued)completeTask(task,null);
    }
    private void checkTask(WorkSession task) throws java.io.InterruptedIOException {
        if(task.canceled||destroyed||Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Cancelled");
    }
    private <T>T commitTask(WorkSession task,Callable<T> commit) throws Exception {
        synchronized(task.lock){checkTask(task);task.committing=true;}
        runOnUiThread(()->{if(!destroyed&&!isFinishing()&&activeTask==task&&progress!=null){progress.setMessage("Saving…");progress.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);}});
        return commit.call();
    }
    private void taskProgress(WorkSession task,int done,int total) {
        runOnUiThread(()->{if(!destroyed&&activeTask==task&&!task.canceled&&!task.committing&&progress!=null){progress.setMax(Math.max(1,total));progress.setProgress(done);progress.setMessage(done+" of "+total+" pages");}});
    }
    private void completeTask(WorkSession task,String errorMessage) {
        Runnable cleanup;
        synchronized(task.lock){if(task.completed)return;task.completed=true;cleanup=task.completionCleanup;}
        if(cleanup!=null)cleanup.run();
        runOnUiThread(()->{if(destroyed||activeTask!=task)return;activeTask=null;endWork();if(isFinishing())return;
            if(task.canceled){toast("Cancelled. Your draft and original PDFs are kept.");return;}
            if(errorMessage!=null){showError(errorMessage);return;}if(task.success!=null)task.success.run();
        });
    }
    private void beginWork(String message) {busy=true;progress=new ProgressDialog(this);progress.setMessage(message);progress.setIndeterminate(true);progress.setCancelable(false);progress.show();}
    private void endWork() {busy=false;if(progress!=null){progress.dismiss();progress=null;}}
    private void failure(String message,Throwable e) {runOnUiThread(()->{if(isDestroyed())return;endWork();showError(message);});}
    private void showError(String message) {if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle("PaperPocket").setMessage(message).setPositiveButton("OK",null).show();}
    private void toast(String message) {if(!isDestroyed())Toast.makeText(this,message,Toast.LENGTH_SHORT).show();}
    private void refreshDocuments() {
        if(work.isShutdown())return;final int generation=++libraryGeneration;work.execute(()->{
            List<PdfRecord> records=null;List<TrashRecord> trash=null;List<FolderRecord> loadedFolders=null;
            try{records=store.list();}catch(Exception ignored){}
            try{trash=store.listTrash();}catch(Exception ignored){}
            try{loadedFolders=store.listFolders();}catch(Exception ignored){}
            final List<PdfRecord> active=records;final List<TrashRecord> removed=trash;final List<FolderRecord> nextFolders=loadedFolders;
            runOnUiThread(()->{if(isDestroyed()||generation!=libraryGeneration)return;if(active!=null)documents=active;if(removed!=null)trashed=removed;boolean foldersChanged=nextFolders!=null&&!sameFolders(folders,nextFolders);if(nextFolders!=null)folders=nextFolders;
                if(screen.equals("library")){if(foldersChanged)render();else renderLibraryRows();}else if(screen.equals("home")||screen.equals("trash")){render();}
                if(screen.equals("trash")&&removed==null)showError("Trash could not be loaded. Check available storage and try again.");
                else if((screen.equals("home")||screen.equals("library"))&&active==null)showError("Your PDF library could not be loaded. Check available storage and reopen the app.");
            });
        });
    }
    private void loadPreview(PageItem page,ImageView target) {
        PageItem snapshot=new PageItem(page.path);snapshot.rotation=page.rotation;snapshot.filter=page.filter;String key=page.path+page.rotation+page.filter;target.setTag(key);
        final int generation=previewGeneration;
        previews.execute(()->{if(isDestroyed()||Thread.currentThread().isInterrupted()||generation!=previewGeneration)return;try{Bitmap bitmap=ImageLoader.load(snapshot,280);runOnUiThread(()->{if(!isDestroyed()&&generation==previewGeneration&&key.equals(target.getTag())&&target.isAttachedToWindow())target.setImageBitmap(bitmap);else bitmap.recycle();});}catch(Exception|OutOfMemoryError e){runOnUiThread(()->{if(!isDestroyed()&&generation==previewGeneration)target.setContentDescription("Preview unavailable; original page is retained");});}});
    }
    private boolean validDraftPath(String path) {try{return new File(path).getCanonicalPath().startsWith(new File(getFilesDir(),"draft-pages").getCanonicalPath()+File.separator);}catch(Exception e){return false;}}
    private void deleteDraftImage(String path) {if(validDraftPath(path))new File(path).delete();}
    private boolean persistDraft() {
        try{writeDraft(pages);return true;}
        catch(Exception e){showError("Your draft could not be saved. Check device storage before closing the app.");return false;}
    }
    private void writeDraft(List<PageItem> draft) throws Exception {
        synchronized(DRAFT_LOCK){
        if(destroyed||Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Draft operation cancelled");
        JSONArray array=new JSONArray();for(PageItem p:draft){JSONObject item=new JSONObject();item.put("id",p.id);item.put("path",p.path);item.put("rotation",p.rotation);item.put("filter",p.filter.name());array.put(item);}
        AtomicFile target=new AtomicFile(new File(getFilesDir(),"draft.json"));FileOutputStream out=null;
        byte[] encoded=array.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try{out=target.startWrite();out.write(encoded);out.getFD().sync();target.finishWrite(out);out=null;
            if(!java.util.Arrays.equals(encoded,target.readFully()))throw new java.io.IOException("Draft persistence could not be verified");}
        catch(Exception e){if(out!=null)target.failWrite(out);throw e;}
        }
    }
    private ArrayList<PageItem> loadDraft() throws Exception {
        ArrayList<PageItem> restored=new ArrayList<>();
        AtomicFile target=new AtomicFile(new File(getFilesDir(),"draft.json"));if(!target.getBaseFile().exists()&&!new File(getFilesDir(),"draft.json.bak").exists())return restored;
        byte[] bytes;synchronized(DRAFT_LOCK){bytes=target.readFully();}if(bytes.length>1024*1024)throw new Exception("Draft metadata too large");
        JSONArray array=new JSONArray(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));for(int i=0;i<Math.min(array.length(),PAGE_LIMIT);i++){JSONObject item=array.getJSONObject(i);String path=item.getString("path");if(!validDraftPath(path)||!new File(path).isFile())continue;PageItem p=new PageItem(path);p.id=item.optString("id",p.id);p.rotation=(((item.optInt("rotation",0)%360)+360)%360)/90*90;try{p.filter=PageItem.Filter.valueOf(item.optString("filter","ORIGINAL"));}catch(Exception ignored){}restored.add(p);}
        return restored;
    }

    /** Small vector symbols keep the app crisp without a large icon dependency. */
    private class IconView extends View {
        private final String icon;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        IconView(String name,int color){super(MainActivity.this);icon=name;paint.setColor(color);paint.setStrokeWidth(1.7f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStyle(Paint.Style.STROKE);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);canvas.save();float scale=Math.min(getWidth(),getHeight())/24f;canvas.translate((getWidth()-24*scale)/2,(getHeight()-24*scale)/2);canvas.scale(scale,scale);Path p=new Path();
            if(icon.equals("leaf")){p.moveTo(5,19);p.cubicTo(2,8,9,3,20,4);p.cubicTo(21,14,16,21,5,19);canvas.drawPath(p,paint);canvas.drawLine(5,19,15,9,paint);canvas.drawLine(10,14,10,9,paint);}
            else if(icon.equals("image")){canvas.drawRoundRect(3,4,21,20,3,3,paint);canvas.drawCircle(8,9,1.5f,paint);p.moveTo(3,17);p.lineTo(9,12);p.lineTo(13,16);p.lineTo(17,11);p.lineTo(21,15);canvas.drawPath(p,paint);}
            else if(icon.equals("scan")){canvas.drawLine(3,8,3,3,paint);canvas.drawLine(3,3,8,3,paint);canvas.drawLine(16,3,21,3,paint);canvas.drawLine(21,3,21,8,paint);canvas.drawLine(3,16,3,21,paint);canvas.drawLine(3,21,8,21,paint);canvas.drawLine(16,21,21,21,paint);canvas.drawLine(21,21,21,16,paint);canvas.drawRoundRect(7,6,17,18,1,1,paint);canvas.drawLine(1,12,23,12,paint);}
            else if(icon.equals("home")){p.moveTo(3,11);p.lineTo(12,3);p.lineTo(21,11);p.moveTo(5,10);p.lineTo(5,21);p.lineTo(10,21);p.lineTo(10,15);p.lineTo(14,15);p.lineTo(14,21);p.lineTo(19,21);p.lineTo(19,10);canvas.drawPath(p,paint);}
            else if(icon.equals("library")){canvas.drawRoundRect(3,4,21,20,2,2,paint);canvas.drawLine(8,4,8,20,paint);canvas.drawLine(12,9,17,9,paint);canvas.drawLine(12,13,17,13,paint);}
            else{p.moveTo(6,2);p.lineTo(14,2);p.lineTo(19,7);p.lineTo(19,22);p.lineTo(5,22);p.lineTo(5,2);p.moveTo(14,2);p.lineTo(14,7);p.lineTo(19,7);canvas.drawPath(p,paint);canvas.drawLine(8,12,16,12,paint);canvas.drawLine(8,16,14,16,paint);}
            canvas.restore();}
    }
}
