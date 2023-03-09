package com.termux.x11;

import static com.termux.x11.CmdEntryPoint.ACTION_START;
import static com.termux.x11.CmdEntryPoint.requestConnection;
import static com.termux.x11.LoriePreferences.ACTION_PREFERENCES_CHANGED;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.viewpager.widget.ViewPager;

import com.termux.x11.utils.FullscreenWorkaround;
import com.termux.x11.utils.PermissionUtils;
import com.termux.x11.utils.SamsungDexUtils;
import com.termux.x11.utils.TermuxX11ExtraKeys;
import com.termux.x11.utils.X11ToolbarViewPager;


public class MainActivity extends AppCompatActivity implements View.OnApplyWindowInsetsListener, TouchParser.OnTouchParseListener {
    static final String ACTION_STOP = "com.termux.x11.ACTION_STOP";
    static final String REQUEST_LAUNCH_EXTERNAL_DISPLAY = "request_launch_external_display";
    public static final int KeyPress = 2; // synchronized with X.h
    public static final int KeyRelease = 3; // synchronized with X.h

    FrameLayout frm;
    private TouchParser mTP;
    private final ServiceEventListener listener = new ServiceEventListener();
    private ICmdEntryInterface service = null;
    public TermuxX11ExtraKeys mExtraKeys;
    private int mTerminalToolbarDefaultHeight;
    private Notification mNotification;
    private final int mNotificationId = 7892;
    NotificationManager mNotificationManager;
    private boolean mClientConnected = false;
    private SurfaceHolder.Callback mLorieViewCallback;
    private PointF mDpi;

    public MainActivity() {
        init();
    }

    @SuppressLint({"AppCompatMethod", "ObsoleteSdkInt"}) @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.registerOnSharedPreferenceChangeListener((sharedPreferences, key) -> onPreferencesChanged());

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.main_activity);

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        mDpi = new PointF(dm.xdpi, dm.ydpi);

        //kbd = findViewById(R.id.additionalKbd);
        frm = findViewById(R.id.frame);
        Button preferencesButton = findViewById(R.id.preferences_button);
        preferencesButton.setOnClickListener((l) -> {
            Intent i = new Intent(this, LoriePreferences.class);
            i.setAction(Intent.ACTION_MAIN);
            startActivity(i);
        });

        SurfaceView lorieView = findViewById(R.id.lorieView);
        SurfaceView cursorView = findViewById(R.id.cursorView);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1, 1);
        cursorView.setLayoutParams(params);

        listener.setAsListenerTo(lorieView, cursorView);

        mTP = new TouchParser(lorieView, this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            getWindow().
             getDecorView().
              setPointerIcon(PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL));

        IntentFilter filter = new IntentFilter(ACTION_START);
        filter.addAction(ACTION_PREFERENCES_CHANGED);
        filter.addAction(ACTION_STOP);

        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_START.equals(intent.getAction())) {
                    try {
                        Log.v("LorieBroadcastReceiver", "Got new ACTION_START intent");
                        IBinder b = intent.getBundleExtra("").getBinder("");
                        service = ICmdEntryInterface.Stub.asInterface(b);
                        service.asBinder().linkToDeath(() -> {
                            service = null;
                            CmdEntryPoint.requestConnection();
                        }, 0);

                        onReceiveConnection();
                    } catch (Exception e) {
                        Log.e("MainActivity", "Something went wrong while we extracted connection details from binder.", e);
                    }
                } else if (ACTION_STOP.equals(intent.getAction())) {
                    finishAffinity();
                } else if (ACTION_PREFERENCES_CHANGED.equals(intent.getAction())) {
                    onPreferencesChanged();
                }
            }
        }, filter);

        // Taken from Stackoverflow answer https://stackoverflow.com/questions/7417123/android-how-to-adjust-layout-in-full-screen-mode-when-softkeyboard-is-visible/7509285#
        FullscreenWorkaround.assistActivity(this);
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotification = buildNotification();

        requestConnection();
        onPreferencesChanged();

        toggleExtraKeys(false);
    }

    void onReceiveConnection() {
        try {
            if (service != null && service.asBinder().isBinderAlive()) {
                Log.v("LorieBroadcastReceiver", "Extracting X connection socket.");
                ParcelFileDescriptor logcatOutput = service.getLogcatOutput();
                if (logcatOutput != null) {
                    startLogcat(logcatOutput.detachFd());
                }

                tryConnect();
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Something went wrong while we were establishing connection", e);
        }
    }

    void tryConnect() {
        if (mClientConnected)
            return;
        try {
            Log.v("LorieBroadcastReceiver", "Extracting X connection socket.");
            ParcelFileDescriptor fd = service.getXConnection();
            if (fd != null) {
                connect(fd.detachFd());
                SurfaceView lorieView = getLorieView();
                Rect r = lorieView.getHolder().getSurfaceFrame();
                mLorieViewCallback.surfaceChanged(lorieView.getHolder(), PixelFormat.RGBA_8888, r.width(), r.height());
            } else
                handler.postDelayed(this::tryConnect, 500);
        } catch (Exception e) {
            Log.e("MainActivity", "Something went wrong while we were establishing connection", e);
        }
    }

    void onPreferencesChanged() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);

        int mode = Integer.parseInt(preferences.getString("touchMode", "1"));
        mTP.setMode(mode);

        if (preferences.getBoolean("dexMetaKeyCapture", false)) {
            SamsungDexUtils.dexMetaKeyCapture(this, false);
        }

        setTerminalToolbarView();
        onWindowFocusChanged(true);
        setHorizontalScrollEnabled(preferences.getBoolean("horizontalScroll", false));
        setClipboardSyncEnabled(preferences.getBoolean("clipboardSync", false));

        SurfaceView lorieView = getLorieView();
        Rect r = lorieView.getHolder().getSurfaceFrame();
        mLorieViewCallback.surfaceChanged(lorieView.getHolder(), PixelFormat.RGBA_8888, r.width(), r.height());
        lorieView.setFocusable(true);
        lorieView.setFocusableInTouchMode(true);
        lorieView.requestFocus();
    }

    @Override
    public void onResume() {
        super.onResume();
        nativeResume();

        mNotificationManager.notify(mNotificationId, mNotification);

        setTerminalToolbarView();
        getLorieView().requestFocus();
    }

    @Override
    public void onPause() {
        mNotificationManager.cancel(mNotificationId);

        // We do not really need to draw while application is in background.
        nativePause();
        super.onPause();
    }

    @Override
    public void setTheme(int resId) {
        boolean externalDisplayRequested = getIntent().getBooleanExtra(REQUEST_LAUNCH_EXTERNAL_DISPLAY, false);

        // for some reason, calling setTheme() in onCreate() wasn't working.
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        super.setTheme(externalDisplayRequested || preferences.getBoolean("fullscreen", true) ?
                R.style.FullScreen_ExternalDisplay : R.style.NoActionBar);
    }

    public SurfaceView getLorieView() {
        return findViewById(R.id.lorieView);
    }

    public ViewPager getTerminalToolbarViewPager() {
        return findViewById(R.id.terminal_toolbar_view_pager);
    }

    public void setDefaultToolbarHeight(int height) {
        mTerminalToolbarDefaultHeight = height;
    }

    private void setTerminalToolbarView() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();

        terminalToolbarViewPager.setAdapter(new X11ToolbarViewPager.PageAdapter(this, listener));
        terminalToolbarViewPager.addOnPageChangeListener(new X11ToolbarViewPager.OnPageChangeListener(this, terminalToolbarViewPager));

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean showNow = preferences.getBoolean("showAdditionalKbd", true);

        terminalToolbarViewPager.setVisibility(showNow ? View.VISIBLE : View.GONE);
        findViewById(R.id.terminal_toolbar_view_pager).requestFocus();

        if (mExtraKeys == null) {
            handler.postDelayed(() -> {
                if (mExtraKeys != null) {
                    ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
                    layoutParams.height = Math.round(mTerminalToolbarDefaultHeight *
                            (mExtraKeys.getExtraKeysInfo() == null ? 0 : mExtraKeys.getExtraKeysInfo().getMatrix().length));
                    terminalToolbarViewPager.setLayoutParams(layoutParams);
                }
            }, 200);
        }
    }

    public void toggleExtraKeys(boolean visible) {
        runOnUiThread(() -> {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            boolean enabled = preferences.getBoolean("showAdditionalKbd", true);
            ViewPager pager = getTerminalToolbarViewPager();
            ViewGroup parent = (ViewGroup) pager.getParent();
            boolean show = enabled && mClientConnected && visible;

            if (show) {
                getTerminalToolbarViewPager().bringToFront();
            } else {
                parent.removeView(pager);
                parent.addView(pager, 0);
            }

            pager.setVisibility(show ? View.VISIBLE : View.GONE);
        });
    }

    @Override
    public void toggleExtraKeys() {
        int visibility = getTerminalToolbarViewPager().getVisibility();
        toggleExtraKeys(visibility != View.VISIBLE);
    }

    @SuppressLint("ObsoleteSdkInt")
    Notification buildNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.putExtra("foo_bar_extra_key", "foo_bar_extra_value");
        notificationIntent.setAction(Long.toString(System.currentTimeMillis()));

        Intent exitIntent = new Intent(ACTION_STOP);
        exitIntent.setPackage(getPackageName());

        Intent preferencesIntent = new Intent(this, LoriePreferences.class);
        preferencesIntent.setAction("0");

        PendingIntent pIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pExitIntent = PendingIntent.getBroadcast(this, 0, exitIntent, PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pPreferencesIntent = PendingIntent.getActivity(this, 0, preferencesIntent, PendingIntent.FLAG_IMMUTABLE);

        int priority = Notification.PRIORITY_HIGH;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N)
            priority = NotificationManager.IMPORTANCE_HIGH;
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        String channelId = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? getNotificationChannel(notificationManager) : "";
        return new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Termux:X11")
                .setSmallIcon(R.drawable.ic_x11_icon)
                .setContentText("Pull down to show options")
                .setContentIntent(pIntent)
                .setOngoing(true)
                .setPriority(priority)
                .setShowWhen(false)
                .setColor(0xFF607D8B)
                .addAction(0, "Preferences", pPreferencesIntent)
                .addAction(0, "Exit", pExitIntent)
                .build();
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private String getNotificationChannel(NotificationManager notificationManager){
        String channelId = getResources().getString(R.string.app_name);
        String channelName = getResources().getString(R.string.app_name);
        NotificationChannel channel = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH);
        channel.setImportance(NotificationManager.IMPORTANCE_NONE);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        notificationManager.createNotificationChannel(channel);
        return channelId;
    }

    int orientation;

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (newConfig.orientation != orientation) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Activity.INPUT_METHOD_SERVICE);
            View view = getCurrentFocus();
            if (view == null) {
                view = findViewById(R.id.lorieView);
                view.requestFocus();
            }
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }

        orientation = newConfig.orientation;
        setTerminalToolbarView();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        Window window = getWindow();
        View decorView = window.getDecorView();
        boolean fullscreen = preferences.getBoolean("fullscreen", false);
        boolean reseed = preferences.getBoolean("Reseed", true);

        fullscreen = fullscreen || getIntent().getBooleanExtra(REQUEST_LAUNCH_EXTERNAL_DISPLAY, false);

        if (hasFocus && fullscreen) {
            window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            decorView.setSystemUiVisibility(0);
        }

        if (reseed)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        else
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN|
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("dexMetaKeyCapture", false)) {
            SamsungDexUtils.dexMetaKeyCapture(this, hasFocus);
        }
    }

    @Override
    public void onBackPressed() {
    }

    @Override
    public void onUserLeaveHint() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (preferences.getBoolean("PIP", false) && PermissionUtils.hasPipPermission(this)) {
            enterPictureInPictureMode();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, @NonNull Configuration newConfig) {
        toggleExtraKeys(!isInPictureInPictureMode);

        frm.setPadding(0, 0, 0, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        }
    }

    @SuppressLint("WrongConstant")
    @Override
    public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
        SurfaceView c = getLorieView();
        Rect r = c.getHolder().getSurfaceFrame();
        handler.postDelayed(() -> mLorieViewCallback.surfaceChanged(c.getHolder(), 0, r.width(), r.height()), 100);
        return insets;
    }

    @SuppressWarnings("SameParameterValue")
    private class ServiceEventListener implements View.OnKeyListener {
        @SuppressLint({"WrongConstant", "ClickableViewAccessibility"})
        private void setAsListenerTo(SurfaceView view, SurfaceView cursor) {
            view.setOnTouchListener((v, e) -> mTP.onTouchEvent(e));
            view.setOnHoverListener((v, e) -> mTP.onTouchEvent(e));
            view.setOnGenericMotionListener((v, e) -> mTP.onTouchEvent(e));
            view.setOnKeyListener(this);

            cursor.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(@NonNull SurfaceHolder holder) {
                    cursor.getHolder().setFormat(PixelFormat.TRANSLUCENT);
                }
                @Override public void surfaceChanged(@NonNull SurfaceHolder holder, int f, int w, int h) {
                    cursor.getHolder().setFormat(PixelFormat.TRANSLUCENT);
                    cursorChanged(holder.getSurface());
                }
                @Override public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                    cursorChanged(null);
                }
            });

            mLorieViewCallback = new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(@NonNull SurfaceHolder holder) {
                    holder.setFormat(PixelFormat.OPAQUE);
                }
                @Override public void surfaceChanged(@NonNull SurfaceHolder holder, int f, int width, int height) {
                    int w = width;
                    int h = height;
                    SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
                    switch(preferences.getString("displayResolutionMode", "native")) {
                        case "scaled":
                            int scale = preferences.getInt("displayScale", 100);
                            w = width  / scale * 100;
                            h = height / scale * 100;
                            break;
                        case "exact":
                            String[] resolution = preferences.getString("displayResolutionExact", "1024x768").split("x");
                            w = Integer.parseInt(resolution[0]);
                            h = Integer.parseInt(resolution[1]);
                            break;
                    }

                    if (width < height && w > h) {
                        int temp = w;
                        w = h;
                        h = temp;
                    }

                    windowChanged(holder.getSurface(), w, h, width, height);
                    if (service != null) {
                        try {
                            service.outputResize(w, h);
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    }
                }
                @Override public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                    windowChanged(null, 0, 0, 0, 0);
                }
            };

            Rect r = view.getHolder().getSurfaceFrame();
            mLorieViewCallback.surfaceChanged(view.getHolder(), 0, r.width(), r.height());

            view.getHolder().addCallback(mLorieViewCallback);
        }

        private boolean isSource(KeyEvent e, int source) {
            return (e.getSource() & source) == source;
        }

        private boolean rightPressed = false; // Prevent right button press event from being repeated
        private boolean middlePressed = false; // Prevent middle button press event from being repeated
        @SuppressWarnings("DanglingJavadoc")
        @Override
        public boolean onKey(View v, int keyCode, KeyEvent e) {
            Log.e("KEY", " " + e);
            // Ignoring Android's autorepeat.
            if (e.getRepeatCount() > 0)
                return true;


            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (isSource(e, InputDevice.SOURCE_MOUSE) &&
                        rightPressed != (e.getAction() == KeyEvent.ACTION_DOWN)) {
                    onPointerButton(TouchParser.BTN_RIGHT, (e.getAction() == KeyEvent.ACTION_DOWN) ? TouchParser.ACTION_DOWN : TouchParser.ACTION_UP);
                    rightPressed = (e.getAction() == KeyEvent.ACTION_DOWN);
                } else if (e.getAction() == KeyEvent.ACTION_UP) {
                    toggleKeyboardVisibility(MainActivity.this);
                    findViewById(R.id.lorieView).requestFocus();
                }
                return true;
            }

            if (keyCode == KeyEvent.KEYCODE_MENU &&
                    isSource(e, InputDevice.SOURCE_MOUSE) &&
                    middlePressed != (e.getAction() == KeyEvent.ACTION_DOWN)) {
                onPointerButton(TouchParser.BTN_MIDDLE, (e.getAction() == KeyEvent.ACTION_DOWN) ? TouchParser.ACTION_DOWN : TouchParser.ACTION_UP);
                middlePressed = (e.getAction() == KeyEvent.ACTION_DOWN);
                return true;
            }

            /**
             * A KeyEvent is generated in Android when the user interacts with the device's
             * physical keyboard or software keyboard. If the KeyEvent is generated by a physical key,
             * or a software key that has a corresponding physical key, it will contain a key code.
             * If the key that is pressed generates a symbol, such as a letter or number,
             * the symbol can be retrieved by calling the KeyEvent::getUnicodeChar() method.
             * If the physical key requires the user to press the Shift key to enter the symbol,
             * the software keyboard will emulate the Shift key press.
             * However, in the case of a non-English keyboard layout, KeyEvent::getUnicodeChar()
             * will not return 0 symbol for non-English keyboard layouts, keycode also will be set to 0,
             * action will be set to KeyEvent.ACTION_MULTIPLE and the symbol data can only be retrieved
             * by calling the KeyEvent::getCharacters() method.
             *
             * For special keys like Tab and Return, both the key code and Unicode symbol are set in
             * the KeyEvent object. However, this is not the case for all keys, so to determine whether
             * a key is special or not, we will rely only on the keycode value.
             *
             * Android sends emulated Shift key press event along with some key events.
             * Since we cannot determine the event source from JNI, we ignore the emulated Shift
             * key press in JNI. In this case, it becomes much easier to handle Shift key press events
             * coming from a physical keyboard or ExtraKeysView.
             *
             * To avoid handling modifier states we will simply ignore modifier keys and
             * ACTION_DOWN if they come from soft keyboard.
             *
             */
            if (e.getDevice() == null || e.getDevice().isVirtual()) {
                if (e.getAction() == KeyEvent.ACTION_UP || e.getAction() == KeyEvent.ACTION_MULTIPLE) {
                    switch (keyCode) {
                        case KeyEvent.KEYCODE_SHIFT_LEFT:
                        case KeyEvent.KEYCODE_SHIFT_RIGHT:
                        case KeyEvent.KEYCODE_ALT_LEFT:
                        case KeyEvent.KEYCODE_ALT_RIGHT:
                        case KeyEvent.KEYCODE_CTRL_LEFT:
                        case KeyEvent.KEYCODE_CTRL_RIGHT:
                        case KeyEvent.KEYCODE_META_LEFT:
                        case KeyEvent.KEYCODE_META_RIGHT:
                            break;
                        default: {
                            int unicode = e.getUnicodeChar();
                            if (unicode != 0 && e.getMetaState() == 0) {
                                onKeySym(0, unicode, 0);
                                return true;
                            }

                            if (keyCode != 0) {
                                onKeySym(keyCode, unicode, 0);
                            }

                            if (e.getCharacters() != null)
                                e.getCharacters().codePoints().forEach(codePoint -> onKeySym(0, codePoint, 0));
                        }
                    }
                }
            } else
            /**
             * Android does not send us non-latin symbols from physical keyboard
             * so we can trust those events and not emulate typing.
             */
                switch(e.getAction()) {
                    case KeyEvent.ACTION_DOWN:
                        MainActivity.this.onKey(keyCode, KeyPress);
                        break;
                    case KeyEvent.ACTION_UP:
                        MainActivity.this.onKey(keyCode, KeyRelease);
                        break;
                }
            return true;
        }
    }

    /**
     * Manually toggle soft keyboard visibility
     * @param context calling context
     */
    public static void toggleKeyboardVisibility(Context context) {
        InputMethodManager inputMethodManager = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if(inputMethodManager != null)
            inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
    }

    @SuppressWarnings("unused")
    // It is used in native code
    void clientConnectedStateChanged(boolean connected) {
        runOnUiThread(()-> {
            mClientConnected = connected;
            toggleExtraKeys(connected);
            findViewById(R.id.stub).setVisibility(connected?View.INVISIBLE:View.VISIBLE);
            findViewById(R.id.lorieView).setVisibility(connected?View.VISIBLE:View.INVISIBLE);
            findViewById(R.id.cursorView).setVisibility(connected?View.VISIBLE:View.INVISIBLE);

            // We should recover connectin in the case if file descriptor for some reason was broken...
            if (!connected)
                tryConnect();
        });
    }

    @SuppressWarnings("unused")
    // It is used in native code
    void setCursorVisibility(boolean visible) {
        View cursor = findViewById(R.id.cursorView);
        int visibility = visible?View.VISIBLE:View.INVISIBLE;
        if (cursor.getVisibility() != visibility)
            runOnUiThread(()-> findViewById(R.id.cursorView).setVisibility(visibility));
    }

    @SuppressWarnings("unused")
    // It is used in native code
    void setCursorCoordinates(int x, int y) {
        mTP.setCursorCoordinates(x, y);
    }

    @SuppressWarnings("unused")
    // It is used in native code
    void moveCursorRect(int x, int y, int w, int h) {
        runOnUiThread(()-> {
            SurfaceView v = findViewById(R.id.cursorView);
            //int W = (int) (w * mDpi.x / 120);
            //int H = (int) (h * mDpi.y / 120);
            //Log.v("cursor", "w " + W + " h " + H + " dpix " + mDpi.x + " dpiy " + mDpi.y);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(w, h);
            params.setMargins(x, y, 0, 0);

            v.setLayoutParams(params);
            v.setVisibility(View.VISIBLE);
        });
    }

    @SuppressWarnings("unused")
    // It is used in native code
    void setClipboardText(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("X11 clipboard", text));
    }

    public static Handler handler = new Handler();

    private native void init();
    private native void connect(int fd);
    private native void cursorChanged(Surface surface);
    private native void windowChanged(Surface surface, int width, int height, int realWidth, int realHeight);
    public native void onPointerMotion(int x, int y);
    public native void onPointerScroll(int axis, float value);

    public native void onPointerButton(int button, int type);
    public native void onKeySym(int keyCode, int unicode, int metaState);
    public native void onKey(int keyCode, int type);
    private native void nativeResume();
    private native void nativePause();

    private native void startLogcat(int fd);
    private native void setHorizontalScrollEnabled(boolean enabled);
    private native void setClipboardSyncEnabled(boolean enabled);

    static {
        System.loadLibrary("lorie");
    }
}
