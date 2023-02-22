package com.termux.x11;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.Settings;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;

import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import com.termux.x11.utils.KeyboardUtils;
import com.termux.x11.utils.SamsungDexUtils;


@SuppressWarnings({"ConstantConditions", "SameParameterValue", "SdCardPath"})
@SuppressLint({"ClickableViewAccessibility", "StaticFieldLeak"})
public class LorieService extends Service implements View.OnApplyWindowInsetsListener {
    static final String LAUNCHED_BY_COMPATION = "com.termux.x11.launched_by_companion";
    static final String ACTION_STOP_SERVICE = "com.termux.x11.service_stop";
    static final String ACTION_START_FROM_ACTIVITY = "com.termux.x11.start_from_activity";
    static final String ACTION_START_PREFERENCES_ACTIVITY = "com.termux.x11.start_preferences_activity";
    static final String ACTION_PREFERENCES_CHANGED = "com.termux.x11.preferences_changed";

    private static LorieService instance = null;
    long compositor;
    private static final ServiceEventListener listener = new ServiceEventListener();
    private static MainActivity act;

    private TouchParser mTP;

    public LorieService(){
        instance = this;
    }

    static void setMainActivity(MainActivity activity) {
        act = activity;
    }

    static void start(String action) {
        Intent intent = new Intent(act, LorieService.class);
        intent.setAction(action);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            act.startForegroundService(intent);
        } else {
            act.startService(intent);
        }
    }

    @SuppressLint({"BatteryLife", "ObsoleteSdkInt"})
    @Override
    public void onCreate() {

        if (isServiceRunningInForeground(this, LorieService.class)) return;

        compositor = createLorieThread();
        if (compositor == 0) {
            Log.e("LorieService", "compositor thread was not created");
            return;
        }

        instance = this;
        Log.e("LorieService", "created");

        Intent notificationIntent = new Intent(getApplicationContext(), MainActivity.class);
        notificationIntent.putExtra("foo_bar_extra_key", "foo_bar_extra_value");
        notificationIntent.setAction(Long.toString(System.currentTimeMillis()));

        Intent exitIntent = new Intent(getApplicationContext(), LorieService.class);
        exitIntent.setAction(ACTION_STOP_SERVICE);

        Intent preferencesIntent = new Intent(getApplicationContext(), LoriePreferences.class);
        preferencesIntent.setAction(ACTION_START_PREFERENCES_ACTIVITY);

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pendingExitIntent = PendingIntent.getService(getApplicationContext(), 0, exitIntent, PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pendingPreferencesIntent = PendingIntent.getActivity(getApplicationContext(), 0, preferencesIntent, PendingIntent.FLAG_IMMUTABLE);

        //For creating the Foreground Service
        int priority = Notification.PRIORITY_HIGH;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N)
                priority = NotificationManager.IMPORTANCE_HIGH;
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        String channelId = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? getNotificationChannel(notificationManager) : "";
        Notification notification = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Termux:X11")
                .setSmallIcon(R.drawable.ic_x11_icon)
                .setContentText("Pull down to show options")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(priority)
                .setShowWhen(false)
                .setColor(0xFF607D8B)
                .addAction(0, "Preferences", pendingPreferencesIntent)
                .addAction(0, "Exit", pendingExitIntent)
                .build();
        startForeground(1, notification);

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String packageName = getPackageName();
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                Intent whitelist = new Intent();
                whitelist.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                whitelist.setData(Uri.parse("package:" + packageName));
                whitelist.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(whitelist);
            }
        }
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

    public static boolean isServiceRunningInForeground(Context context, Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return service.foreground;
            }
        }
        return false;
    }

    private void onPreferencesChanged() {
        if (instance == null || act == null) return;

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(instance);

        int mode = Integer.parseInt(preferences.getString("touchMode", "1"));
        instance.mTP.setMode(mode);

        act.getWindow().getDecorView().setOnApplyWindowInsetsListener(act);

        if (preferences.getBoolean("showAdditionalKbd", true))
            act.kbd.setVisibility(View.VISIBLE);
        else
            act.kbd.setVisibility(View.INVISIBLE);

        if (preferences.getBoolean("dexMetaKeyCapture", false)) {
            SamsungDexUtils.dexMetaKeyCapture(act, false);
        }

        Log.e("LorieService", "Preferences changed");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.e("LorieService", "start");
        String action = intent.getAction();

        if (action.equals(ACTION_START_FROM_ACTIVITY)) {
            act.onLorieServiceStart(this);
        }

        if (action.equals(ACTION_STOP_SERVICE)) {
            Log.e("LorieService", action);
            terminate();
            act.finish();
            stopSelf();
        }

        onPreferencesChanged();

        return START_REDELIVER_INTENT;
    }

    private static void sendRunCommandInternal(Context ctx) {
        Intent intent = new Intent();
        intent.setClassName("com.termux", "com.termux.app.RunCommandService");
        intent.setAction("com.termux.RUN_COMMAND");
        intent.putExtra("com.termux.RUN_COMMAND_PATH",
                "/data/data/com.termux/files/usr/libexec/termux-x11/termux-startx11");
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        Log.d("LorieService", "sendRunCommand: " + intent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }

    public static void sendRunCommand(AppCompatActivity act) {
        final String ERROR_MESSAGE =
                "It is impossible to start without " +
                "com.termux.permission.RUN_COMMAND permission. " +
                "Sorry.";
        if (act.checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED) {
            LorieService.sendRunCommandInternal(act);
        } else {
            Log.d("MainActivity", "We have no permission to sendRunCommand(). Requesting it.");
            ActivityResultLauncher<String> requestPermissionLauncher =
                    act.registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                        if (isGranted) {
                            sendRunCommandInternal(act);
                        } else {
                            new AlertDialog.Builder(act)
                                .setTitle("Insufficient permission")
                                .setMessage(ERROR_MESSAGE)
                                .setPositiveButton(android.R.string.yes,
                                        (dialog, which) -> act.finish())
                                .setIcon(android.R.drawable.ic_dialog_alert)
                                .show();

                        }
                    });
            requestPermissionLauncher.launch("com.termux.permission.RUN_COMMAND");
        }
    }


    @Override
    public void onDestroy() {
        Log.e("LorieService", "destroyed");
    }

    static LorieService getInstance() {
        if (instance == null) {
            Log.e("LorieService", "Instance was requested, but no instances available");
        }
        return instance;
    }

    void setListeners(@NonNull SurfaceView view, @NonNull SurfaceView cursor) {
        Context a = view.getRootView().findViewById(android.R.id.content).getContext();
        if (!(a instanceof MainActivity)) {
            Log.e("LorieService", "Context is not an activity!!!");
        }

        act = (MainActivity) a;

        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.requestFocus();

        listener.svc = this;
        listener.setAsListenerTo(view, cursor);

        mTP = new TouchParser(view, listener);
        onPreferencesChanged();
    }

    static View.OnKeyListener getOnKeyListener() {
        return listener;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressWarnings("SameParameterValue")
    private static class ServiceEventListener implements SurfaceHolder.Callback, View.OnTouchListener, View.OnKeyListener, View.OnHoverListener, View.OnGenericMotionListener, TouchParser.OnTouchParseListener {
        LorieService svc;

        @SuppressLint("WrongConstant")
        private void setAsListenerTo(SurfaceView view, SurfaceView cursor) {
            view.getHolder().addCallback(this);
            view.setOnTouchListener(this);
            view.setOnHoverListener(this);
            view.setOnGenericMotionListener(this);
            view.setOnKeyListener(this);
            surfaceChanged(view.getHolder(), PixelFormat.UNKNOWN, view.getWidth(), view.getHeight());

            cursor.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(@NonNull SurfaceHolder holder) {
                    cursor.getHolder().setFormat(PixelFormat.TRANSLUCENT);
                }
                @Override
                public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                    cursor.getHolder().setFormat(PixelFormat.TRANSLUCENT);
                    svc.cursorChanged(holder.getSurface());
                }
                @Override public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                    svc.cursorChanged(null);
                }
            });
        }

        public void onPointerButton(int button, int state) {
            if (svc == null) return;
            svc.pointerButton(button, state);
        }

        public void onPointerMotion(int x, int y) {
            if (svc == null) return;
            svc.pointerMotion(x, y);
        }

        public void onPointerScroll(int axis, float value) {
            if (svc == null) return;
            svc.pointerScroll(axis, value);
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (svc == null) return false;
            return svc.mTP.onTouchEvent(e);
        }

        @Override
        public boolean onGenericMotion(View v, MotionEvent e) {
            if (svc == null) return false;
            return svc.mTP.onTouchEvent(e);
        }

        @Override
        public boolean onHover(View v, MotionEvent e) {
            if (svc == null) return false;
            return svc.mTP.onTouchEvent(e);
        }

        private boolean isSource(KeyEvent e, int source) {
            return (e.getSource() & source) == source;
        }

        private boolean rightPressed = false; // Prevent right button press event from being repeated
        private boolean middlePressed = false; // Prevent middle button press event from being repeated
        @Override
        public boolean onKey(View v, int keyCode, KeyEvent e) {
            if (svc == null) return false;
            int action = 0;

            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (
                        isSource(e, InputDevice.SOURCE_MOUSE) &&
                        rightPressed != (e.getAction() == KeyEvent.ACTION_DOWN)
                   ) {
                    svc.pointerButton(TouchParser.BTN_RIGHT, (e.getAction() == KeyEvent.ACTION_DOWN) ? TouchParser.ACTION_DOWN : TouchParser.ACTION_UP);
                    rightPressed = (e.getAction() == KeyEvent.ACTION_DOWN);
                } else if (e.getAction() == KeyEvent.ACTION_UP) {
                    KeyboardUtils.toggleKeyboardVisibility(act);
                    if (act.kbd!=null)
                        act.kbd.requestFocus();

                }
                return true;
            }

            if (
                    keyCode == KeyEvent.KEYCODE_MENU &&
                    isSource(e, InputDevice.SOURCE_MOUSE) &&
                    middlePressed != (e.getAction() == KeyEvent.ACTION_DOWN)
               ) {
                svc.pointerButton(TouchParser.BTN_MIDDLE, (e.getAction() == KeyEvent.ACTION_DOWN) ? TouchParser.ACTION_DOWN : TouchParser.ACTION_UP);
                middlePressed = (e.getAction() == KeyEvent.ACTION_DOWN);
                return true;
            }

            if (e.getAction() == KeyEvent.ACTION_DOWN) action = TouchParser.ACTION_DOWN;
            if (e.getAction() == KeyEvent.ACTION_UP) action = TouchParser.ACTION_UP;
            svc.keyboardKey(action, keyCode, e.isShiftPressed() ? 1 : 0, e.getCharacters());
            return true;
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            svc.windowChanged(holder.getSurface(), width, height);
        }

        @Override public void surfaceCreated(SurfaceHolder holder) {}
        @SuppressLint("WrongConstant")
        @Override public void surfaceDestroyed(SurfaceHolder holder) {
            surfaceChanged(holder, PixelFormat.UNKNOWN, 0, 0);
        }

    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    static final Handler handler = new Handler();
    private abstract static class Task implements Runnable {
        public Task() {
            handler.post(this);
        }
        @Override
        public void run() {
            LorieService svc = getInstance();
            if (svc == null || svc.compositor == 0 || act == null) {
                handler.postDelayed(this, 100);
                return;
            }

            run(svc);
        }
        public abstract void run(LorieService svc);
    }

    public static void adoptWaylandFd(int fd) {
        new Task() {
            @Override
            public void run(LorieService svc) {
                svc.passWaylandFD(fd);
            }
        };
    }

    @SuppressLint("WrongConstant")
    @Override
    public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
        SurfaceView c = v.getRootView().findViewById(R.id.lorieView);
        SurfaceHolder h = (c != null) ? c.getHolder() : null;
        if (h != null)
            handler.postDelayed(() -> listener.surfaceChanged(h, PixelFormat.UNKNOWN, 0, 0), 100);
        return insets;
    }

    @SuppressWarnings("unused")
    // It is used in native code
    void setRendererVisibility(boolean visible) {
        act.runOnUiThread(()-> {
            act.findViewById(R.id.stub).setVisibility(visible?View.INVISIBLE:View.VISIBLE);
            act.findViewById(R.id.lorieView).setVisibility(visible?View.VISIBLE:View.INVISIBLE);
        });
    }

    @SuppressWarnings("unused")
    // It is used in native code
    void setCursorVisibility(boolean visible) {
        act.runOnUiThread(()-> act.findViewById(R.id.cursorView).setVisibility(visible?View.VISIBLE:View.INVISIBLE));
    }

    @SuppressWarnings("unused")
    // It is used in native code
    void setCursorRect(int x, int y, int w, int h) {
        act.runOnUiThread(()-> {
            SurfaceView v = act.findViewById(R.id.cursorView);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(w, h);
            params.setMargins(x, y, 0, 0);

            v.setLayoutParams(params);
            v.setVisibility(View.VISIBLE);
        });
    }

    private native void cursorChanged(Surface surface);
    private native void windowChanged(Surface surface, int width, int height);
    private native void pointerMotion(int x, int y);
    private native void pointerScroll(int axis, float value);
    private native void pointerButton(int button, int type);
    private native void keyboardKey(int key, int type, int shift, String characters);
    private native void passWaylandFD(int fd);
    private native long createLorieThread();    
    private native void terminate();
    public static native void startLogcatForFd(int fd);

    static {
        System.loadLibrary("lorie");
    }
}
