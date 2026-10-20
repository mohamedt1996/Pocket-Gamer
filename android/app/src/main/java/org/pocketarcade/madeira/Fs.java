package org.pocketarcade.madeira;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
final class Fs {
 static byte[] readAll(InputStream in) throws IOException {
  ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[16384];int n;
  while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();
 }
 static String readText(Path p) throws IOException {try(InputStream in=new FileInputStream(p.toFile())){return new String(readAll(in),StandardCharsets.UTF_8);}}
 static void writeText(Path p,String text) throws IOException {try(OutputStream out=new FileOutputStream(p.toFile())){out.write(text.getBytes(StandardCharsets.UTF_8));}}
}
