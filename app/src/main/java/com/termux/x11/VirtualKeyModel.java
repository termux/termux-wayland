package com.termux.x11;

public class VirtualKeyModel {
    private String keyName;

    public VirtualKeyModel(String keyName) {
        this.keyName = keyName;
    }

    public String getKeyName() {
        return keyName;
    }

    public void setKeyName(String keyName) {
        this.keyName = keyName;
    }
}
