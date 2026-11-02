package com.hermes.loader.bootstrap;

import android.system.ErrnoException;
import android.system.Os;

import com.hermes.loader.Env;

import java.io.File;
import java.io.IOException;

/**
 * Verifies the {@code chroot-ng} binary that ships with the APK.
 *
 * <p>The emulator is packaged as a {@code jniLibs} entry
 * ({@code libchrootng.so}) so Android extracts it to
 * {@code nativeLibraryDir} at install time. That directory is read-only and
 * owned by the APK, which is the only place an app targeting API 29+ is still
 * allowed to {@code execve()} from - writing a binary into {@code files/} and
 * chmod-ing it fails with {@code EACCES} on Android 10 and newer.
 */
public final class NgInstaller {

    private NgInstaller() {
    }

    public static void ensure(Env env, Log log) throws IOException {
        String abi = Env.abiDir();
        if (abi.isEmpty() || !"arm64-v8a".equals(abi)) {
            throw new IOException("Unsupported device ABI: " + abi
                    + " (chroot-ng is built for arm64-v8a only)");
        }

        File ng = env.guestBinary();
        if (!ng.isFile()) {
            throw new IOException("Bundled chroot-ng for " + abi + " is missing in "
                    + env.nativeLib + " - reinstall the APK.");
        }

        // Already 0755 after install; cheap insurance for odd package managers.
        chmod(ng, 0755);

        if (!ng.canExecute()) {
            log.line("[CHROOT-NG] \u001b[1;33mwarning:\u001b[0m " + ng + " is not executable.");
        }
        log.line("[CHROOT-NG] chroot-ng ready (" + abi + "): " + ng.getAbsolutePath());
        log.line("[CHROOT-NG] hard links: emulated (--link2symlink)");
    }

    private static void chmod(File f, int mode) {
        try {
            Os.chmod(f.getAbsolutePath(), mode);
        } catch (ErrnoException | RuntimeException ignored) {
            // best effort
        }
    }
}
