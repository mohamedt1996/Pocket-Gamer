package org.pocketarcade.madeira;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.widget.*;
import android.view.SurfaceView;
import java.io.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
 static {System.loadLibrary("madeira_probe");}
 private static native String nativeProbe();
 private static native String nativeVulkanTest();
 private static native String nativeSurfaceTest(android.view.Surface surface);
 private static native String nativePresentTest(android.view.Surface surface);
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private static final int PICK_EXE=22,PICK_RUNTIME=23;
 private RuntimeManager runtime;
 private TextView report, status;
 private File selectedExe;
 @Override public void onCreate(Bundle state) {
  super.onCreate(state);runtime=new RuntimeManager(this);
  LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(28,48,28,28);
  TextView title=new TextView(this);title.setText("Madeira Android · Windows Execution Lab");title.setTextSize(23);layout.addView(title);
  status=new TextView(this);layout.addView(status);
  Button probe=new Button(this);probe.setText("Run Android diagnostics");layout.addView(probe);
  Button graphics=new Button(this);graphics.setText("Test real Vulkan GPU commands");layout.addView(graphics);
  SurfaceView preview=new SurfaceView(this);layout.addView(preview,new LinearLayout.LayoutParams(-1,240));
  Button wsi=new Button(this);wsi.setText("Test Android Vulkan surface");layout.addView(wsi);
  wsi.setOnClickListener(v->{if(!preview.getHolder().getSurface().isValid()){report.setText("Surface not ready");return;}work("Testing Android Vulkan WSI",()->nativeSurfaceTest(preview.getHolder().getSurface()));});
  Button present=new Button(this);present.setText("Render blue Vulkan swapchain frame");layout.addView(present);
  present.setOnClickListener(v->{if(!preview.getHolder().getSurface().isValid()){report.setText("Surface not ready");return;}work("Rendering Vulkan swapchain",()->nativePresentTest(preview.getHolder().getSurface()));});
  Button install=new Button(this);install.setText("Import Android ARM64 Wine/FEX runtime ZIP");layout.addView(install);
  Button select=new Button(this);select.setText("Choose Windows EXE");layout.addView(select);
  Button play=new Button(this);play.setText("Attempt Windows execution");layout.addView(play);
  report=new TextView(this);report.setTextIsSelectable(true);layout.addView(report);
  ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
  probe.setOnClickListener(v->runProbe());graphics.setOnClickListener(v->work("Running Vulkan GPU test",()->nativeVulkanTest()));install.setOnClickListener(v->pick(PICK_RUNTIME));select.setOnClickListener(v->pick(PICK_EXE));
  play.setOnClickListener(v->{if(selectedExe==null){report.setText("Choose a Windows EXE first.");return;}work("Launching Windows executable",()->runtime.run(selectedExe));});
  updateStatus();runProbe();
 }
 private void updateStatus(){status.setText("Runtime installed: "+runtime.ready()+" | EXE selected: "+(selectedExe!=null)+"\nRequires an Android ARM64 Wine/FEX runtime; none is bundled. A valid ZIP must contain bin/launcher.");}
 private void pick(int code){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,code);}
 private interface Task{String run() throws Exception;}
 private void work(String label,Task task){report.setText(label+"…");worker.execute(()->{String result;try{result=task.run();}catch(Exception e){result="FAILED: "+e.toString();}String output=result;runOnUiThread(()->{report.setText(output);updateStatus();});});}
 private void runProbe(){work("Running native diagnostics",()->{
  StringBuilder out=new StringBuilder(nativeProbe()).append("\n");
  out.append("Vulkan: ").append(getPackageManager().hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)).append("\n");
  out.append("ABI: ").append(android.os.Build.SUPPORTED_ABIS[0]).append("\n");
  Process p=new ProcessBuilder("/system/bin/sh","-c","printf child-process-ok").redirectErrorStream(true).start();
  out.append("Android subprocess: ").append(new String(p.getInputStream().readAllBytes())).append(" exit=").append(p.waitFor()).append("\n");
  out.append("Graphics DLL inventory: ").append(runtime.graphicsStatus()).append("\n");
  out.append("Wine/FEX runtime: ").append(runtime.ready()?runtime.launcher():"NOT INSTALLED");return out.toString();
 });}
 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
  if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
  if(request==PICK_RUNTIME)work("Importing runtime",()->runtime.install(this,uri));
  if(request==PICK_EXE)work("Copying executable",()->{selectedExe=runtime.copyExecutable(this,uri);return "Selected "+uri.getLastPathSegment()+"\nCopied to "+selectedExe+"\nPress Attempt Windows execution to launch through the imported runtime.";});
 }
 @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
