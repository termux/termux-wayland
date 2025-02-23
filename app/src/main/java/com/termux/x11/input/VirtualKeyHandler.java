package com.termux.x11.input;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import com.termux.x11.LorieView;
import com.termux.x11.MainActivity;
import com.termux.x11.R;

public class VirtualKeyHandler {
    private Context context;

    public VirtualKeyHandler(Context context) {
        this.context = context;
    }

    @SuppressLint("ClickableViewAccessibility")
    public void setupInputForButton(Button button) {
        button.setOnTouchListener((v, event) -> {
            String selectedKey = (String) button.getTag();
            if (selectedKey == null) {
                return false;
            }

            int keyCode = getKeyEventCode(selectedKey);
            if (keyCode == -1) {
                return false;
            }

            LorieView lorieView = ((MainActivity) context).findViewById(R.id.lorieView);
            if (lorieView == null) {
                return false;
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lorieView.sendKeyEvent(keyCode, keyCode, true);
                    v.performClick();
                    break;

                case MotionEvent.ACTION_MOVE:
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    lorieView.sendKeyEvent(keyCode, keyCode, false);
                    break;
            }
            return false;
        });
    }



    private int getKeyEventCode(String key) {
        switch (key) {
            // Litere
            case "A": return 30;
            case "B": return 48;
            case "C": return 46;
            case "D": return 32;
            case "E": return 18;
            case "F": return 33;
            case "G": return 34;
            case "H": return 35;
            case "I": return 23;
            case "J": return 36;
            case "K": return 37;
            case "L": return 38;
            case "M": return 50;
            case "N": return 49;
            case "O": return 24;
            case "P": return 25;
            case "Q": return 16;
            case "R": return 19;
            case "S": return 31;
            case "T": return 20;
            case "U": return 22;
            case "V": return 47;
            case "W": return 17;
            case "X": return 45;
            case "Y": return 21;
            case "Z": return 44;

            // Cifre
            case "0": return 11;
            case "1": return 2;
            case "2": return 3;
            case "3": return 4;
            case "4": return 5;
            case "5": return 6;
            case "6": return 7;
            case "7": return 8;
            case "8": return 9;
            case "9": return 10;

            // Taste speciale
            case "Space": return 57;
            case "Enter": return 28;
            case "Backspace": return 14;
            case "Tab": return 15;
            case "Escape": return 1;
            case "Delete": return 111;
            case "Insert": return 110;
            case "Home": return 102;
            case "End": return 107;
            case "Page Up": return 104;
            case "Page Down": return 109;

            // Taste de navigare (săgeți)
            case "↑": return 103;
            case "↓": return 108;
            case "←": return 105;
            case "→": return 106;

            // Combinații de taste (modificatori)
            case "Ctrl": return 29;
            case "Shift": return 42;
            case "Alt": return 56;

            // Taste funcție
            case "F1": return 59;
            case "F2": return 60;
            case "F3": return 61;
            case "F4": return 62;
            case "F5": return 63;
            case "F6": return 64;
            case "F7": return 65;
            case "F8": return 66;
            case "F9": return 67;
            case "F10": return 68;
            case "F11": return 87;
            case "F12": return 88;

            // Altele
            case "`": return 41;
            case "-": return 12;
            case "=": return 13;
            case "[": return 26;
            case "]": return 27;
            case "\\": return 43;
            case ";": return 39;
            case "'": return 40;
            case ",": return 51;
            case ".": return 52;
            case "/": return 53;

            // Scan code necunoscut
            default: return 0;
        }
    }
}
