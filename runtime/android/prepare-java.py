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

    private void loadWine(String ignored)
    {
        try {
            // The Kotlin side prepares the prefix and the environment (session/WineLaunch.kt).
            launch = org.pocketarcade.madeira.session.WineLaunch.prepare(this, getIntent());
            System.load(launch.ntdll);
            createProgressDialog(0, "Starting Windows runtime...");
            runWine(launch.command, launch.environment);
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
            public int getDesktopWidth() { return getWindow().getDecorView().getWidth(); }
            public int getDesktopHeight() { return getWindow().getDecorView().getHeight(); }
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
