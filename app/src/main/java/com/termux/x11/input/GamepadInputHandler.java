package com.termux.x11.input;

import android.content.Context;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.termux.x11.LorieView;
import com.termux.x11.MainActivity;
import com.termux.x11.R;

public class GamepadInputHandler {
    private Context context;

    public GamepadInputHandler(Context context) {
        this.context = context;
    }

    public void setupGamepadInput() {
        Log.d("GamepadInput", "🎮 GamepadInputHandler inițializat.");
    }

    public boolean handleKeyDown(int keyCode, KeyEvent event) {
        Log.d("DEBUG", "✅ Controller");
        if (keyCode != -1) {
            sendGamepadEvent(keyCode, true);
            return true;
        }

        return false;
    }

    public boolean handleKeyUp(int keyCode, KeyEvent event) {
        if (keyCode != -1) {
            sendGamepadEvent(keyCode, false);
            return true;
        }
        return false;
    }

    public boolean handleGenericMotionEvent(MotionEvent event) {
        if (event.getSource() == InputDevice.SOURCE_JOYSTICK || event.getSource() == InputDevice.SOURCE_GAMEPAD) {
            // Trimite separat fiecare set de axe cu un ID unic
            sendGamepadAxisEvent(event.getAxisValue(MotionEvent.AXIS_X),
                    event.getAxisValue(MotionEvent.AXIS_Y), 0); // Stick Stânga
            sendGamepadAxisEvent(event.getAxisValue(MotionEvent.AXIS_Z),
                    event.getAxisValue(MotionEvent.AXIS_RZ), 1); // Stick Dreapta
            sendGamepadAxisEvent(event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                    event.getAxisValue(MotionEvent.AXIS_RTRIGGER), 2); // Triggers (L2/R2)
            sendGamepadAxisEvent(event.getAxisValue(MotionEvent.AXIS_HAT_X),
                    event.getAxisValue(MotionEvent.AXIS_HAT_Y), 3); // D-Pad

            return true;
        }
        return false;
    }



    private void sendGamepadEvent(int button, boolean pressed) {
        LorieView lorieView = ((MainActivity) context).findViewById(R.id.lorieView);
        if (lorieView == null) {
            Log.e("GamepadInput", "❌ Eroare: LorieView nu a fost găsit!");
            return;
        }

        lorieView.sendGamepadEvent(button, pressed, 0.0F, 0.0F ,0);
        Log.d("GamepadInput", "🎮 Buton gamepad: " + button + " Apăsat: " + pressed);
    }

    private void sendGamepadAxisEvent(float axisX, float axisY, int axisID) {
        LorieView lorieView = ((MainActivity) context).findViewById(R.id.lorieView);
        if (lorieView == null) {
            Log.e("GamepadInput", "❌ Eroare: LorieView nu a fost găsit!");
            return;
        }

        lorieView.sendGamepadEvent(0, false, axisX, axisY, axisID);
        Log.d("GamepadInput", "🎮 Stickuri & Triggere trimise.");
    }
}
