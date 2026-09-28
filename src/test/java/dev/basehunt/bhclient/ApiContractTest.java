package dev.basehunt.bhclient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Verifies that the Meteor ElytraFly API this addon drives still exists.
 *
 * <p>ElytraController and ElytraManager reach into another mod's module: a renamed setting or enum
 * constant compiles perfectly here and then throws NoSuchFieldError in game. This check reads the class
 * files straight out of the real Meteor jar and asserts the members are present, so the contract is
 * verified without loading Minecraft classes or starting a client.
 */
public class ApiContractTest {
    static int passed = 0, failed = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  PASS: " + name); }
        else { failed++; System.out.println("  FAIL: " + name + " -> " + detail); }
    }

    public static void main(String[] args) throws Exception {
        String jarPath = args[0];

        try (ZipFile zip = new ZipFile(jarPath)) {
            byte[] flyBytes = read(zip,
                "meteordevelopment/meteorclient/systems/modules/movement/elytrafly/ElytraFly.class");

            System.out.println("== ElytraFly settings driven by ElytraController ==");

            String[] settings = {
                "flightMode", "autoJump", "sprint", "useFireworks", "autoPilot",
                "autoPilotMinimumHeight", "autoHover", "lockPitch", "pitch",
                "yawLockMode", "yaw", "manualTakeoff", "restart", "restartDelay",
                "noCrash", "dontGoIntoUnloadedChunks", "replace", "replaceDurability",
                "chestSwap", "autoReplenish", "replenishSlot"
            };

            for (String name : settings) {
                check("ElytraFly." + name + " present", containsUtf8(flyBytes, name), "missing");
            }

            System.out.println("== enum constants ==");

            byte[] modes = read(zip,
                "meteordevelopment/meteorclient/systems/modules/movement/elytrafly/ElytraFlightModes.class");
            check("ElytraFlightModes.Bounce present (firework-free flight)",
                containsUtf8(modes, "Bounce"), "missing Bounce");
            check("ElytraFlightModes.Packet present", containsUtf8(modes, "Packet"), "missing Packet");

            byte[] chestSwap = read(zip,
                "meteordevelopment/meteorclient/systems/modules/movement/elytrafly/ElytraFly$ChestSwapMode.class");
            check("ChestSwapMode.Never present", containsUtf8(chestSwap, "Never"), "missing Never");

            // ElytraController pins yawLockMode to Smart; Bounce switches on these constants.
            byte[] lockMode = read(zip,
                "meteordevelopment/meteorclient/systems/modules/player/Rotation$LockMode.class");
            check("Rotation.LockMode.Smart present", containsUtf8(lockMode, "Smart"), "missing Smart");
            check("Rotation.LockMode.Simple present", containsUtf8(lockMode, "Simple"), "missing Simple");
            check("Rotation.LockMode.None present", containsUtf8(lockMode, "None"), "missing None");

            System.out.println("== Bounce mode internals ==");

            byte[] bounce = read(zip,
                "meteordevelopment/meteorclient/systems/modules/movement/elytrafly/modes/Bounce.class");
            check("Bounce.recastElytra present", containsUtf8(bounce, "recastElytra"), "missing");
            check("Bounce.checkConditions present", containsUtf8(bounce, "checkConditions"), "missing");

            System.out.println("== Module lifecycle used by ElytraController ==");

            byte[] module = read(zip, "meteordevelopment/meteorclient/systems/modules/Module.class");
            check("Module.enable present", containsUtf8(module, "enable"), "missing");
            check("Module.disable present", containsUtf8(module, "disable"), "missing");
            check("Module.isActive present", containsUtf8(module, "isActive"), "missing");

            System.out.println("== InvUtils inventory movement used by ElytraManager ==");

            byte[] invUtils = read(zip, "meteordevelopment/meteorclient/utils/player/InvUtils.class");
            check("InvUtils.move present", containsUtf8(invUtils, "move"), "missing");
            check("InvUtils.find present", containsUtf8(invUtils, "find"), "missing");
        }

        // ElytraManager bounds its spare scans by PlayerInventory.MAIN_SIZE because getStack() maps higher
        // indices onto armour slots. That bound is a Minecraft constant, so it is checked against the
        // Minecraft jar rather than the Meteor one.
        try (ZipFile mc = new ZipFile(args[1])) {
            System.out.println("== PlayerInventory slot layout ==");

            byte[] inventory = read(mc, "net/minecraft/entity/player/PlayerInventory.class");
            check("PlayerInventory.MAIN_SIZE present", containsUtf8(inventory, "MAIN_SIZE"), "missing");
            check("PlayerInventory.getStack present", containsUtf8(inventory, "getStack"), "missing");
        }

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    static byte[] read(ZipFile zip, String path) throws Exception {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) throw new IllegalStateException("Not found in jar: " + path);

        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    /**
     * True when the class file's constant pool contains the given name. Field and method names are stored
     * as UTF-8 constants, so their raw bytes appear verbatim in the class file.
     */
    static boolean containsUtf8(byte[] classBytes, String name) {
        byte[] needle = name.getBytes(StandardCharsets.UTF_8);

        outer:
        for (int i = 0; i <= classBytes.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (classBytes[i + j] != needle[j]) continue outer;
            }
            return true;
        }

        return false;
    }
}
