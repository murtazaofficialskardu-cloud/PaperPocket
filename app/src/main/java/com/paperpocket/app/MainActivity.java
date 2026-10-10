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

/** Native, local-first document workspace. All image/PDF work happens off the UI thread. */
public class MainActivity extends Activity {
    private static final int IMPORT = 100, SCAN = 101, CAMERA = 102, CROP = 103, EXPORT = 104;
    private static final int PAGE_LIMIT = 100;
    private static final int GREEN = Color.rgb(23,77,57), BG = Color.rgb(247,248,244);
    private static final int INK = Color.rgb(32,43,36), MUTED = Color.rgb(102,116,106);
    private static final int LINE = Color.rgb(222,229,221), PALE = Color.rgb(230,238,228);
    private static final Object DRAFT_LOCK = new Object();
    private final ArrayList<PageItem> pages = new ArrayList<>();
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final ExecutorService previews = Executors.newSingleThreadExecutor();
    private List<PdfRecord> documents = new ArrayList<>();
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
    private SharedPreferences preferences;
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
            ArrayList<Bundle> pending = state.getParcelableArrayList("pending_results");
            if(pending!=null) for(Bundle item:pending) pendingResults.add(new PendingResult(item.getInt("request"),item.getInt("result"),item.getParcelable("data")));
        }
        render();
        work.execute(() -> {
            String failure = null;
            ArrayList<PageItem> restored=new ArrayList<>();
            try { restored=loadDraft(); } catch (Exception e) { failure = "Your saved draft could not be restored. Your PDFs are still safe."; }
            final String message = failure;
            final ArrayList<PageItem> restoredPages=restored;
            runOnUiThread(() -> {
                if (isDestroyed()) return; pages.clear();pages.addAll(restoredPages);loading = false; render();
                if (message != null) showError(message); refreshDocuments();
                ArrayList<PendingResult> ready = new ArrayList<>(pendingResults); pendingResults.clear();
                for (PendingResult result : ready) handleActivityResult(result.request,result.result,result.data);
            });
        });
    }

    @Override protected void onResume() { super.onResume(); if (store != null && !loading) refreshDocuments(); }
    @Override public void onConfigurationChanged(Configuration configuration) { super.onConfigurationChanged(configuration); render(); }
    @Override public void onSaveInstanceState(Bundle state) {
        state.putString("screen", screen); state.putString("query", query); state.putBoolean("alphabetical", alphabetical);
        ArrayList<Bundle> pending=new ArrayList<>();
        for(PendingResult result:pendingResults){Bundle item=new Bundle();item.putInt("request",result.request);item.putInt("result",result.result);item.putParcelable("data",result.data);pending.add(item);}
        state.putParcelableArrayList("pending_results",pending);
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        destroyed=true;
        if (progress != null) progress.dismiss();
        work.shutdownNow(); previews.shutdownNow();
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
        if (!screen.equals("home")) { screen = "home"; render(); } else finish();
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
        parent.addView(button(title,primary,action),new LinearLayout.LayoutParams(-1,dp(50)));
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
        TextView name = text("  PaperPocket",21,INK,true); header.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        TextView badge = text("ON DEVICE",10,GREEN,true); badge.setPadding(dp(10),dp(7),dp(10),dp(7)); badge.setBackground(shape(PALE,30)); header.addView(badge);
        root.addView(header);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        body = column(); body.setPadding(dp(22),dp(6),dp(22),dp(24)); scroll.addView(body);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if (loading) { gap(body,50); body.addView(text("Getting your workspace ready…",17,MUTED,false)); }
        else if (screen.equals("library")) renderLibrary(); else if (screen.equals("draft")) renderDraft(); else renderHome();
        bottom = row(); bottom.setPadding(dp(16),dp(10),dp(16),dp(12)); bottom.setBackgroundColor(Color.WHITE);
        nav("home","Home","home"); nav("draft","Workspace"+(pages.isEmpty()?"":" · "+pages.size()),"image"); nav("library","My PDFs","library");
        root.addView(bottom);
    }
    private void nav(String destination,String label,String icon) {
        LinearLayout item = column(); item.setGravity(Gravity.CENTER); item.setPadding(dp(2),dp(6),dp(2),dp(6));
        boolean selected = screen.equals(destination); item.setBackground(shape(selected ? PALE : Color.WHITE,14));
        item.addView(new IconView(icon,selected?GREEN:MUTED),new LinearLayout.LayoutParams(dp(24),dp(24)));
        gap(item,4); item.addView(text(label,11,selected?GREEN:MUTED,selected)); item.setContentDescription(label);
        item.setOnClickListener(v -> { if(loading||busy)return; screen=destination; render(); });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,dp(62),1); p.setMargins(dp(3),0,dp(3),0); bottom.addView(item,p);
    }
    private void renderHome() {
        gap(body,14); body.addView(text("A LITTLE LESS PAPER",11,GREEN,true)); gap(body,12);
        body.addView(text("Your paper.\nSimplified.",38,INK,true)); gap(body,12);
        TextView intro=text("Turn photos and everyday documents into crisp, shareable PDFs.",16,MUTED,false); intro.setLineSpacing(dp(4),1); body.addView(intro); gap(body,26);
        LinearLayout actions=row();
        actionCard(actions,"image","Import images","From your gallery",v->importImages());
        actionCard(actions,"scan","Scan document","Capture every detail",v->startScanner());
        body.addView(actions); gap(body,22);
        if (!pages.isEmpty()) {
            LinearLayout draft=card(); LinearLayout heading=row(); heading.addView(text("Pick up where you left off",17,INK,true),new LinearLayout.LayoutParams(0,-2,1));
            heading.addView(text(String.valueOf(pages.size()),18,GREEN,true)); draft.addView(heading); gap(draft,8);
            draft.addView(text(pages.size()+" page"+(pages.size()==1?"":"s")+" ready to arrange and convert",14,MUTED,false)); gap(draft,16);
            fullButton(draft,"Continue draft  →",true,v->{screen="draft";render();}); body.addView(draft); gap(body,24);
        } else {
            LinearLayout note=card(); note.setBackground(shape(PALE,20)); note.addView(text("Good scans. Small effort.",18,GREEN,true)); gap(note,10);
            TextView t=text("Automatic edge detection, straightening and cleanup. Finish with a high-quality, 300 DPI PDF.",14,GREEN,false); t.setLineSpacing(dp(3),1); note.addView(t); body.addView(note); gap(body,24);
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
        gap(body,10); body.addView(text("Your workspace",30,INK,true)); gap(body,8); body.addView(text("One photo, one page. Make it yours.",15,MUTED,false)); gap(body,20);
        LinearLayout add=row(); Button importButton=button("＋ Images",false,v->importImages()); Button scanButton=button("＋ Scan",false,v->startScanner());
        LinearLayout.LayoutParams a=new LinearLayout.LayoutParams(0,dp(48),1);a.setMargins(0,0,dp(6),0); add.addView(importButton,a);
        LinearLayout.LayoutParams b=new LinearLayout.LayoutParams(0,dp(48),1);b.setMargins(dp(6),0,0,0);add.addView(scanButton,b);body.addView(add);gap(body,20);
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
            move.addView(up,new LinearLayout.LayoutParams(dp(50),dp(48)));LinearLayout.LayoutParams small=new LinearLayout.LayoutParams(dp(50),dp(48));small.setMargins(dp(6),0,0,0);move.addView(down,small);
            LinearLayout.LayoutParams small2=new LinearLayout.LayoutParams(dp(50),dp(48));small2.setMargins(dp(6),0,0,0);move.addView(remove,small2);info.addView(move);top.addView(info,new LinearLayout.LayoutParams(0,-2,1));c.addView(top);gap(c,12);
            LinearLayout tools=row();miniTool(tools,"Crop",v->crop(page));miniTool(tools,"Rotate",v->{int before=page.rotation;page.rotation=(page.rotation+90)%360;if(!persistDraft())page.rotation=before;render();});miniTool(tools,"Filter",v->chooseFilter(page));c.addView(tools);body.addView(c);gap(body,12);
        }
        gap(body,8);fullButton(body,"Create PDF  ·  "+pages.size()+" page"+(pages.size()==1?"":"s"),true,v->conversionOptions());gap(body,12);body.addView(text("Choose your paper size and quality in the next step.",12,MUTED,false));
    }
    private void miniTool(LinearLayout tools,String label,View.OnClickListener action) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1);p.setMargins(dp(3),0,dp(3),0);tools.addView(button(label,false,action),p); }
    private String filterLabel(PageItem.Filter filter) { return filter==PageItem.Filter.ENHANCED?"Enhanced clarity":filter==PageItem.Filter.BLACK_WHITE?"Black & white":"Original color"; }
    private void movePage(int index,int offset) { Collections.swap(pages,index,index+offset);if(!persistDraft())Collections.swap(pages,index,index+offset);render(); }
    private void removePage(PageItem page) { int index=pages.indexOf(page);if(index<0)return;pages.remove(index);if(persistDraft())deleteDraftImage(page.path);else pages.add(index,page);render(); }
    private void chooseFilter(PageItem page) { String[] labels={"Original color","Enhanced clarity","Black & white"};new AlertDialog.Builder(this).setTitle("Page filter").setSingleChoiceItems(labels,page.filter.ordinal(),(d,which)->{PageItem.Filter before=page.filter;page.filter=PageItem.Filter.values()[which];if(!persistDraft())page.filter=before;render();d.dismiss();}).setNegativeButton("Cancel",null).show(); }
    private void confirmClear() { new AlertDialog.Builder(this).setTitle("Clear this draft?").setMessage("The images in this draft will be removed. Your generated PDFs stay in My PDFs.").setNegativeButton("Keep draft",null).setPositiveButton("Clear",(d,w)->clearDraft()).show(); }
    private void clearDraft() { ArrayList<PageItem> old=new ArrayList<>(pages);pages.clear();if(persistDraft()){for(PageItem page:old)deleteDraftImage(page.path);}else pages.addAll(old);render(); }

    private void renderLibrary() {
        gap(body,10);body.addView(text("My PDFs",30,INK,true));gap(body,8);libraryCount=text("",15,MUTED,false);updateLibraryCount();body.addView(libraryCount);gap(body,20);
        EditText search=new EditText(this);search.setSingleLine(true);search.setTextSize(15);search.setTextColor(INK);search.setHintTextColor(MUTED);search.setHint("Search your documents");search.setPadding(dp(16),dp(12),dp(16),dp(12));search.setBackground(shape(Color.WHITE,14));search.setText(query);
        body.addView(search,new LinearLayout.LayoutParams(-1,dp(52)));gap(body,10);LinearLayout sort=row();sort.addView(text("YOUR DOCUMENTS",11,MUTED,true),new LinearLayout.LayoutParams(0,-2,1));
        TextView ordering=text(alphabetical?"Name A–Z ↓":"Newest first ↓",13,GREEN,true);ordering.setPadding(dp(8),dp(14),0,dp(14));ordering.setOnClickListener(v->{alphabetical=!alphabetical;ordering.setText(alphabetical?"Name A–Z ↓":"Newest first ↓");renderLibraryRows();});sort.addView(ordering);body.addView(sort);
        libraryList=column();body.addView(libraryList);renderLibraryRows();search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){query=s.toString();renderLibraryRows();}public void afterTextChanged(Editable e){}});
    }
    private void renderLibraryRows() {
        updateLibraryCount();
        if(libraryList==null)return;libraryList.removeAllViews();ArrayList<PdfRecord> visible=new ArrayList<>();for(PdfRecord r:documents)if(r.name.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))visible.add(r);
        if(alphabetical)visible.sort(Comparator.comparing(r->r.name.toLowerCase(Locale.ROOT)));
        if(visible.isEmpty()){gap(libraryList,24);LinearLayout empty=card();empty.addView(text(documents.isEmpty()?"A home for your PDFs":"No matching documents",21,INK,true));gap(empty,10);empty.addView(text(documents.isEmpty()?"Create your first PDF from images or a scan. It will be saved here automatically.":"Try searching with a different name.",15,MUTED,false));libraryList.addView(empty);}
        else for(PdfRecord r:visible){libraryList.addView(documentCard(r));gap(libraryList,10);}
    }
    private void updateLibraryCount() {if(libraryCount!=null)libraryCount.setText(documents.size()+" document"+(documents.size()==1?"":"s")+" · stored on this device");}
    private LinearLayout documentCard(PdfRecord record) {
        LinearLayout c=row();c.setPadding(dp(14),dp(16),dp(10),dp(16));c.setBackground(shape(Color.WHITE,16));FrameLayout tile=new FrameLayout(this);tile.setBackground(shape(PALE,12));tile.addView(new IconView("pdf",GREEN),new FrameLayout.LayoutParams(dp(28),dp(30),Gravity.CENTER));c.addView(tile,new LinearLayout.LayoutParams(dp(48),dp(56)));
        LinearLayout label=column();label.setPadding(dp(14),0,dp(6),0);TextView title=text(record.name,16,INK,true);title.setMaxLines(2);label.addView(title);gap(label,7);
        String details=record.pageCount+" page"+(record.pageCount==1?"":"s")+" · "+size(record.sizeBytes)+"\n"+DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(record.createdAt));
        TextView metadata=text(details,12,MUTED,false);metadata.setLineSpacing(dp(3),1);label.addView(metadata);c.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        TextView menu=text("⋮",28,MUTED,true);menu.setGravity(Gravity.CENTER);menu.setContentDescription("Actions for "+record.name);menu.setOnClickListener(v->documentActions(record));c.addView(menu,new LinearLayout.LayoutParams(dp(48),dp(56)));
        c.setOnClickListener(v->openPdf(record));c.setContentDescription("Open "+record.name+", "+record.pageCount+" pages");return c;
    }
    private String size(long bytes) { return bytes<1024*1024?String.format(Locale.US,"%.0f KB",bytes/1024.0):String.format(Locale.US,"%.1f MB",bytes/(1024.0*1024)); }
    private void documentActions(PdfRecord r) {new AlertDialog.Builder(this).setTitle(r.name).setItems(new String[]{"View PDF","Share","Save a copy","Rename","Delete"},(dialog,which)->{switch(which){case 0:openPdf(r);break;case 1:share(r);break;case 2:export(r);break;case 3:rename(r);break;case 4:delete(r);break;}}).show();}
    private void openPdf(PdfRecord r) {startActivity(new Intent(this,PdfViewerActivity.class).putExtra("path",r.file.getAbsolutePath()).putExtra("name",r.name));}
    private void share(PdfRecord r) {try{Uri uri=store.uri(r);Intent intent=new Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri(r.name,uri));startActivity(Intent.createChooser(intent,"Share PDF"));}catch(Exception e){showError("No app is available to share this PDF.");}}
    private void rename(PdfRecord r) {
        EditText name=new EditText(this);name.setSingleLine(true);name.setText(r.name.replaceFirst("(?i)\\.pdf$",""));name.setSelectAllOnFocus(true);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Rename PDF").setView(name).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{if(name.getText().toString().trim().isEmpty()){name.setError("Enter a name");return;}final String requestedName=name.getText().toString();dialog.dismiss();beginWork("Renaming PDF…");work.execute(()->{try{store.rename(r,requestedName);runOnUiThread(()->{endWork();refreshDocuments();});}catch(Exception e){failure("Could not rename this PDF.",e);}});}));dialog.show();
    }
    private void delete(PdfRecord r) {new AlertDialog.Builder(this).setTitle("Delete "+r.name+"?").setMessage("This removes the PDF from this app. Any copies you exported or shared are kept.").setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->{beginWork("Deleting PDF…");work.execute(()->{try{store.delete(r);runOnUiThread(()->{endWork();refreshDocuments();});}catch(Exception e){failure("Could not delete this PDF.",e);}});}).show();}
    private void export(PdfRecord r) {preferences.edit().putString("export_path",r.file.getAbsolutePath()).apply();Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf").putExtra(Intent.EXTRA_TITLE,r.name);try{startActivityForResult(i,EXPORT);}catch(Exception e){showError("Your device has no file picker to save a copy.");}}

    private void importImages() {
        if(loading||busy)return;if(pages.size()>=PAGE_LIMIT){showError("A PDF can have up to 100 pages. Convert this draft to start another.");return;}
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        try{startActivityForResult(intent,IMPORT);}catch(Exception e){showError("Your device has no image picker. Try scanning a document instead.");}
    }
    private void startScanner() {
        if(loading||busy)return;if(pages.size()>=PAGE_LIMIT){showError("This draft already has 100 pages.");return;}
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
    private void crop(PageItem page) {if(page.rotation!=0){new AlertDialog.Builder(this).setTitle("Crop original image?").setMessage("Crop works on the original orientation. Your rotation and filter will be kept when you finish.").setNegativeButton("Cancel",null).setPositiveButton("Crop",(d,w)->launchCrop(page)).show();}else launchCrop(page);}
    private void launchCrop(PageItem page) {preferences.edit().putString("crop_id",page.id).apply();startActivityForResult(new Intent(this,CropActivity.class).putExtra("path",page.path),CROP);}

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(loading){pendingResults.add(new PendingResult(request,result,data));return;}
        handleActivityResult(request,result,data);
    }
    private void handleActivityResult(int request,int result,Intent data) {
        if(request==CAMERA) {
            String path=preferences.getString("camera_path",null);preferences.edit().remove("camera_path").apply();
            if(path!=null){File file=new File(path);if(result==RESULT_OK&&file.length()>0){ArrayList<Uri> uris=new ArrayList<>();uris.add(Uri.fromFile(file));importUris(uris,true,file);}else file.delete();}return;
        }
        if(result!=RESULT_OK)return;
        if(request==IMPORT&&data!=null){ArrayList<Uri> uris=new ArrayList<>();if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());}else if(data.getData()!=null)uris.add(data.getData());importUris(uris,false,null);}
        else if(request==SCAN&&data!=null){GmsDocumentScanningResult scan=GmsDocumentScanningResult.fromActivityResultIntent(data);if(scan!=null&&scan.getPages()!=null){ArrayList<Uri> uris=new ArrayList<>();for(GmsDocumentScanningResult.Page p:scan.getPages())uris.add(p.getImageUri());importUris(uris,false,null);}else showError("The scanner returned no pages. Please try again.");}
        else if(request==CROP&&data!=null){String id=preferences.getString("crop_id","");String path=data.getStringExtra("path");if(path!=null&&validDraftPath(path)){boolean used=false;for(PageItem page:pages)if(page.id.equals(id)){String old=page.path;page.path=path;if(persistDraft()){deleteDraftImage(old);used=true;}else page.path=old;break;}if(!used)deleteDraftImage(path);render();}preferences.edit().remove("crop_id").apply();}
        else if(request==EXPORT&&data!=null&&data.getData()!=null){String path=preferences.getString("export_path",null);Uri destination=data.getData();if(path==null)return;beginWork("Saving a copy…");work.execute(()->{try{File source=new File(path);if(!source.getCanonicalPath().startsWith(new File(getFilesDir(),"pdfs").getCanonicalPath()+File.separator))throw new Exception("Invalid document");try(InputStream in=new FileInputStream(source);OutputStream out=getContentResolver().openOutputStream(destination,"wt")){if(out==null)throw new Exception("Destination unavailable");copy(in,out,Long.MAX_VALUE);}runOnUiThread(()->{endWork();toast("PDF copy saved");});}catch(Exception e){failure("Could not save this copy. Choose another location.",e);}finally{preferences.edit().remove("export_path").apply();}});}
    }
    private void importUris(List<Uri> uris,boolean enhance,File cleanup) {
        if(uris.isEmpty())return;int available=Math.max(0,PAGE_LIMIT-pages.size());List<Uri> accepted=new ArrayList<>(uris.subList(0,Math.min(available,uris.size())));
        if(accepted.isEmpty()){if(cleanup!=null)cleanup.delete();showError("This draft already has 100 pages. Create a PDF before importing more.");return;}
        ArrayList<PageItem> restored=new ArrayList<>();for(PageItem page:pages)restored.add(page.copy());beginWork("Importing images…");
        work.execute(()->{ArrayList<PageItem> added=new ArrayList<>();int failed=0;File folder=new File(getFilesDir(),"draft-pages");folder.mkdirs();
            for(int i=0;i<accepted.size();i++){if(destroyed||Thread.currentThread().isInterrupted())break;File file=new File(folder,UUID.randomUUID()+".img");try{Uri uri=accepted.get(i);try(InputStream input=getContentResolver().openInputStream(uri);OutputStream output=new FileOutputStream(file)){if(input==null)throw new Exception("Image unavailable");copy(input,output,80L*1024*1024);}
                    BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);if(bounds.outWidth<=0||bounds.outHeight<=0)throw new Exception("Unsupported image");
                    PageItem page=new PageItem(file.getAbsolutePath());if(enhance)page.filter=PageItem.Filter.ENHANCED;added.add(page);
                }catch(Exception e){failed++;file.delete();}int done=i+1;runOnUiThread(()->{if(progress!=null)progress.setMessage("Importing image "+done+" of "+accepted.size()+"…");});}
            if(cleanup!=null)cleanup.delete();final int errors=failed;final boolean limited=accepted.size()<uris.size();
            restored.addAll(added);
            try{writeDraft(restored);}catch(Exception e){for(PageItem page:added)deleteDraftImage(page.path);failure("The imported images could not be saved. Check device storage and try again.",e);return;}
            runOnUiThread(()->{if(isDestroyed())return;pages.addAll(added);endWork();screen="draft";render();if(errors>0)showError(errors+" image"+(errors==1?"":"s")+" could not be imported. Use JPEG, PNG, WebP, or a format supported by your device, under 80 MB per image.");else if(limited)toast("Imported up to the 100-page limit");else toast(added.size()+" page"+(added.size()==1?"":"s")+" added");});
        });
    }
    private static void copy(InputStream input,OutputStream output,long maximum) throws Exception {byte[] buffer=new byte[32768];long count=0;int read;while((read=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Operation cancelled");count+=read;if(count>maximum)throw new Exception("File is too large");output.write(buffer,0,read);}output.flush();}

    private void conversionOptions() {
        if(pages.isEmpty())return;LinearLayout options=column();options.setPadding(dp(22),dp(10),dp(22),dp(6));options.addView(text("Document name",13,MUTED,true));gap(options,6);
        EditText name=new EditText(this);name.setSingleLine(true);name.setText("Document "+new SimpleDateFormat("yyyy-MM-dd HHmm",Locale.US).format(new Date()));name.setTextColor(INK);options.addView(name,new LinearLayout.LayoutParams(-1,dp(52)));gap(options,14);
        options.addView(text("Paper size",13,MUTED,true));Spinner paper=spinner(new String[]{"A4","Letter","Fit to image"});paper.setSelection(preferences.getInt("paper",0));options.addView(paper,new LinearLayout.LayoutParams(-1,dp(48)));gap(options,12);
        options.addView(text("Image quality",13,MUTED,true));Spinner quality=spinner(new String[]{"High · 300 DPI","Standard · 150 DPI"});quality.setSelection(preferences.getInt("quality",0));options.addView(quality,new LinearLayout.LayoutParams(-1,dp(48)));gap(options,10);
        CheckBox margin=new CheckBox(this);margin.setText("Add a small page margin");margin.setTextColor(INK);margin.setButtonTintList(ColorStateList.valueOf(GREEN));margin.setChecked(preferences.getBoolean("margin",true));options.addView(margin);gap(options,10);
        options.addView(text("High quality preserves more detail and creates larger files. Image quality depends on the original photo.",12,MUTED,false));
        ScrollView optionsScroll=new ScrollView(this);optionsScroll.addView(options);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Create your PDF").setView(optionsScroll).setNegativeButton("Cancel",null).setPositiveButton("Create PDF",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{String title=name.getText().toString().trim();if(title.isEmpty()){name.setError("Enter a document name");return;}
            PdfConverter.Options settings=new PdfConverter.Options();settings.name=title;settings.dpi=quality.getSelectedItemPosition()==0?300:150;settings.paper=new String[]{"A4","LETTER","AUTO"}[paper.getSelectedItemPosition()];settings.margin=margin.isChecked();preferences.edit().putInt("paper",paper.getSelectedItemPosition()).putInt("quality",quality.getSelectedItemPosition()).putBoolean("margin",margin.isChecked()).apply();dialog.dismiss();convert(settings);}));dialog.show();
    }
    private Spinner spinner(String[] options) {Spinner s=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,options);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(adapter);return s;}
    private void convert(PdfConverter.Options options) {
        ArrayList<PageItem> selected=new ArrayList<>();for(PageItem page:pages)selected.add(page.copy());beginWork("Creating PDF…");work.execute(()->{File temporary=null;try{
            temporary=PdfConverter.convert(this,selected,options,(done,total)->runOnUiThread(()->{if(progress!=null)progress.setMessage("Creating page "+done+" of "+total+"…");}));
            PdfRecord saved=store.save(temporary,options.name,selected.size());runOnUiThread(()->{if(isDestroyed())return;endWork();clearDraft();screen="library";refreshDocuments();render();
                new AlertDialog.Builder(this).setTitle("Your PDF is ready").setMessage(saved.name+"\n"+saved.pageCount+" pages · "+size(saved.sizeBytes)+"\nSaved in My PDFs.").setNegativeButton("Done",null).setNeutralButton("Share",(d,w)->share(saved)).setPositiveButton("View PDF",(d,w)->openPdf(saved)).show();});
        }catch(OutOfMemoryError e){failure("There was not enough memory to create this PDF. Try standard quality or fewer pages.",e);}catch(Exception e){failure("Could not create your PDF. Your draft is kept; check free storage and try again.",e);}finally{if(temporary!=null)temporary.delete();}});
    }
    private void beginWork(String message) {busy=true;progress=new ProgressDialog(this);progress.setMessage(message);progress.setIndeterminate(true);progress.setCancelable(false);progress.show();}
    private void endWork() {busy=false;if(progress!=null){progress.dismiss();progress=null;}}
    private void failure(String message,Throwable e) {runOnUiThread(()->{if(isDestroyed())return;endWork();showError(message);});}
    private void showError(String message) {if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle("PaperPocket").setMessage(message).setPositiveButton("OK",null).show();}
    private void toast(String message) {if(!isDestroyed())Toast.makeText(this,message,Toast.LENGTH_SHORT).show();}
    private void refreshDocuments() {
        if(work.isShutdown())return;final int generation=++libraryGeneration;work.execute(()->{try{List<PdfRecord> records=store.list();runOnUiThread(()->{if(isDestroyed()||generation!=libraryGeneration)return;documents=records;if(screen.equals("library")){renderLibraryRows();}else if(screen.equals("home")){render();}});}catch(Exception e){runOnUiThread(()->{if(!isDestroyed())showError("Your PDF library could not be loaded. Check available storage and reopen the app.");});}});
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
