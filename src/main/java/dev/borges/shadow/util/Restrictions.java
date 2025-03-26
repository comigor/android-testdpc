package dev.borges.shadow.util;

import android.annotation.TargetApi;
import android.os.Build;
import android.os.UserManager;

import java.util.Arrays;
import java.util.List;

@TargetApi(Build.VERSION_CODES.P)
public class Restrictions {
    public static final List<String> DEFAULT_RESTRICTIONS = Arrays.asList(
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_REMOVE_USER,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_CONFIG_LOCATION,
            UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
            UserManager.DISALLOW_NETWORK_RESET,
            UserManager.DISALLOW_AIRPLANE_MODE
            // UserManager.DISALLOW_DEBUGGING_FEATURES,
    );

    public static final String[] THEFT_MODE_RESTRICTIONS = new String[]{
            UserManager.DISALLOW_ADJUST_VOLUME,
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
            UserManager.DISALLOW_CREATE_WINDOWS,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
            UserManager.DISALLOW_CONFIG_VPN,
            UserManager.DISALLOW_CONFIG_CELL_BROADCASTS,
            UserManager.DISALLOW_CONFIG_SCREEN_TIMEOUT
    };
}
