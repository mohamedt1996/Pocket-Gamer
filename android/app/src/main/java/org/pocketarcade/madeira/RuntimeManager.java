package org.pocketarcade.madeira;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Installs a user-provided, Android ARM64 Wine/FEX runtime into private app storage.
 * Required ZIP layout: bin/launcher (executable Android arm64 ELF), lib/, share/.
 * Launcher contract: launcher /absolute/path/to/windows.exe ; stdout/stderr are diagnostic output.
 * The launcher must handle x86/x64 translation, Wine, wineserver and display integration.
 */
public final class RuntimeManager {
 private final File root;
 public RuntimeManager(Context context) { root = new File(context.getFilesDir(), "windows-runtime"); }
 public File getRoot() { return root; }
 public File launcher() { return new File(root, "bin/launcher"); }
 public String graphicsStatus() {
  String[] names={"d3d9.dll","d3d10core.dll","d3d11.dll","dxgi.dll","d3d12.dll","d3d12core.dll"};
  StringBuilder b=new StringBuilder();
  for(String n:names){if(b.length()>0)b.append(", ");b.append(n).append(containsFile(root,n)?" present":" absent");}
  return b.toString();
 }
 private static boolean containsFile(File dir,String name){
  if(!dir.isDirectory())return false;File[] files=dir.listFiles();if(files==null)return false;
  for(File f:files){if(f.isFile()&&f.getName().equalsIgnoreCase(name))return true;if(f.isDirectory()&&containsFile(f,name))return true;}return false;
 }
 public boolean ready() { return launcher().isFile() && launcher().canExecute(); }
 public String install(Context context, Uri uri) throws Exception {
  File staging = new File(context.getFilesDir(), "runtime-staging");
  delete(staging); if (!staging.mkdirs()) throw new IOException("Cannot create staging directory");
  long total=0; int count=0;
  try (InputStream source=context.getContentResolver().openInputStream(uri);
       ZipInputStream zip=new ZipInputStream(new BufferedInputStream(source))) {
   ZipEntry entry;
   while ((entry=zip.getNextEntry())!=null) {
    if (++count > 20000) throw new IOException("Too many ZIP entries");
    String name=entry.getName();
    if (name.startsWith("/") || name.contains("\\") || name.matches("^[A-Za-z]:.*")) throw new IOException("Invalid ZIP path");
    File dest=new File(staging,name);
    String base=staging.getCanonicalPath()+File.separator;
    if (!dest.getCanonicalPath().startsWith(base)) throw new IOException("Unsafe ZIP path: "+name);
    if (entry.isDirectory()) { if(!dest.isDirectory()&&!dest.mkdirs())throw new IOException("Cannot create directory"); continue; }
    File parent=dest.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Cannot create parent");
    try(OutputStream out=new BufferedOutputStream(new FileOutputStream(dest))) {
     byte[] buf=new byte[65536];int n;
     while((n=zip.read(buf))!=-1) { total+=n;if(total>1500L*1024*1024)throw new IOException("Runtime ZIP exceeds 1.5 GB unpacked");out.write(buf,0,n); }
    }
    zip.closeEntry();
   }
  } catch(Exception e) {delete(staging);throw e;}
  File binary=new File(staging,"bin/launcher");
  if(!binary.isFile()) {delete(staging);throw new IOException("Runtime ZIP must contain bin/launcher (Android arm64 ELF)");}
  if(!isArm64Elf(binary)) {delete(staging);throw new IOException("bin/launcher is not an AArch64 ELF executable");}
  if(!binary.setExecutable(true,true)) {delete(staging);throw new IOException("Cannot make launcher executable");}
  // Modern Android can block execution from writable app directories. Report the failure in the UI.
  delete(root);
  if(!staging.renameTo(root)) throw new IOException("Cannot activate runtime");
  return "Imported "+count+" entries ("+(total/1024/1024)+" MiB). Launcher available: "+ready();
 }
 private static boolean isArm64Elf(File f) throws IOException {
  try(InputStream in=new FileInputStream(f)) {
   byte[] h=new byte[20];if(in.read(h)!=20)return false;
   return (h[0]&255)==127 && h[1]=='E' && h[2]=='L' && h[3]=='F' && (h[18]&255)==183 && h[19]==0;
  }
 }
 public String run(File executable) throws Exception {
  if(!ready()) throw new IOException("No Android ARM64 Wine/FEX runtime installed");
  ProcessBuilder pb=new ProcessBuilder(launcher().getAbsolutePath(),executable.getAbsolutePath());
  pb.directory(root);
  pb.environment().put("HOME",root.getAbsolutePath());
  pb.environment().put("TMPDIR",root.getAbsolutePath());
  pb.environment().put("LD_LIBRARY_PATH",new File(root,"lib").getAbsolutePath());
  pb.redirectErrorStream(true);
  Process p=pb.start();StringBuilder log=new StringBuilder();
  try(InputStream in=p.getInputStream()) {
   byte[] buf=new byte[4096];int n;long limit=1024*1024;
   while((n=in.read(buf))!=-1) {if(log.length()<limit)log.append(new String(buf,0,n,StandardCharsets.UTF_8));}
  }
  int code=p.waitFor();return "Windows launcher exit code: "+code+"\n"+log;
 }
 public File copyExecutable(Context context,Uri uri) throws IOException {
  File dir=new File(context.getFilesDir(),"windows-games");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Cannot create game folder");
  File dest=new File(dir,"selected.exe");
  try(InputStream in=context.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(dest)) {
   byte[] buf=new byte[65536];int n;long total=0;
   while((n=in.read(buf))!=-1) {total+=n;if(total>1024L*1024*1024)throw new IOException("EXE too large");out.write(buf,0,n);}
  }
  try(InputStream in=new FileInputStream(dest)) {if(in.read()!='M'||in.read()!='Z')throw new IOException("Not an MZ executable");}
  return dest;
 }
 private static void delete(File f) {
  if(f.isDirectory()) {File[] children=f.listFiles();if(children!=null)for(File c:children)delete(c);}
  if(f.exists()&&!f.delete())throw new IllegalStateException("Cannot delete "+f);
 }
}
