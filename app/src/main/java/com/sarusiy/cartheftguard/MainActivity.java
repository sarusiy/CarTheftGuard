package com.sarusiy.cartheftguard;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.sarusiy.cartheftguard.ui.AboutFragment;
import com.sarusiy.cartheftguard.ui.ConnectFragment;
import com.sarusiy.cartheftguard.ui.ControlFragment;
import com.sarusiy.cartheftguard.ui.MonitorFragment;
import com.sarusiy.cartheftguard.ui.RecordFragment;

/**
 * Thin host Activity: owns the bottom navigation bar and swaps between the
 * four top-level screens (Connect / Monitor / Control / About). All BLE/Wi-Fi
 * state lives in {@link BoardLink}, shared across fragments, not here.
 */
public class MainActivity extends AppCompatActivity {
    private static final int FRAGMENT_CONTAINER_ID = View.generateViewId();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout fragmentContainer = new FrameLayout(this);
        fragmentContainer.setId(FRAGMENT_CONTAINER_ID);

        BottomNavigationView bottomNav = new BottomNavigationView(this);
        bottomNav.inflateMenu(R.menu.bottom_nav_menu);
        bottomNav.setOnItemSelectedListener(item -> {
            Fragment fragment = createFragment(item.getItemId());
            if (fragment == null) {
                return false;
            }
            getSupportFragmentManager().beginTransaction()
                    .replace(FRAGMENT_CONTAINER_ID, fragment)
                    .commit();
            return true;
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(fragmentContainer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(bottomNav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        if (savedInstanceState == null) {
            bottomNav.setSelectedItemId(R.id.nav_connect);
        }
    }

    private Fragment createFragment(int itemId) {
        if (itemId == R.id.nav_connect) {
            return new ConnectFragment();
        }
        if (itemId == R.id.nav_monitor) {
            return new MonitorFragment();
        }
        if (itemId == R.id.nav_control) {
            return new ControlFragment();
        }
        if (itemId == R.id.nav_record) {
            return new RecordFragment();
        }
        if (itemId == R.id.nav_about) {
            return new AboutFragment();
        }
        return null;
    }
}
