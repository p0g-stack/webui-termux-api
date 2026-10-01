package com.termux.api;

import com.termux.shared.termux.TermuxConstants;
import static com.termux.shared.termux.TermuxConstants.TERMUX_API_PACKAGE_NAME;

public class TermuxAPIConstants {

    /**
     * WebUI fork: the actual application id (package name) of this app, "com.webui.termux.api".
     * The Java package / namespace stays "com.termux.api", so component class names are unchanged
     * but every runtime identity (component package, provider authority, socket name, ...) must use
     * this value instead of {@link TermuxConstants#TERMUX_API_PACKAGE_NAME}. See WEBUI.md.
     *
     * Set from {@code getPackageName()} when the app starts ({@link TermuxAPIApplication}), not from
     * the build: tools rename the built APK per module (flutter_p0g), so the package is only known
     * at runtime. The build's id is the default until then.
     */
    public static volatile String WEBUI_PACKAGE_NAME = BuildConfig.APPLICATION_ID;

    /**
     * Termux:API Receiver class name. This is a class name (Java package "com.termux.api"), not a
     * package name, so it is unchanged. The full component is
     * "com.webui.termux.api/com.termux.api.TermuxApiReceiver".
     */
    public static final String TERMUX_API_RECEIVER_NAME = TERMUX_API_PACKAGE_NAME + ".TermuxApiReceiver"; // "com.termux.api.TermuxApiReceiver"

    /** The Uri authority for Termux:API app file shares. WebUI fork: "com.webui.termux.api.sharedfiles"
     * instead of upstream "com.termux.sharedfiles" so it does not collide with the real Termux:API. */
    public static String fileShareUriAuthority() {
        return WEBUI_PACKAGE_NAME + ".sharedfiles";
    }

}
