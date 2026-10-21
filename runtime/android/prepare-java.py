#!/usr/bin/env python3
from pathlib import Path
import sys
java=Path(sys.argv[1]).read_text()

def edit(old, new):
    """Replace exactly one occurrence; fail if this Wine's WineActivity changed."""
    global java
    count = java.count(old)
    if count != 1:
        raise SystemExit(f'WineActivity.java anchor changed: expected once, got {count}: {old.strip()[:70]!r}')
    java = java.replace(old, new, 1)
start=java.index('    private void loadWine( String cmdline )')
end=java.index('    private final void runWine(',start)
java=java[:start]+'''    private org.pocketarcade.madeira.session.SessionOverlay overlay;
    private org.pocketarcade.madeira.session.WineLaunch launch;

    // Wine starts once per :wine process: wine_init never returns while it runs,
    // and cannot be started a second time in the same process.
    private static boolean wine_started;

    private void loadWine(String ignored)
    {
        synchronized (WineActivity.class) {
            if (wine_started) {
                // A recreated activity cannot reattach to the running Wine; end the
                // process so the next launch starts clean instead of re-running wine_init.
                Log.w("madeira", "WineActivity recreated after Wine started; ending the session");
                runOnUiThread(this::endProcess);
                return;
            }
            wine_started = true;
        }
        try {
            createProgressDialog(0, "Preparing Windows runtime...");
            // The Kotlin side prepares the prefix and the environment (session/WineLaunch.kt).
            launch = org.pocketarcade.madeira.session.WineLaunch.prepare(this, getIntent());
            System.load(launch.ntdll);
            createProgressDialog(0, "Starting Windows runtime...");
            String err = runWine(launch.command, launch.environment);
            // wine_init only returns when Wine could not start.
            fail(new RuntimeException(err == null || err.isEmpty() ? "Wine exited during startup" : err));
        } catch (Throwable e) {
            fail(e);
        }
    }

    private void fail(Throwable e)
    {
        Log.e("madeira", "Runtime initialization failed", e);
        runOnUiThread(() -> {
            if (progress_dialog != null) progress_dialog.dismiss();
            new android.app.AlertDialog.Builder(this).setTitle("Runtime failed")
                .setMessage(e.toString()).setCancelable(false)
                .setPositiveButton("Close", (d,w) -> endProcess()).show();
        });
    }

    // Leaves no cached :wine process behind: the app treats a live :wine process as a running game.
    private void endProcess()
    {
        org.pocketarcade.madeira.session.SessionState.markEnded(this);
        finishAndRemoveTask();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        // Returning from the session notification just brings the game back; only a new launch is refused.
        if (intent.hasExtra(org.pocketarcade.madeira.session.LaunchSpec.EXTRA))
            android.widget.Toast.makeText(this,"Stop the current game before starting another one.",android.widget.Toast.LENGTH_LONG).show();
    }
    @Override public void onBackPressed() {
        if (overlay != null) overlay.onBack();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (overlay != null && overlay.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (overlay != null && overlay.onMotion(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }

'''+java[end:]
# The app runs in a dedicated :wine process. Keep the JNI class name unchanged.

# runWine reports wine_init's error, and passes the game's command line as separate
# arguments: Wine quotes each argv item when it builds explorer's command line, so a
# single pre-quoted string ("Z:\...\game.exe" -arg) would arrive double-quoted.
edit('    private final void runWine( String cmdline, HashMap<String,String> environ )',
     '    private final String runWine( String cmdline, HashMap<String,String> environ )')
edit('''        String[] cmd = { environ.get( "WINELOADER" ),
                         "c:\\\\windows\\\\system32\\\\explorer.exe",
                         "/desktop=shell,,android",
                         cmdline };

        String err = wine_init( cmd, env );
        Log.e( LOGTAG, err );
    }''', '''        java.util.ArrayList<String> args = new java.util.ArrayList<>();
        args.add( environ.get( "WINELOADER" ) );
        args.add( "c:\\\\windows\\\\system32\\\\explorer.exe" );
        args.add( "/desktop=shell,,android" );
        args.addAll( org.pocketarcade.madeira.session.CommandLine.split( cmdline ) );
        String[] cmd = args.toArray( new String[0] );

        String err = wine_init( cmd, env );
        Log.e( LOGTAG, String.valueOf( err ) );
        return err;
    }''')
# Keys go to the window last touched; once that window is gone, fall back to the desktop.
edit('''    public void destroy_window( int hwnd )
    {
''', '''    public void destroy_window( int hwnd )
    {
        if (hwnd == active_hwnd) active_hwnd = desktop_hwnd;
''')

edit('    private final String LOGTAG = "wine";', '    private int active_hwnd;\n    private int desktop_hwnd;\n    private final String LOGTAG = "wine";')
edit('        public boolean onTouchEvent( MotionEvent event )\n        {', '        public boolean onTouchEvent( MotionEvent event )\n        {\n            active_hwnd = window.hwnd;')
# The desktop is created differently by the two Wine trees this script serves.
if 'setContentView( new TopView( this, hwnd ));' in java:
    # Proton Android fork (Wine 11.0): desktop view and window are created together.
    edit('        setContentView( new TopView( this, hwnd ));',
         '        setContentView( new TopView( this, hwnd ));\n        active_hwnd = hwnd;\n        desktop_hwnd = hwnd;\n        addControls();')
else:
    # Madeira / upstream Wine 11.4: the view comes first, the desktop window
    # arrives later through create_window(..., is_desktop, ...).
    edit('        setContentView( top_view = new TopView( this ));',
         '        setContentView( top_view = new TopView( this ));\n        addControls();')
    edit('                desktop_window = win;\n',
         '                desktop_window = win;\n                active_hwnd = hwnd;\n                desktop_hwnd = hwnd;\n')
controls = r"""
    private void addControls() {
        overlay = org.pocketarcade.madeira.session.SessionOverlay.attach(this, new org.pocketarcade.madeira.session.WineInput() {
            public void key(int keycode, boolean down) {
                wine_keyboard_event(active_hwnd, down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, keycode, 0);
            }
            public void mouse(int action, int x, int y, int buttons, int vscroll) {
                wine_motion_event(desktop_hwnd != 0 ? desktop_hwnd : active_hwnd, action, x, y, buttons, vscroll);
            }
            // The content view is what Wine's desktop covers; the decor view also spans the system bars.
            public int getDesktopWidth() { return findViewById(android.R.id.content).getWidth(); }
            public int getDesktopHeight() { return findViewById(android.R.id.content).getHeight(); }
        });
    }
    @Override protected void onPause() {
        if (overlay != null) overlay.onPause();
        super.onPause();
    }
"""
edit('    // Entry points for the device driver',controls+'\n    // Entry points for the device driver')
edit('        public void onSurfaceTextureUpdated(SurfaceTexture surftex)\n        {\n        }',
     '        public void onSurfaceTextureUpdated(SurfaceTexture surftex)\n        {\n            if (overlay != null) overlay.frame();\n        }')
java_dir=Path(sys.argv[2])
java_dir.mkdir(parents=True,exist_ok=True)
(java_dir/'WineActivity.java').write_text(java)
