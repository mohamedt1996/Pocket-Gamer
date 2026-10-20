#!/usr/bin/env python3
from pathlib import Path
import sys
java=Path(sys.argv[1]).read_text()
start=java.index('    private void loadWine( String cmdline )')
end=java.index('    private final void runWine(',start)
java=java[:start]+'''    private void loadWine(String ignored)
    {
        try {
            org.pocketarcade.madeira.PortRuntime runtime = new org.pocketarcade.madeira.PortRuntime(this);
            runtime.installBundled();
            String game = getIntent().getStringExtra("gameId");
            HashMap<String,String> env = runtime.environment(game);
            File ntdll = new File(runtime.root(), "lib/wine/aarch64-unix/ntdll.so");
            org.pocketarcade.madeira.NativeEnvironment.prepare(env.get("LD_LIBRARY_PATH"),
                new File(new org.pocketarcade.madeira.GameStore(this).settings(game).getString("exe")).getParent(), env.get("TMPDIR"));
            System.load(ntdll.getAbsolutePath());
            String command = getIntent().getStringExtra("wineCommand");
            if (command == null) command = "notepad.exe";
            createProgressDialog(0, "Starting Windows runtime...");
            runWine(command, env);
        } catch (Throwable e) {
            Log.e("madeira", "Runtime initialization failed", e);
            runOnUiThread(() -> {
                if (progress_dialog != null) progress_dialog.dismiss();
                new android.app.AlertDialog.Builder(this).setTitle("Runtime failed")
                    .setMessage(e.toString()).setPositiveButton("Close", (d,w) -> finish()).show();
            });
        }
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        android.widget.Toast.makeText(this,"Stop the current Wine session before starting another game.",android.widget.Toast.LENGTH_LONG).show();
    }
    @Override public void onBackPressed() {
        new android.app.AlertDialog.Builder(this).setTitle("Stop this game?")
            .setPositiveButton("Stop", (d,w) -> {
                new Thread(() -> {
                    try { new org.pocketarcade.madeira.PortRuntime(this).stop(getIntent().getStringExtra("gameId")); }
                    catch (Exception e) { Log.e("madeira", "Stop failed", e); }
                    android.os.Process.killProcess(android.os.Process.myPid());
                }).start();
            }).setNegativeButton("Keep playing", null).show();
    }

'''+java[end:]
# The app runs in a dedicated :wine process. Keep the JNI class name unchanged.

java=java.replace('    private final String LOGTAG = "wine";', '    private int active_hwnd;\n    private final java.util.HashSet<Integer> heldKeys = new java.util.HashSet<>();\n    private final String LOGTAG = "wine";')
java=java.replace('        public boolean onTouchEvent( MotionEvent event )\n        {', '        public boolean onTouchEvent( MotionEvent event )\n        {\n            active_hwnd = window.hwnd;')
java=java.replace('        setContentView( new TopView( this, hwnd ));', '        setContentView( new TopView( this, hwnd ));\n        active_hwnd = hwnd;\n        addControls();')
controls = r"""
    private void addControls() {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setAlpha(0.7f);
        String[] labels = {"W", "A", "S", "D", "Jump", "Enter", "Esc"};
        int[] codes = {KeyEvent.KEYCODE_W,KeyEvent.KEYCODE_A,KeyEvent.KEYCODE_S,KeyEvent.KEYCODE_D,
                       KeyEvent.KEYCODE_SPACE,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_ESCAPE};
        for(int i=0;i<labels.length;i++) {
            android.widget.Button button = new android.widget.Button(this);
            button.setText(labels[i]);button.setFocusable(false);
            final int code=codes[i];
            button.setOnTouchListener((view,event)->{
                int action=event.getActionMasked();
                if(action==MotionEvent.ACTION_DOWN) {
                    heldKeys.add(code);wine_keyboard_event(active_hwnd,KeyEvent.ACTION_DOWN,code,0);
                } else if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) {
                    heldKeys.remove(code);wine_keyboard_event(active_hwnd,KeyEvent.ACTION_UP,code,0);
                }
                return true;
            });
            row.addView(button,new android.widget.LinearLayout.LayoutParams(0,-2,1));
        }
        android.widget.FrameLayout.LayoutParams position=new android.widget.FrameLayout.LayoutParams(-1,-2,android.view.Gravity.BOTTOM);
        addContentView(row,position);
    }
    @Override protected void onPause() {
        for(int key:heldKeys)wine_keyboard_event(active_hwnd,KeyEvent.ACTION_UP,key,0);
        heldKeys.clear();super.onPause();
    }
"""
java=java.replace('    // Entry points for the device driver',controls+'\n    // Entry points for the device driver')
java_dir=Path(sys.argv[2])
java_dir.mkdir(parents=True,exist_ok=True)
(java_dir/'WineActivity.java').write_text(java)
