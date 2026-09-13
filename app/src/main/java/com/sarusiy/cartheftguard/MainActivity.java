package com.sarusiy.cartheftguard;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.tabs.TabLayout;
import com.sarusiy.cartheftguard.ui.AboutFragment;
import com.sarusiy.cartheftguard.ui.ConnectFragment;
import com.sarusiy.cartheftguard.ui.ControlFragment;
import com.sarusiy.cartheftguard.ui.FaultsFragment;
import com.sarusiy.cartheftguard.ui.FiatMonitorFragment;
import com.sarusiy.cartheftguard.ui.LearnFragment;
import com.sarusiy.cartheftguard.ui.MonitorFragment;
import com.sarusiy.cartheftguard.ui.RecordFragment;
import com.sarusiy.cartheftguard.ui.TrackFragment;

/**
 * Thin host Activity: owns the tab bar and swaps between the top-level
 * screens. All BLE/Wi-Fi state lives in {@link BoardLink}, shared across
 * fragments, not here.
 *
 * Uses a scrollable TabLayout rather than BottomNavigationView: the latter
 * hard-caps at 5 destinations and throws IllegalArgumentException past that
 * (see NavigationBarMenu#addInternal) -- this app already has 6 tabs and is
 * expected to grow more over time.
 */
public class MainActivity extends AppCompatActivity {
    private static final int FRAGMENT_CONTAINER_ID = View.generateViewId();

    private static final String[] TAB_TITLES = {
            "Connect", "Monitor", "Fiat", "Track", "Control", "Record", "Faults", "Learn", "About"
    };
    private static final int[] TAB_ICONS = {
            android.R.drawable.stat_sys_data_bluetooth,
            android.R.drawable.ic_menu_view,
            android.R.drawable.ic_menu_compass,
            android.R.drawable.ic_dialog_map,
            android.R.drawable.ic_menu_preferences,
            android.R.drawable.ic_menu_save,
            android.R.drawable.ic_dialog_alert,
            android.R.drawable.ic_menu_help,
            android.R.drawable.ic_menu_info_details,
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout fragmentContainer = new FrameLayout(this);
        fragmentContainer.setId(FRAGMENT_CONTAINER_ID);

        TabLayout tabLayout = new TabLayout(this);
        tabLayout.setTabMode(TabLayout.MODE_SCROLLABLE);
        tabLayout.setTabGravity(TabLayout.GRAVITY_START);
        for (int i = 0; i < TAB_TITLES.length; i++) {
            tabLayout.addTab(tabLayout.newTab().setText(TAB_TITLES[i]).setIcon(TAB_ICONS[i]));
        }
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showFragment(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(fragmentContainer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(tabLayout, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        /* API 35 (this app's targetSdk) enforces edge-to-edge by default, so without
         * this the tab bar sits flush against the bottom edge -- inside the system's
         * gesture-navigation zone on devices like this one. Taps there get eaten by
         * the "go home" gesture instead of reaching the tab (looks like the app
         * randomly minimizes when tapping a tab near the bottom). Pad the tab bar by
         * the system bar/gesture inset so it sits above that zone. */
        ViewCompat.setOnApplyWindowInsetsListener(tabLayout, (view, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.systemGestures()).bottom;
            view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), bottom);
            return insets;
        });

        if (savedInstanceState == null) {
            showFragment(0);
        }
    }

    private void showFragment(int position) {
        Fragment fragment = createFragment(position);
        if (fragment == null) {
            return;
        }
        getSupportFragmentManager().beginTransaction()
                .replace(FRAGMENT_CONTAINER_ID, fragment)
                .commit();
    }

    private Fragment createFragment(int position) {
        switch (position) {
            case 0: return new ConnectFragment();
            case 1: return new MonitorFragment();
            case 2: return new FiatMonitorFragment();
            case 3: return new TrackFragment();
            case 4: return new ControlFragment();
            case 5: return new RecordFragment();
            case 6: return new FaultsFragment();
            case 7: return new LearnFragment();
            case 8: return new AboutFragment();
            default: return null;
        }
    }
}
