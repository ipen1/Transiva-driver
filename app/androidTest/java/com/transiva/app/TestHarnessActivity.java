package com.transiva.app;

import android.app.Activity;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class TestHarnessActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        DriverThemeEngine.applySystemBars(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DriverThemeTokens.background(this));
        TextView title = new TextView(this); title.setText("Transiva Test Harness");
        DriverThemeEngine.applyText(title, true); root.addView(title);
        EditText input = new EditText(this); input.setHint("Input");
        DriverThemeEngine.applyInput(input); root.addView(input);
        setContentView(root);
    }
}
