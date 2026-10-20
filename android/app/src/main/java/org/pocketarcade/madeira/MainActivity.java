package org.pocketarcade.madeira;

import android.app.*;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.widget.*;
import android.view.SurfaceView;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
 static {System.loadLibrary("madeira_probe");}
 private static native String nativeProbe();
 private static native String nativeVulkanTest();
 private static native String nativeSurfaceTest(android.view.Surface surface);
 private static native String nativePresentTest(android.view.Surface surface);
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private static final int PICK_GAME=22,PICK_RUNTIME=23,PICK_DEPENDENCY=24;
 private TextView report,status;
 private Spinner games;
 private ArrayList<JSONObject> entries=new ArrayList<>();
 private GameStore store;
 private PortRuntime port;
 private String dependencyGame;
 private boolean busy;
 private interface Task{String run() throws Exception;}
 private Button button(LinearLayout parent,String label,Runnable action){Button b=new Button(this);b.setText(label);parent.addView(b);b.setOnClickListener(v->{if(!busy)action.run();});return b;}
 @Override public void onCreate(Bundle state) {
  super.onCreate(state);store=new GameStore(this);port=new PortRuntime(this);
  LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(24,32,24,24);
  TextView title=new TextView(this);title.setText("Pocket Arcade · Madeira Android");title.setTextSize(24);layout.addView(title);
  status=new TextView(this);layout.addView(status);
  games=new Spinner(this);layout.addView(games);
  button(layout,"Add game EXE / installer / game ZIP",()->pick(PICK_GAME));
  button(layout,"Choose executable inside this game",this::chooseExecutable);
  button(layout,"Play",()->withGame(id->{JSONObject game=store.settings(id);launch(id,GameStore.command(new File(game.getString("exe"))));}));
  button(layout,"Add bundled runtime smoke test",()->new AlertDialog.Builder(this).setTitle("Runtime checks")
   .setItems(new String[]{"x64 CPU + threads + files","x86 CPU + threads + files","x64 D3D11 blue frame","x86 D3D11 blue frame","x64 D3D12 device","x86 D3D12 device"},(d,index)->work("Adding test",()->{port.installBundled();store.addSmoke(port.root(),index%2==0?"x64":"x86",index<2?"hello":index<4?"d3d11":"d3d12-device");return "Test added. Select it above and press Play.";})).show());
  button(layout,"Windows desktop",()->withGame(id->launch(id,"explorer.exe")));
  button(layout,"Graphics: Wine / DXVK / VKD3D",this::graphics);
  button(layout,"Runtimes & dependencies",this::dependencies);
  button(layout,"View last Wine log",()->withGame(id->work("Reading log",()->{
   File f=new File(store.directory(id),"wine.log");if(!f.isFile())return "No Wine log yet.";
   try(RandomAccessFile r=new RandomAccessFile(f,"r")){r.seek(Math.max(0,r.length()-128*1024));byte[] b=new byte[(int)Math.min(r.length(),128*1024)];r.readFully(b);return new String(b,java.nio.charset.StandardCharsets.UTF_8);}
  })));
  button(layout,"Prepare bundled runtime",()->work("Installing runtime",()->{port.installBundled();return "Bundled runtime installed and checksums verified. Game execution still requires device validation.";}));
  button(layout,"Native diagnostics",()->work("Diagnostics",()->nativeProbe()+"\nRuntime bundle: "+port.bundled()));
  button(layout,"Vulkan GPU clear",()->work("Vulkan",()->nativeVulkanTest()));
  SurfaceView preview=new SurfaceView(this);layout.addView(preview,new LinearLayout.LayoutParams(-1,220));
  button(layout,"Vulkan surface check",()->{if(preview.getHolder().getSurface().isValid())work("Vulkan surface",()->nativeSurfaceTest(preview.getHolder().getSurface()));});
  button(layout,"Vulkan blue-frame check",()->{if(preview.getHolder().getSurface().isValid())work("Vulkan presentation",()->nativePresentTest(preview.getHolder().getSurface()));});
  button(layout,"Legacy external runtime importer",()->pick(PICK_RUNTIME));
  report=new TextView(this);report.setTextIsSelectable(true);layout.addView(report);
  ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);refresh();
 }
 private void refresh(){
  String prior=selected();
  try{entries=store.list();ArrayList<String> names=new ArrayList<>();for(JSONObject j:entries)names.add(j.optString("title"));
   games.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));
   if(prior!=null)for(int i=0;i<entries.size();i++)if(prior.equals(entries.get(i).optString("id")))games.setSelection(i);
  }catch(Exception e){report.setText(e.toString());}
  status.setText(port.bundled()?"Runtime components bundled · experimental, device execution unverified":"Runtime bundle absent. Build the full CI workflow before launching Windows games.");
 }
 private String selected(){int i=games==null?-1:games.getSelectedItemPosition();return i>=0&&i<entries.size()?entries.get(i).optString("id"):null;}
 private interface GameAction{void run(String id)throws Exception;}
 private void withGame(GameAction action){String id=selected();if(id==null){report.setText("Add a game first.");return;}try{action.run(id);}catch(Exception e){report.setText(e.toString());}}
 private void launch(String id,String command){
  if(!port.bundled()){report.setText("This APK has no bundled runtime. Run the full Android CI build.");return;}
  Intent intent=new Intent();intent.setClassName(this,"org.winehq.wine.WineActivity");intent.putExtra("gameId",id);intent.putExtra("wineCommand",command);startActivity(intent);
 }
 private void graphics(){withGame(id->new AlertDialog.Builder(this).setTitle("Graphics for this game")
  .setItems(new String[]{"Wine built-in (basic apps)","DXVK (D3D 8–11)","DXVK + VKD3D-Proton (D3D 12)"},(d,index)->{
   try{JSONObject s=store.settings(id);s.put("graphics",new String[]{"wine","dxvk","vkd3d"}[index]);store.save(id,s);report.setText("Graphics saved. Stop and restart this game to apply.");}catch(Exception e){report.setText(e.toString());}
  }).show());}
 private void chooseExecutable(){withGame(id->{ArrayList<File> files=new ArrayList<>();collectExecutables(store.directory(id),files);String[] names=new String[files.size()];for(int i=0;i<files.size();i++)names[i]=files.get(i).getAbsolutePath().substring(store.directory(id).getAbsolutePath().length()+1);
  new AlertDialog.Builder(this).setTitle("Game executable").setItems(names,(d,index)->{try{JSONObject s=store.settings(id);s.put("exe",files.get(index).getAbsolutePath());store.save(id,s);report.setText("Selected "+names[index]);}catch(Exception e){report.setText(e.toString());}}).show();
 });}
 private void collectExecutables(File dir,ArrayList<File> out){File[] children=dir.listFiles();if(children==null)return;for(File f:children){if(f.isDirectory())collectExecutables(f,out);else if(f.getName().toLowerCase(Locale.ROOT).endsWith(".exe"))out.add(f);}}
 private void dependencies(){withGame(id->new AlertDialog.Builder(this).setTitle("Install into this game's prefix")
  .setItems(new String[]{"Visual C++ x64 (Microsoft)","Visual C++ x86 (Microsoft)","Import AIR / .NET / other EXE or MSI","Download installer from HTTPS URL"},(d,index)->{
   dependencyGame=id;
   if(index<2)download(id,"https://aka.ms/vc14/vc_redist."+(index==0?"x64":"x86")+".exe",false);
   else if(index==2)pick(PICK_DEPENDENCY);
   else {LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);EditText url=new EditText(this);url.setHint("https://vendor.example/installer.exe");box.addView(url);CheckBox msi=new CheckBox(this);msi.setText("MSI package");box.addView(msi);
    new AlertDialog.Builder(this).setTitle("Installer URL").setView(box).setPositiveButton("Download",(a,b)->download(id,url.getText().toString().trim(),msi.isChecked())).setNegativeButton("Cancel",null).show();}
  }).show());}
 private void download(String id,String url,boolean msi){work("Downloading installer",()->{File f=store.downloadDependency(id,url,msi);runOnUiThread(()->confirmInstaller(id,f));return "Downloaded "+f.getName()+". The installer will run in this game's prefix.";});}
 private void confirmInstaller(String id,File f){new AlertDialog.Builder(this).setTitle("Run installer for this game?").setMessage(f.getName()).setPositiveButton("Install",(d,w)->launch(id,GameStore.command(f))).setNegativeButton("Later",null).show();}
 private void pick(int code){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,code);}
 private void work(String label,Task task){if(busy)return;busy=true;report.setText(label+"…");worker.execute(()->{String result;try{result=task.run();}catch(Exception e){result="FAILED: "+e;}String output=result;runOnUiThread(()->{busy=false;if(!isDestroyed()){report.setText(output);refresh();}});});}
 @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
  if(req==PICK_GAME)work("Importing game",()->{String id=store.importGame(uri);return "Imported "+store.settings(id).getString("title")+". Select the game above; for ZIPs, choose its executable.";});
  if(req==PICK_RUNTIME)work("Importing legacy runtime",()->new RuntimeManager(this).install(this,uri));
  if(req==PICK_DEPENDENCY){String id=dependencyGame;work("Importing installer",()->{File f=store.importDependency(id,uri);runOnUiThread(()->confirmInstaller(id,f));return "Installer copied into this game's directory.";});}
 }
 @Override protected void onSaveInstanceState(Bundle s){super.onSaveInstanceState(s);s.putString("dependencyGame",dependencyGame);}
 @Override protected void onRestoreInstanceState(Bundle s){super.onRestoreInstanceState(s);dependencyGame=s.getString("dependencyGame");}
 @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
