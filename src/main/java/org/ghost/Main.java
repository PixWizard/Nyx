package org.ghost;

import org.ghost.core.SteganoEngine;
import org.ghost.core.SteganoProtocol;
import org.ghost.crypto.CipherProtocol;
import org.ghost.crypto.CipherManager;
import org.ghost.utils.ImageManager;
import org.ghost.utils.UnsplashClient;

import javax.crypto.BadPaddingException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        SteganoProtocol engine = new SteganoEngine();
        CipherProtocol cipher = new CipherManager();

        printHeader();

        try {
            // 1. Secret File Path
            System.out.print("Enter path of the file to hide (e.g., sample.txt): ");
            String filePathStr = scanner.nextLine().trim().replace("\"", "");
            Path filePath = Paths.get(filePathStr);

            if (!Files.exists(filePath)) {
                System.err.println("ERROR: Secret file not found at: " + filePathStr);
                return;
            }

            // Storing the metadata regarding the extension.
            String fileName = filePath.getFileName().toString();
            int lastDot = fileName.lastIndexOf(".");
            if (lastDot == -1) {
                System.err.println("ERROR: File must have an extension!");
                return;
            }
            String extension = fileName.substring(lastDot);

            byte[] fileBytes = Files.readAllBytes(filePath);

            // 2. Encryption Password
            System.out.print("Enter password for encryption: ");
            String password = scanner.nextLine();
            if (password.isEmpty()) {
                System.out.println("No password entered. Using default: 'GhostProtocol123'");
                password = "GhostProtocol123";
            }

            // 3. Carrier Image Selection
            System.out.println("\n--- Carrier Image Selection ---");
            System.out.println("[1] Provide your own carrier image path");
            System.out.println("[2] Auto-fetch from Unsplash (Recommended)");
            System.out.print("Choose option (1/2): ");
            String choice = scanner.nextLine().trim();

            BufferedImage original = null;
            String imagePathToSave = "carrier_image.jpg"; // Default path for auto-fetched or copy

            long requiredPixels = ((long) fileBytes.length * 8 + 32);

            if (choice.equals("2")) {
                try {
                    original = UnsplashClient.fetchCarrierImage(requiredPixels, imagePathToSave);
                } catch (IllegalStateException e) {
                    System.err.println("\n" + e.getMessage());
                    System.out.println("Falling back to manual path entry...");
                    choice = "1";
                }
            }

            if (choice.equals("1")) {
                System.out.print("Enter path of the carrier image: ");
                String imagePathStr = scanner.nextLine().trim().replace("\"", "");
                original = ImageManager.loadImage(imagePathStr);
            }

            if (original == null) {
                System.err.println("ERROR: No carrier image loaded. Process aborted.");
                return;
            }

            // Size compatibility check.
            long totalPixels = (long) original.getWidth() * original.getHeight();
            long availableBits = totalPixels * 3; // 3 bits per pixel

            if (requiredPixels > availableBits) {
                System.err.println("\nERROR: Secret file is too large for this carrier image!");
                System.err.println("Required bits: " + requiredPixels);
                System.err.println("Available bits: " + availableBits);
                return;
            }

            // --- ENCRYPTION & CLOAKING ---
            System.out.println("\n--- Processing ---");
            System.out.print("Encrypting and hiding message... ");
            byte[] encryptedMessage = cipher.encrypt(fileBytes, password);
            engine.cloak(original, encryptedMessage);
            System.out.println("Done!");

            // Saving the output image.
            System.out.print("Enter name for output image (default: output.png): ");
            String outputName = scanner.nextLine().trim();
            if (outputName.isEmpty()) outputName = "output.png";
            if (!outputName.toLowerCase().endsWith(".png")) outputName += ".png";

            ImageIO.scanForPlugins();
            ImageManager.saveImage(original, outputName);
            System.out.println("SUCCESS! Encrypted image saved as '" + outputName + "'");


            // --- REVEAL & DECRYPTION TEST ---
            System.out.println("\n--- Verification (Optional) ---");
            System.out.print("Would you like to verify by extracting the file now? (y/n): ");
            if (scanner.nextLine().trim().equalsIgnoreCase("y")) {
                System.out.print("Enter password for decryption: ");
                String verifyPassword = scanner.nextLine();
                if (verifyPassword.isEmpty()) verifyPassword = "GhostProtocol123";

                System.out.print("Extracting and decrypting... ");
                BufferedImage ghostImage = ImageManager.loadImage(outputName);
                byte[] revealed = engine.reveal(ghostImage);
                byte[] finalDecrypted = cipher.decrypt(revealed, verifyPassword);

                String restoredFileName = "Restored_file" + extension;
                Files.write(Paths.get(restoredFileName), finalDecrypted);
                System.out.println("Done!");
                System.out.println("File restored to: " + restoredFileName);
            }

            System.out.println("\nThank you for using Nyx-Stegano!");

        } catch (IOException e) {
            System.err.println("\n[FILE ERROR]: " + e.getMessage());
        } catch (BadPaddingException e) {
            System.err.println("\n[SECURITY ALERT]: Incorrect password! Decryption failed.");
        } catch (Exception e) {
            System.err.println("\n[SYSTEM ERROR]: " + e.getMessage());
            e.printStackTrace();
        } finally {
            scanner.close();
        }
    }

    private static void printHeader() {
        System.out.println("******************************************");
        System.out.println("*            GHOST STEGANOGRAPHY         *");
        System.out.println("*        Secure Data Hiding in Images    *");
        System.out.println("******************************************\n");
    }
}