package org.pocketarcade.madeira;
public final class NativeEnvironment {
 static { System.loadLibrary("madeira_probe"); }
 private NativeEnvironment() {}
 public static native void prepare(String libraryPath,String cwd,String tmp);
}
