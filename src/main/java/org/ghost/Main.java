package org.ghost;

//Brain
import org.ghost.core.SteganoEngine;
import org.ghost.core.SteganoProtocol;

//Encryption and Decryption
import org.ghost.crypto.AbstractCipher;
import org.ghost.crypto.XORCipher;

//Image manager.
import java.awt.image.BufferedImage;
import org.ghost.utils.ImageManager;
import org.ghost.utils.UnsplashClient;

//Error handling
import javax.crypto.BadPaddingException;
import java.io.IOException;

//File tools
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

//Java Utils
import java.util.Scanner;

public class Main {

    private static void printHeader() {
        System.out.println("******************************************");
        System.out.println("*            NYX STEGANOGRAPHY           *");
        System.out.println("*       Bit-Perfect Binary Cloaking      *");
        System.out.println("******************************************\n");
    }

    public static void main(String[] args) {

        //Creating objects for the required classes
        Scanner scanner = new Scanner(System.in);
        SteganoProtocol engine = new SteganoEngine();
        AbstractCipher cipher = new XORCipher();

        printHeader(); //Main title header.

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

    private static void handleEncryptionFlow(Scanner scanner, SteganoProtocol engine, AbstractCipher cipher) throws Exception {

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

        byte[] packedData = packData(fileBytes, originalExtension);
        
        // Size check (incl. header and padding)
        long requiredBits = ((long) (packedData.length + 4) * 8);

        System.out.println("\n--- Carrier Image (Container) Selection ---");
        System.out.println("[1] Manual path");
        System.out.println("[2] Auto-fetch Unsplash (HD)");
        System.out.print("Choice (1/2): ");
        String choice = scanner.nextLine().trim();

        BufferedImage carrier = null;
        if (choice.equals("2")) {
            carrier = UnsplashClient.fetchCarrierImage(requiredBits, "carrier_image.jpg");
        } else {
            System.out.print("Enter carrier image path ( Size <~ 90 mb ): ");
            String carrierPath = scanner.nextLine().trim().replace("\"", "");
            // Use bit-preparation for the initial carrier
            carrier = ImageManager.loadAndPrepareCarrier(carrierPath);
        }

        if (carrier == null) throw new IOException("Carrier loading failed.");

        //Capacity check.
        long capacityBits = (long) carrier.getWidth() * carrier.getHeight() * 3;

        if (requiredBits > capacityBits) {
            System.err.println("\nERROR: Carrier image is too small!");
            System.err.println("Required: " + requiredBits + " bits | Available: " + capacityBits + " bits");
            return;
        }

        System.out.print("Encrypting and hiding... ");
        
        byte[] encrypted = cipher.encrypt(packedData, password);
        engine.cloak(carrier, encrypted);
        System.out.println("Done!");

        System.out.print("Save output as (default: output.png): ");
        String outputName = scanner.nextLine().trim();
        if (outputName.isEmpty()) outputName = "output.png";
        if (!outputName.toLowerCase().endsWith(".png")) outputName += ".png";

        ImageManager.saveImage(carrier, outputName);
        System.out.println("SUCCESS! Digital Ghost saved to: " + outputName);
    }

    private static void handleDecryptionFlow(Scanner scanner, SteganoProtocol engine, AbstractCipher cipher) throws Exception {
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
        
        // --- UNPACK DATA ---
        UnpackedData unpacked = unpackData(decrypted);
        byte[] finalFileData = unpacked.data();
        String detectedExt = unpacked.extension();

        System.out.println("Done!");

        // Ensure the restored filename has the correct extension
        System.out.print("Save restored file as (extension will be added automatically): ");
        String restoredName = scanner.nextLine().trim();
        
        if (restoredName.isEmpty()) restoredName = "restored_file";
        
        // Append extension if not already present
        if (!detectedExt.isEmpty() && !restoredName.toLowerCase().endsWith(detectedExt.toLowerCase())) {
            restoredName += detectedExt;
        }

        Files.write(Paths.get(restoredName), finalFileData);
        System.out.println("SUCCESS! File restored as: " + restoredName);
    }


    // --- METADATA HELPERS ---

    private static byte[] packData(byte[] fileData, String extension) {
        byte[] extBytes = extension.getBytes();
        int extLen = extBytes.length;
        
        byte[] packed = new byte[1 + extLen + fileData.length];
        packed[0] = (byte) extLen;
        System.arraycopy(extBytes, 0, packed, 1, extLen);
        System.arraycopy(fileData, 0, packed, 1 + extLen, fileData.length);
        
        return packed;
    }

    private static UnpackedData unpackData(byte[] decrypted) {
        if (decrypted.length > 1) {
            int extLen = decrypted[0] & 0xFF; // handle unsigned
            if (decrypted.length >= 1 + extLen) {
                String ext = new String(decrypted, 1, extLen);
                byte[] data = new byte[decrypted.length - 1 - extLen];
                System.arraycopy(decrypted, 1 + extLen, data, 0, data.length);
                return new UnpackedData(data, ext);
            }
        }
        return new UnpackedData(decrypted, ".bin");
    }

    private record UnpackedData(byte[] data, String extension) {}

}