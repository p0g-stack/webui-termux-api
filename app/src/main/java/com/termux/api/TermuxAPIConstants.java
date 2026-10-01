package com.termux.api;

import com.termux.shared.termux.TermuxConstants;
import static com.termux.shared.termux.TermuxConstants.TERMUX_API_PACKAGE_NAME;

public class TermuxAPIConstants {

    /**
     * WebUI fork: the actual application id (package name) of this app, "com.webui.termux.api".
     * The Java package / namespace stays "com.termux.api", so component class names are unchanged
     * but every runtime identity (component package, provider authority, socket name, ...) must use
     * this value instead of {@link TermuxConstants#TERMUX_API_PACKAGE_NAME}. See WEBUI.md.
     */
    public static final String WEBUI_PACKAGE_NAME = BuildConfig.APPLICATION_ID;

    /**
     * Termux:API Receiver class name. This is a class name (Java package "com.termux.api"), not a
     * package name, so it is unchanged. The full component is
     * "com.webui.termux.api/com.termux.api.TermuxApiReceiver".
     */
    public static final String TERMUX_API_RECEIVER_NAME = TERMUX_API_PACKAGE_NAME + ".TermuxApiReceiver"; // "com.termux.api.TermuxApiReceiver"

    /** The Uri authority for Termux:API app file shares. WebUI fork: "com.webui.termux.api.sharedfiles"
     * instead of upstream "com.termux.sharedfiles" so it does not collide with the real Termux:API. */
    public static final String TERMUX_API_FILE_SHARE_URI_AUTHORITY = WEBUI_PACKAGE_NAME + ".sharedfiles";

}
