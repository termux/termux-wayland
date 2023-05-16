package com.termux.x11;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.preference.Preference;

import android.os.Handler;
import android.preference.PreferenceManager;
import android.provider.Settings;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.SeekBarPreference;

import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.x11.utils.ExtraKeyConfigPreference;

import java.util.Objects;
import java.util.regex.PatternSyntaxException;

@SuppressWarnings("deprecation")
public class LoriePreferences extends AppCompatActivity {

    static final String ACTION_PREFERENCES_CHANGED = "com.termux.x11.ACTION_PREFERENCES_CHANGED";
    static final String SHOW_IME_WITH_HARD_KEYBOARD = "show_ime_with_hard_keyboard";
    LoriePreferenceFragment loriePreferenceFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loriePreferenceFragment = new LoriePreferenceFragment();
        getSupportFragmentManager().beginTransaction().replace(android.R.id.content, loriePreferenceFragment).commit();

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeButtonEnabled(true);
            actionBar.setTitle("Preferences");
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == android.R.id.home) {
            finish();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public static class LoriePreferenceFragment extends PreferenceFragmentCompat implements OnPreferenceChangeListener, Preference.OnPreferenceClickListener {
        @Override
        public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
            addPreferencesFromResource(R.xml.preferences);
        }

        @SuppressWarnings("ConstantConditions")
        void updatePreferencesLayout() {
            SharedPreferences preferences = getPreferenceManager().getSharedPreferences();
            SeekBarPreference scalePreference = findPreference("displayScale");
            scalePreference.setMin(30);
            scalePreference.setMax(200);
            scalePreference.setSeekBarIncrement(10);
            scalePreference.setShowSeekBarValue(true);

            switch (preferences.getString("displayResolutionMode", "native")) {
                case "scaled":
                    findPreference("displayScale").setVisible(true);
                    findPreference("displayResolutionExact").setVisible(false);
                    findPreference("displayResolutionCustom").setVisible(false);
                    break;
                case "exact":
                    findPreference("displayScale").setVisible(false);
                    findPreference("displayResolutionExact").setVisible(true);
                    findPreference("displayResolutionCustom").setVisible(false);
                    break;
                case "custom":
                    findPreference("displayScale").setVisible(false);
                    findPreference("displayResolutionExact").setVisible(false);
                    findPreference("displayResolutionCustom").setVisible(true);
                    break;
                default:
                    findPreference("displayScale").setVisible(false);
                    findPreference("displayResolutionExact").setVisible(false);
                    findPreference("displayResolutionCustom").setVisible(false);
            }
        }

        @Override
        public void onCreate(final Bundle savedInstanceState)
        {
            super.onCreate(savedInstanceState);
            SharedPreferences preferences = getPreferenceManager().getSharedPreferences();

            String showImeEnabled = Settings.Secure.getString(requireActivity().getContentResolver(), SHOW_IME_WITH_HARD_KEYBOARD);
            if (showImeEnabled == null) showImeEnabled = "0";
            SharedPreferences.Editor p = Objects.requireNonNull(preferences).edit();
            p.putBoolean("showIMEWhileExternalConnected", showImeEnabled.equals("1"));
            p.apply();

            PreferenceScreen s = getPreferenceScreen();
            for (int i=0; i<s.getPreferenceCount(); i++) {
                s.getPreference(i).setOnPreferenceChangeListener(this);
                s.getPreference(i).setOnPreferenceClickListener(this);
            }

            updatePreferencesLayout();
        }

        @Override
        public boolean onPreferenceClick(@NonNull Preference preference) {
            updatePreferencesLayout();
            return false;
        }

        @SuppressLint("ApplySharedPref")
        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            String key = preference.getKey();
            Log.e("Preferences", "changed preference: " + key);
            handler.postDelayed(this::updatePreferencesLayout, 100);

            if ("showIMEWhileExternalConnected".equals(key)) {
                boolean enabled = newValue.toString().equals("true");
                try {
                    Settings.Secure.putString(requireActivity().getContentResolver(), SHOW_IME_WITH_HARD_KEYBOARD, enabled ? "1" : "0");
                } catch (Exception e) {
                    if (e instanceof SecurityException) {
                        new AlertDialog.Builder(requireActivity())
                                .setTitle("Permission denied")
                                .setMessage("Android requires WRITE_SECURE_SETTINGS permission to change this setting.\n" +
                                            "Please, launch this command using ADB:\n" +
                                            "adb shell pm grant com.termux.x11 android.permission.WRITE_SECURE_SETTINGS")
                                .setNegativeButton("OK", null)
                                .create()
                                .show();
                    } else e.printStackTrace();
                    return false;
                }
            }

            if ("displayScale".equals(key)) {
                int scale = (Integer) newValue;
                if (scale % 10 != 0) {
                    scale = Math.round( ( (float) scale ) / 10 ) * 10;
                    ((SeekBarPreference) preference).setValue(scale);
                    return false;
                }
            }

            if ("displayDensity".equals(key)) {
                int v;
                try {
                    v = Integer.parseInt((String) newValue);
                } catch (NumberFormatException | PatternSyntaxException ignored) {
                    Toast.makeText(getActivity(), "This field accepts only numerics between 96 and 800", Toast.LENGTH_SHORT).show();
                    return false;
                }

                return (v > 96 && v < 800);
            }

            if ("displayResolutionCustom".equals(key)) {
                String value = (String) newValue;
                try {
                    String[] resolution = value.split("x");
                    Integer.parseInt(resolution[0]);
                    Integer.parseInt(resolution[1]);
                } catch (NumberFormatException | PatternSyntaxException ignored) {
                    Toast.makeText(getActivity(), "Wrong resolution format", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }

            Intent intent = new Intent(ACTION_PREFERENCES_CHANGED);
            intent.setPackage("com.termux.x11");
            requireContext().sendBroadcast(intent);
            return true;
        }

        @Override
        public void onDisplayPreferenceDialog(@NonNull Preference preference) {
            if (preference instanceof ExtraKeyConfigPreference) {
                ExtraKeysConfigFragment f = new ExtraKeysConfigFragment();
                f.setTargetFragment(this, 0);
                assert getFragmentManager() != null;
                f.show(getFragmentManager(), null);
            } else super.onDisplayPreferenceDialog(preference);
        }

        public static class ExtraKeysConfigFragment extends DialogFragment {

            @NonNull
            @Override
            public Dialog onCreateDialog(Bundle savedInstanceState) {
                @SuppressLint("InflateParams")
                View view = getLayoutInflater().inflate(R.layout.extra_keys_config, null, false);
                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
                EditText config = view.findViewById(R.id.extra_keys_config);
                config.setTypeface(Typeface.MONOSPACE);
                config.setText(preferences.getString("extra_keys_config", TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS));
                TextView desc = view.findViewById(R.id.extra_keys_config_description);
                desc.setLinksClickable(true);
                desc.setText(R.string.extra_keys_config_desc);
                desc.setMovementMethod(LinkMovementMethod.getInstance());
                return new android.app.AlertDialog.Builder(getActivity())
                        .setView(view)
                        .setTitle("Extra keys config")
                        .setPositiveButton("OK",
                                (dialog, whichButton) -> {
                                    String text = config.getText().toString();
                                    text = text.length() > 0 ? text : TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS;
                                    preferences
                                            .edit()
                                            .putString("extra_keys_config", text)
                                            .apply();
                                }
                        )
                        .setNegativeButton("Cancel",
                                (dialog, whichButton) -> dialog.dismiss()
                        )
                        .create();
            }
        }

    }

    static Handler handler = new Handler();
}
