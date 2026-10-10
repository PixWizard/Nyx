package org.ghost;

import org.ghost.core.EnvelopeHeader;
import org.ghost.core.InsufficientCarrierCapacityException;
import org.ghost.core.InvalidHeaderException;
import org.ghost.core.NoNyxPayloadException;
import org.ghost.core.PayloadEnvelope;
import org.ghost.core.SequentialLayout;
import org.ghost.core.SteganoEngine;
import org.ghost.core.UnsupportedFormatException;
import org.ghost.crypto.AuthenticatedCipher;
import org.ghost.crypto.AuthenticationFailedException;
import org.ghost.utils.ImageManager;
import org.ghost.utils.UnsplashClient;

import java.awt.image.BufferedImage;
import java.io.Console;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Scanner;

public class Main {

    private static void printHeader() {
        System.out.println("******************************************");
        System.out.println("*            NYX STEGANOGRAPHY           *");
        System.out.println("*       Bit-Perfect Binary Cloaking      *");
        System.out.println("******************************************\n");
    }

    public static void main(String[] args) {
        boolean scatter = true;
        boolean adaptive = true;
        if (args != null) {
            for (String arg : args) {
                if ("--no-scatter".equalsIgnoreCase(arg.trim())) {
                    scatter = false;
                }
                if ("--no-adaptive".equalsIgnoreCase(arg.trim())) {
                    adaptive = false;
                }
            }
        }

        Scanner scanner = new Scanner(System.in);
        SteganoEngine engine = new SteganoEngine();

        printHeader();
        System.out.println("Embedding Mode: " + (scatter ? "Key-Driven Scattered (default)" : "Sequential (--no-scatter)") +
                " | Depth: " + (adaptive ? "Sobel Adaptive (default)" : "Constant Depth 1 (--no-adaptive)"));

        System.out.println("Select Operation Mode:");
        System.out.println("[1] Encrypt & Hide (Cloak)");
        System.out.println("[2] Reveal & Decrypt (Uncloak)");
        System.out.print("Choice (1/2): ");
        String mode = scanner.nextLine().trim();

        try {
            if (mode.equals("1")) {
                handleEncryptionFlow(scanner, engine, scatter, adaptive);
            } else if (mode.equals("2")) {
                handleDecryptionFlow(scanner, engine);
            } else {
                System.out.println("Invalid selection. Exiting...");
            }
        } catch (AuthenticationFailedException e) {
            System.err.println("\n[SECURITY ALERT]: " + e.getMessage());
        } catch (NoNyxPayloadException | InvalidHeaderException | UnsupportedFormatException | InsufficientCarrierCapacityException e) {
            System.err.println("\n[STEGANOGRAPHY ERROR]: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("\n[SYSTEM ERROR]: " + e.getMessage());
        } finally {
            System.out.println("\nThank you for using Nyx-Stegano!");
            scanner.close();
        }
    }

    private static char[] readPassword() {
        Console console = System.console();
        if (console != null) {
            System.out.print("Enter password: ");
            char[] pwd = console.readPassword();
            if (pwd != null && pwd.length > 0) {
                return pwd;
            }
        }
        String envPwd = System.getenv("NYX_PASSWORD");
        if (envPwd != null && !envPwd.isEmpty()) {
            return envPwd.toCharArray();
        }
        throw new IllegalStateException("Password required: please run in an interactive console or set the NYX_PASSWORD environment variable.");
    }

    private static void handleEncryptionFlow(Scanner scanner, SteganoEngine engine, boolean scatter, boolean adaptive) throws Exception {
        System.out.print("Enter path of the file to hide: ");
        String filePathStr = scanner.nextLine().trim().replace("\"", "");
        Path filePath = Paths.get(filePathStr);

        if (!Files.exists(filePath)) {
            throw new IOException("Secret file not found: " + filePathStr);
        }

        String fileName = filePath.getFileName().toString();
        int lastDot = fileName.lastIndexOf(".");
        String originalExtension = (lastDot == -1) ? "" : fileName.substring(lastDot + 1);

        byte[] fileBytes = Files.readAllBytes(filePath);

        char[] password = readPassword();

        try {
            byte[] extBytes = originalExtension.getBytes(StandardCharsets.US_ASCII);
            long ciphertextBytes = 1L + extBytes.length + fileBytes.length + AuthenticatedCipher.GCM_TAG_LENGTH_BYTES;
            long requiredBits = ((long) SequentialLayout.HEADER_PIXELS * 3L) + (ciphertextBytes * 8L);

            System.out.println("\n--- Carrier Image (Container) Selection ---");
            System.out.println("[1] Manual path");
            System.out.println("[2] Auto-fetch Unsplash (HD)");
            System.out.print("Choice (1/2): ");
            String choice = scanner.nextLine().trim();

            BufferedImage carrier;
            if (choice.equals("2")) {
                try {
                    carrier = UnsplashClient.fetchCarrierImage(requiredBits, "carrier_image.jpg");
                } catch (IllegalStateException e) {
                    System.err.println("\n[CONFIGURATION ERROR]: " + e.getMessage());
                    return;
                }
            } else {
                System.out.print("Enter carrier image path: ");
                String carrierPath = scanner.nextLine().trim().replace("\"", "");
                carrier = ImageManager.loadAndPrepareCarrier(carrierPath);
            }

            if (carrier == null) throw new IOException("Carrier loading failed.");

            System.out.println("Deriving key, this can take a few seconds...");
            System.out.print("Encrypting and cloaking... ");
            carrier = engine.cloak(carrier, fileBytes, originalExtension, password, scatter, adaptive);
            System.out.println("Done!");

            System.out.print("Save output as (default: output.png): ");
            String outputName = scanner.nextLine().trim();
            if (outputName.isEmpty()) {
                outputName = "output.png";
            } else {
                String lower = outputName.toLowerCase();
                if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".bmp") || lower.endsWith(".gif")) {
                    int outDot = outputName.lastIndexOf('.');
                    String base = outputName.substring(0, outDot);
                    outputName = base + ".png";
                    System.out.println("Notice: Stego carrier must be a lossless PNG container. Forcing extension to .png: " + outputName);
                } else if (!lower.endsWith(".png")) {
                    outputName += ".png";
                    System.out.println("Notice: Appending required lossless PNG extension: " + outputName);
                }
            }

            ImageManager.saveImage(carrier, outputName);
            long outputSize = Files.size(Paths.get(outputName));
            System.out.printf("SUCCESS! Digital Ghost saved to: %s (%d bytes)%n", outputName, outputSize);
        } finally {
            Arrays.fill(password, (char) 0);
        }
    }

    private static void handleDecryptionFlow(Scanner scanner, SteganoEngine engine) throws Exception {
        System.out.println("\n--- Decryption Flow ---");
        System.out.print("Enter Ghost image path: ");
        String path = scanner.nextLine().trim().replace("\"", "");

        char[] password = readPassword();

        try {
            BufferedImage ghost = ImageManager.loadImage(path);
            if (ghost == null) throw new IOException("Image not found: " + path);

            System.out.println("Deriving key, this can take a few seconds...");
            System.out.print("Extracting and decrypting... ");
            PayloadEnvelope.Unpacked unpacked = engine.reveal(ghost, password);
            byte[] finalFileData = unpacked.fileBytes();
            String detectedExt = unpacked.extension();

            System.out.println("Done!");

            System.out.print("Save restored file as (extension will be added automatically): ");
            String restoredName = scanner.nextLine().trim();
            if (restoredName.isEmpty()) restoredName = "restored_file";

            if (!detectedExt.isEmpty() && !restoredName.toLowerCase().endsWith("." + detectedExt.toLowerCase())) {
                restoredName += "." + detectedExt;
            }

            Path targetPath = Paths.get(restoredName);
            if (Files.exists(targetPath) && System.console() != null) {
                System.out.print("File '" + targetPath + "' already exists. Overwrite? (y/N): ");
                String confirm = scanner.nextLine().trim();
                if (!confirm.equalsIgnoreCase("y") && !confirm.equalsIgnoreCase("yes")) {
                    System.out.println("Aborting restore to avoid overwriting existing file.");
                    return;
                }
            }

            Path parentDir = targetPath.getParent();
            if (parentDir == null) parentDir = Paths.get(".");
            Path tempFile = Files.createTempFile(parentDir, ".nyx_tmp_", ".tmp");
            try {
                Files.write(tempFile, finalFileData);
                try {
                    Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tempFile);
            }

            System.out.println("SUCCESS! File restored as: " + targetPath + " (" + finalFileData.length + " bytes)");
        } finally {
            Arrays.fill(password, (char) 0);
        }
    }
}