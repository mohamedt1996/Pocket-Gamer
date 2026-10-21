package org.pocketarcade.madeira;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/** Bundled Wine runtime and independent game prefixes. No shell interpolation. */
public final class PortRuntime {
 private final Context context;
 public PortRuntime(Context c) { context=c.getApplicationContext(); }
 public File root() { return new File(context.getFilesDir(),"madeira-runtime-v2"); }
 public boolean bundled() { try(InputStream i=context.getAssets().open("madeira-runtime.sha256")){ return true; }catch(IOException e){ return false; } }
 private File gameDir(String id) throws IOException {
  if(id==null||!id.matches("[a-f0-9]{32}")) throw new IOException("Invalid game identifier");
  return new File(context.getFilesDir(),"games/"+id);
 }
 public File prefix(String id) throws IOException { return new File(gameDir(id),"prefix"); }
 private static String digest(InputStream in) throws Exception {
  MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;
  while((n=in.read(b))!=-1)md.update(b,0,n);
  StringBuilder out=new StringBuilder();for(byte v:md.digest())out.append(String.format(Locale.ROOT,"%02x",v&255));return out.toString();
 }
 private static void remove(File file) throws IOException {
  if(file.isDirectory()&&!Files.isSymbolicLink(file.toPath())) {File[] fs=file.listFiles();if(fs==null)throw new IOException("Cannot list "+file);for(File f:fs)remove(f);}
  if(file.exists()&&!file.delete())throw new IOException("Cannot remove "+file);
 }
 public void installBundled() throws Exception {
  // Cross-process lock: the launcher and Wine Activity may both initialize.
  File lock=new File(context.getFilesDir(),"runtime.lock");
  try(RandomAccessFile raf=new RandomAccessFile(lock,"rw");java.nio.channels.FileLock held=raf.getChannel().lock()) {
   String expected;try(InputStream in=context.getAssets().open("madeira-runtime.sha256")){expected=new String(Fs.readAll(in),StandardCharsets.UTF_8).trim();}
   File marker=new File(root(),".installed");
   if(marker.isFile()&&Fs.readText(marker.toPath()).equals(expected))return;
   File stage=new File(context.getFilesDir(),"runtime-v2-staging");remove(stage);if(!stage.mkdirs())throw new IOException("Cannot stage runtime");
   File backup=new File(context.getFilesDir(),"runtime-v2-backup");
   // Restore an interrupted activation before starting another one.
   if(!root().exists()&&backup.exists()&&!backup.renameTo(root()))throw new IOException("Cannot restore runtime backup");
   try {
    try(InputStream in=context.getAssets().open("madeira-runtime.zip")) {if(!digest(in).equals(expected))throw new IOException("Bundled ZIP checksum mismatch");}
    long total=0;int count=0;
    try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(context.getAssets().open("madeira-runtime.zip")))) {
     ZipEntry e;byte[] b=new byte[65536];
     while((e=zip.getNextEntry())!=null) {
      if(++count>80000)throw new IOException("Too many runtime files");
      String name=e.getName();File dest=new File(stage,name);
      if(name.contains("\\")||name.startsWith("/")||!dest.getCanonicalPath().startsWith(stage.getCanonicalPath()+"/"))throw new IOException("Invalid runtime path");
      if(e.isDirectory()){if(!dest.isDirectory()&&!dest.mkdirs())throw new IOException("Cannot create runtime directory");continue;}
      if(!dest.getParentFile().isDirectory()&&!dest.getParentFile().mkdirs())throw new IOException("Cannot create runtime parent");
      try(OutputStream out=new FileOutputStream(dest)){int n;while((n=zip.read(b))!=-1){total+=n;if(total>3L*1024*1024*1024)throw new IOException("Runtime too large");out.write(b,0,n);}}
      if(!dest.setExecutable(true,true))throw new IOException("Cannot set runtime permissions");
     }
    }
    JSONObject manifest=new JSONObject(Fs.readText(new File(stage,"runtime.json").toPath()));
    if(manifest.getInt("schema")!=2||!manifest.getString("abi").equals("arm64-v8a"))throw new IOException("Unsupported runtime format");
    JSONObject entries=manifest.getJSONObject("files");Iterator<String> names=entries.keys();
    while(names.hasNext()){String n=names.next();File f=new File(stage,n);if(!f.getCanonicalPath().startsWith(stage.getCanonicalPath()+"/"))throw new IOException("Invalid manifest path");try(InputStream in=new FileInputStream(f)){if(!digest(in).equals(entries.getString(n)))throw new IOException("Checksum failed: "+n);}}
    Fs.writeText(new File(stage,".installed").toPath(),expected);
    remove(backup);
    if(root().exists()&&!root().renameTo(backup))throw new IOException("Cannot preserve runtime");
    if(!stage.renameTo(root())){if(backup.exists())backup.renameTo(root());throw new IOException("Cannot activate runtime");}
    remove(backup);
   }catch(Exception e){remove(stage);throw e;}
  }
 }
 public HashMap<String,String> environment(String id) throws Exception {
  File p=prefix(id), tmp=new File(gameDir(id),"tmp");
  if(!p.isDirectory()&&!p.mkdirs())throw new IOException("Cannot create prefix");
  if(!tmp.isDirectory()&&!tmp.mkdirs())throw new IOException("Cannot create TMPDIR");
  // Same 64-bit prefix registry as game sessions (WineLaunch.seedRegistry).
  org.pocketarcade.madeira.session.WineLaunch.seedRegistry(p);
  HashMap<String,String> env=new HashMap<>();
  env.put("HOME",gameDir(id).getAbsolutePath());env.put("TMPDIR",tmp.getAbsolutePath());
  env.put("WINEPREFIX",p.getAbsolutePath());env.put("WINELOADER",new File(root(),"bin/wine").getAbsolutePath());
  env.put("WINESERVER",new File(root(),"bin/wineserver").getAbsolutePath());
  env.put("WINEDLLPATH",new File(root(),"lib/wine").getAbsolutePath());
  env.put("WINEDEBUGLOG",new File(gameDir(id),"wine.log").getAbsolutePath());env.put("WINEDEBUG","+timestamp,+pid,+seh");
  env.put("LD_LIBRARY_PATH",new File(root(),"lib").getAbsolutePath()+":"+new File(root(),"lib/wine/aarch64-unix").getAbsolutePath()+":"+context.getApplicationInfo().nativeLibraryDir);
  env.put("PATH",new File(root(),"bin").getAbsolutePath()+":/system/bin");
  env.put("LC_ALL","C.UTF-8");env.put("LANG","C.UTF-8");
  // Android cannot provide the upstream /dev/ntsync interface. Optional patched
  // userspace ntsync remains opt-in; keep synchronization conservative initially.
  env.put("WINEESYNC","0");env.put("WINEFSYNC","0");env.put("WINENTSYNC","0");
  JSONObject config=new GameStore(context).settings(id);
  String mode=config.optString("graphics","wine");StringBuilder overrides=new StringBuilder();
  if(mode.equals("dxvk")||mode.equals("vkd3d")) {
   copyDlls("graphics/dxvk/x64",new File(p,"drive_c/windows/system32"));
   copyDlls("graphics/dxvk/x86",new File(p,"drive_c/windows/syswow64"));
   overrides.append("d3d8,d3d9,d3d10core,d3d11,dxgi=n,b");
  }
  if(mode.equals("vkd3d")) {
   copyDlls("graphics/vkd3d/x64",new File(p,"drive_c/windows/system32"));
   copyDlls("graphics/vkd3d/x86",new File(p,"drive_c/windows/syswow64"));
   overrides.append(";d3d12,d3d12core=n,b");
  }
  if(mode.equals("wine"))overrides.append("d3d8,d3d9,d3d10core,d3d11,dxgi,d3d12,d3d12core=b");
  env.put("WINEDLLOVERRIDES",overrides.toString());
  env.put("DXVK_LOG_PATH",gameDir(id).getAbsolutePath());
  env.put("VKD3D_LOG_FILE",new File(gameDir(id),"vkd3d.log").getAbsolutePath());
  return env;
 }
 private void copyDlls(String source, File dest) throws IOException {
  if(!dest.isDirectory()&&!dest.mkdirs())throw new IOException("Cannot create DLL directory");
  File[] files=new File(root(),source).listFiles((d,n)->n.endsWith(".dll"));
  if(files==null||files.length==0)throw new IOException("Graphics components missing: "+source);
  for(File f:files)Files.copy(f.toPath(),new File(dest,f.getName()).toPath(),StandardCopyOption.REPLACE_EXISTING);
 }
 public void stop(String id) throws Exception {
  ProcessBuilder pb=new ProcessBuilder(new File(root(),"bin/wineserver").getAbsolutePath(),"-k");
  pb.environment().putAll(environment(id));pb.redirectErrorStream(true);
  Process p=pb.start();if(!p.waitFor(5,TimeUnit.SECONDS))p.destroyForcibly();
 }
}
