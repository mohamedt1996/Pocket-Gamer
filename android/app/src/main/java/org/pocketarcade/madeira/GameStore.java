package org.pocketarcade.madeira;
import android.content.Context;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Game data, installers and prefix configuration stay in per-game directories. */
public final class GameStore {
 private final Context context;
 public GameStore(Context c){context=c.getApplicationContext();}
 public File directory(String id) throws IOException {
  if(id==null||!id.matches("[a-f0-9]{32}"))throw new IOException("Invalid game id");
  return new File(context.getFilesDir(),"games/"+id);
 }
 public JSONObject settings(String id) throws Exception {return new JSONObject(Fs.readText(new File(directory(id),"game.json").toPath()));}
 public void save(String id,JSONObject obj) throws Exception {
  File dest=new File(directory(id),"game.json"),tmp=new File(directory(id),"game.json.tmp");
  Fs.writeText(tmp.toPath(),obj.toString(2));Files.move(tmp.toPath(),dest.toPath(),StandardCopyOption.REPLACE_EXISTING);
 }
 public ArrayList<JSONObject> list() throws Exception {
  ArrayList<JSONObject> out=new ArrayList<>();File[] dirs=new File(context.getFilesDir(),"games").listFiles();
  if(dirs!=null)for(File d:dirs)if(new File(d,"game.json").isFile())out.add(settings(d.getName()));
  out.sort((a,b)->a.optString("title").compareToIgnoreCase(b.optString("title")));return out;
 }
 private String name(Uri uri) {
  try(Cursor c=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
   if(c!=null&&c.moveToFirst()){String n=c.getString(0);if(n!=null)return n.replaceAll("[^A-Za-z0-9._ -]","_");}
  }catch(Exception ignored){}
  return "program.exe";
 }
 private void copy(InputStream in,File file) throws IOException {
  if(in==null)throw new IOException("Cannot open selected file");
  File temp=new File(file.getParentFile(),file.getName()+".partial");
  try(OutputStream out=new FileOutputStream(temp)){
   byte[] b=new byte[65536];int n;long size=0;
   while((n=in.read(b))!=-1){size+=n;if(size>2L*1024*1024*1024)throw new IOException("File exceeds 2 GiB");out.write(b,0,n);}
  }catch(IOException e){temp.delete();throw e;}
  Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);
 }
 private static void validateInstaller(File f) throws IOException {
  try(InputStream in=new FileInputStream(f)){
   byte[] h=new byte[8];int n=in.read(h);
   boolean exe=n>=2&&h[0]=='M'&&h[1]=='Z';
   boolean msi=n==8&&Arrays.equals(h,new byte[]{(byte)0xd0,(byte)0xcf,0x11,(byte)0xe0,(byte)0xa1,(byte)0xb1,0x1a,(byte)0xe1});
   if(!(f.getName().toLowerCase(Locale.ROOT).endsWith(".msi")?msi:exe))throw new IOException("File is not an EXE/MSI installer");
  }
 }
 public String importGame(Uri uri) throws Exception {
  String id=UUID.randomUUID().toString().replace("-","");File dir=directory(id);File files=new File(dir,"files");
  if(!files.mkdirs())throw new IOException("Cannot create game directory");
  String inputName=name(uri);
  if(inputName.toLowerCase(Locale.ROOT).endsWith(".zip")) return importZip(id,uri,inputName,files);
  File exe=new File(files,inputName);
  try(InputStream in=context.getContentResolver().openInputStream(uri)){copy(in,exe);}
  validateInstaller(exe);
  JSONObject obj=new JSONObject();obj.put("id",id);obj.put("title",exe.getName());obj.put("exe",exe.getAbsolutePath());obj.put("graphics","wine");
  save(id,obj);return id;
 }
 private String importZip(String id,Uri uri,String title,File folder) throws Exception {
  ArrayList<File> executables=new ArrayList<>();long total=0;int entries=0;
  try(InputStream input=context.getContentResolver().openInputStream(uri);
      java.util.zip.ZipInputStream zip=new java.util.zip.ZipInputStream(new BufferedInputStream(input))) {
   java.util.zip.ZipEntry e;byte[] buffer=new byte[65536];
   while((e=zip.getNextEntry())!=null){
    if(++entries>100000)throw new IOException("Too many game files");
    File f=new File(folder,e.getName());
    if(e.getName().contains("\\")||e.getName().startsWith("/")||!f.getCanonicalPath().startsWith(folder.getCanonicalPath()+"/"))throw new IOException("Invalid ZIP path");
    if(e.isDirectory()){if(!f.isDirectory()&&!f.mkdirs())throw new IOException("Cannot create directory");continue;}
    if(!f.getParentFile().isDirectory()&&!f.getParentFile().mkdirs())throw new IOException("Cannot create game directory");
    try(OutputStream out=new FileOutputStream(f)){int n;while((n=zip.read(buffer))!=-1){total+=n;if(total>8L*1024*1024*1024)throw new IOException("Game archive exceeds 8 GiB");out.write(buffer,0,n);}}
    if(f.getName().toLowerCase(Locale.ROOT).endsWith(".exe"))executables.add(f);
   }
  }
  if(executables.isEmpty())throw new IOException("ZIP contains no EXE");
  executables.sort(Comparator.comparing(File::getAbsolutePath));
  JSONObject obj=new JSONObject();obj.put("id",id);obj.put("title",title);obj.put("exe",executables.get(0).getAbsolutePath());obj.put("graphics","wine");save(id,obj);return id;
 }
 public String addSmoke(File runtime,String arch,String test) throws Exception {
  if(!arch.matches("x64|x86")||!test.matches("hello|d3d11|d3d12-device"))throw new IOException("Invalid test");
  String id=UUID.randomUUID().toString().replace("-","");File folder=new File(directory(id),"files");if(!folder.mkdirs())throw new IOException("Cannot create test directory");
  File exe=new File(folder,test+".exe");Files.copy(new File(runtime,"graphics/smoke/"+arch+"/"+test+".exe").toPath(),exe.toPath());
  JSONObject obj=new JSONObject();obj.put("id",id);obj.put("title",arch+" "+test+" smoke");obj.put("exe",exe.getAbsolutePath());obj.put("graphics",test.equals("d3d11")?"dxvk":test.equals("d3d12-device")?"vkd3d":"wine");save(id,obj);return id;
 }
 public File importDependency(String id,Uri uri) throws Exception {
  File folder=new File(directory(id),"installers");if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Cannot create installer directory");
  File f=new File(folder,name(uri));try(InputStream in=context.getContentResolver().openInputStream(uri)){copy(in,f);}validateInstaller(f);return f;
 }
 public File downloadDependency(String id,String address,boolean msi) throws Exception {
  URL url=new URL(address);HttpURLConnection connection=null;
  try {
   for(int i=0;i<6;i++){
    if(!url.getProtocol().equals("https")||url.getUserInfo()!=null)throw new IOException("Use an HTTPS URL without credentials");
    connection=(HttpURLConnection)url.openConnection();connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(20000);connection.setReadTimeout(30000);
    int code=connection.getResponseCode();
    if(code>=300&&code<400){String next=connection.getHeaderField("Location");connection.disconnect();if(next==null)throw new IOException("Redirect missing Location");url=new URL(url,next);continue;}
    if(code!=200)throw new IOException("Download returned HTTP "+code);
    File folder=new File(directory(id),"installers");if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Cannot create installer directory");
    File f=new File(folder,"download-"+System.currentTimeMillis()+(msi?".msi":".exe"));
    try(InputStream in=connection.getInputStream()){copy(in,f);}validateInstaller(f);return f;
   }
   throw new IOException("Too many redirects");
  }finally{if(connection!=null)connection.disconnect();}
 }
 public static String command(File f){String path="Z:"+f.getAbsolutePath().replace('/','\\');if(path.indexOf('"')>=0)throw new IllegalArgumentException("Invalid path");return f.getName().toLowerCase(Locale.ROOT).endsWith(".msi")?"msiexec /i \""+path+"\"":"\""+path+"\"";}
}
