package org.pocketarcade.madeira;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.widget.*;
import android.view.ViewGroup;
import java.io.*;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
 static { System.loadLibrary("madeira_probe"); }
 private static native String nativeProbe();
 private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
 private TextView report;
 private static final int PICK_EXE = 22;
 @Override public void onCreate(Bundle state) {
  super.onCreate(state);
  LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(28,48,28,28);
  TextView heading = new TextView(this); heading.setText("Madeira Android · Runtime Lab"); heading.setTextSize(24); layout.addView(heading);
  TextView warning = new TextView(this); warning.setText("Prototype: native ARM64 probe only. Wine/FEX/DXVK not bundled; Windows EXEs cannot run yet.");layout.addView(warning);
  Button probe = new Button(this); probe.setText("Run native diagnostics"); layout.addView(probe);
  Button pick = new Button(this); pick.setText("Inspect Windows EXE");layout.addView(pick);
  report = new TextView(this);report.setTextIsSelectable(true);layout.addView(report);
  ScrollView scroll = new ScrollView(this); scroll.addView(layout);setContentView(scroll);
  probe.setOnClickListener(v -> runProbe());
  pick.setOnClickListener(v -> { Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.setType("*/*"); intent.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(intent,PICK_EXE); });
  runProbe();
 }
 private void runProbe(){
  report.setText("Running checks…");
  worker.execute(() -> {
   StringBuilder out = new StringBuilder();
   out.append(nativeProbe()).append("\n");
   out.append("Vulkan hardware: ").append(getPackageManager().hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)).append("\n");
   out.append("CPU ABI: ").append(android.os.Build.SUPPORTED_ABIS[0]).append("\n");
   // Android allows subprocesses subject to app sandbox restrictions; this tests a real second process.
   try {
    Process p = new ProcessBuilder("/system/bin/sh","-c","printf 'child-process-ok'").redirectErrorStream(true).start();
    byte[] bytes = p.getInputStream().readAllBytes(); int exit = p.waitFor();
    out.append("Subprocess: ").append(new String(bytes)).append(" (exit ").append(exit).append(")\n");
   } catch(Exception e){out.append("Subprocess unavailable: ").append(e).append("\n");}
   runOnUiThread(() -> report.setText(out.toString()));
  });
 }
 @Override protected void onActivityResult(int request,int result,Intent data){
  super.onActivityResult(request,result,data);
  if(request != PICK_EXE || result != RESULT_OK || data == null)return;
  Uri uri=data.getData();
  worker.execute(() -> {
   String verdict;
   try(InputStream in=getContentResolver().openInputStream(uri)){
    byte[] header=new byte[64];int n=in.read(header);
    boolean mz=n>=2 && header[0]=='M' && header[1]=='Z';
    verdict="Selected file: "+uri.getLastPathSegment()+"\nMZ executable signature: "+mz+"\nExecution unavailable until Wine/FEX runtime is ported.";
   }catch(Exception e){verdict="Unable to inspect: "+e.getMessage();}
   final String message=verdict;runOnUiThread(()->report.setText(message));
  });
 }
 @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
