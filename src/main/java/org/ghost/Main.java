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
import java.util.Arrays;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        SteganoProtocol engine = new SteganoEngine();
        CipherProtocol cipher = new CipherManager();

        printHeader();

        System.out.println("Select Operation Mode:");
        System.out.println("[1] Encrypt & Hide (Cloak)");
        System.out.println("[2] Reveal & Decrypt (Uncloak)");
        System.out.print("Choice (1/2): ");
        String mode = scanner.nextLine().trim();

        try {
            if (mode.equals("1")) {
                handleEncryptionFlow(scanner, engine, cipher);
            } else if (mode.equals("2")) {
                handleDecryptionFlow(scanner, engine, cipher);
            } else {
                System.out.println("Invalid selection. Exiting...");
            }
        } catch (BadPaddingException e) {
            System.err.println("\n[SECURITY ALERT]: Incorrect password or corrupted data!");
        } catch (Exception e) {
            System.err.println("\n[SYSTEM ERROR]: " + e.getMessage());
        } finally {
            System.out.println("\nThank you for using Nyx-Stegano!");
            scanner.close();
        }
    }

    private static void handleEncryptionFlow(Scanner scanner, SteganoProtocol engine, CipherProtocol cipher) throws Exception {
        System.out.print("Enter path of the file to hide: ");
        String filePathStr = scanner.nextLine().trim().replace("\"", "");
        Path filePath = Paths.get(filePathStr);

        if (!Files.exists(filePath)) {
            throw new IOException("Secret file not found: " + filePathStr);
        }

        String fileName = filePath.getFileName().toString();
        int lastDot = fileName.lastIndexOf(".");
        String originalExtension = (lastDot == -1) ? "" : fileName.substring(lastDot);

        byte[] fileBytes = Files.readAllBytes(filePath);

        System.out.print("Enter encryption password: ");
        String password = scanner.nextLine();
        if (password.isEmpty()) password = "GhostProtocol123";

        // Size check (incl. header and padding)
        long requiredBits = ((long) (fileBytes.length + 32) * 8) + 32;

        System.out.println("\n--- Carrier Image (Container) Selection ---");
        System.out.println("[1] Manual path");
        System.out.println("[2] Auto-fetch Unsplash (HD)");
        System.out.print("Choice (1/2): ");
        String choice = scanner.nextLine().trim();

        BufferedImage carrier = null;
        if (choice.equals("2")) {
            carrier = UnsplashClient.fetchCarrierImage(requiredBits, "carrier_image.jpg");
        } else {
            System.out.print("Enter carrier image path: ");
            String carrierPath = scanner.nextLine().trim().replace("\"", "");
            // Use bit-preparation for the initial carrier
            carrier = ImageManager.loadAndPrepareCarrier(carrierPath);
        }

        if (carrier == null) throw new IOException("Carrier loading failed.");

        long capacityBits = (long) carrier.getWidth() * carrier.getHeight() * 3;
        if (requiredBits > capacityBits) {
            System.err.println("\nERROR: Carrier image is too small!");
            System.err.println("Required: " + requiredBits + " bits | Available: " + capacityBits + " bits");
            return;
        }

        System.out.print("Encrypting and hiding... ");
        byte[] encrypted = cipher.encrypt(fileBytes, password);
        engine.cloak(carrier, encrypted);
        System.out.println("Done!");

        System.out.print("Save output as (default: output.png): ");
        String outputName = scanner.nextLine().trim();
        if (outputName.isEmpty()) outputName = "output.png";
        if (!outputName.toLowerCase().endsWith(".png")) outputName += ".png";

        ImageManager.saveImage(carrier, outputName);
        System.out.println("SUCCESS! Digital Ghost saved to: " + outputName);

        System.out.print("\nVerify binary identity now? (y/n): ");
        if (scanner.nextLine().trim().equalsIgnoreCase("y")) {
            verifyBinaryIdentity(engine, cipher, outputName, password, fileBytes, originalExtension);
        }
    }

    private static void handleDecryptionFlow(Scanner scanner, SteganoProtocol engine, CipherProtocol cipher) throws Exception {
        System.out.println("\n--- Decryption Flow ---");
        System.out.print("Enter Ghost image path: ");
        String path = scanner.nextLine().trim().replace("\"", "");
        
        System.out.print("Enter password: ");
        String password = scanner.nextLine();
        if (password.isEmpty()) password = "GhostProtocol123";

        System.out.print("Extracting... ");
        // RAW load for decryption (no color management)
        BufferedImage ghost = ImageManager.loadImage(path);
        if (ghost == null) throw new IOException("Image not found.");

        byte[] revealed = engine.reveal(ghost);
        byte[] decrypted = cipher.decrypt(revealed, password);
        System.out.println("Done!");

        System.out.print("Save restored file as (e.g. secret.zip): ");
        String restoredName = scanner.nextLine().trim();
        if (restoredName.isEmpty()) restoredName = "restored_file.bin";

        Files.write(Paths.get(restoredName), decrypted);
        System.out.println("SUCCESS! File restored: " + restoredName);
    }

    private static void verifyBinaryIdentity(SteganoProtocol engine, CipherProtocol cipher, String path, String password, byte[] original, String ext) throws Exception {
        System.out.print("Testing extraction... ");
        BufferedImage ghost = ImageManager.loadImage(path);
        byte[] revealed = engine.reveal(ghost);
        byte[] decrypted = cipher.decrypt(revealed, password);

        if (Arrays.equals(original, decrypted)) {
            System.out.println("DONE!");
            System.out.println("[IDENTITY OK]: Decrypted data is bit-for-bit IDENTICAL to original.");
            
            String testFile = "verify_result" + ext;
            Files.write(Paths.get(testFile), decrypted);
            System.out.println("[VERFIED]: Test file created: " + testFile);
        } else {
            System.err.println("FAILED!");
            System.err.println("[WARNING]: Decrypted data MISMATCH! Integrity may be compromised.");
        }
    }

    private static void printHeader() {
        System.out.println("******************************************");
        System.out.println("*            NYX STEGANOGRAPHY           *");
        System.out.println("*       Bit-Perfect Binary Cloaking      *");
        System.out.println("******************************************\n");
    }
}