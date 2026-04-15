package org.ghost;

import org.ghost.core.SteganoEngine;
import org.ghost.core.SteganoProtocol;

//Encryption and decryption
import org.ghost.crypto.CipherManager;

//Error handling
import java.io.IOException;
import javax.crypto.BadPaddingException;

//Image managers
import org.ghost.utils.ImageManager;
import java.awt.image.BufferedImage;

//For files
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;


public class Main {
    public static void main(String[] args) {

        SteganoProtocol engine = new SteganoEngine(); //for streamling protocol.

        try {
            // Path of the file to hide.
            Path filePath = Paths.get("C:\\Projects\\ghost\\ghost\\TestPDF.pdf");

            // Storing the metadata regarding the extension.
            String fileName = filePath.getFileName().toString();
            String extension = fileName.substring(fileName.lastIndexOf("."));

            //Supports all types, But a safety message for restricting to 4 only.
            if (!(fileName.endsWith(".jpg") || fileName.endsWith(".pdf") || fileName.endsWith(".png") || fileName.endsWith(".txt"))) {
                System.err.println("ERROR: Unsupported file type! Nyx only supports JPG, PDF, PNG, and TXT.");
                return;
            }

            byte[] fileBytes = Files.readAllBytes(filePath);

            //User defined password required for encryption and decryption.
            String password = "GhostProtocol123";

            //Path of the Image which will act as a carrier.
            String imagePath = "C:\\Projects\\ghost\\ghost\\CarrierImg.png";

            System.out.println("Loading image...");
            //Loading the carrier image.
            BufferedImage original = ImageManager.loadImage(imagePath);

            //Size compatibility check.
            long totalPixels = (long) original.getWidth() * original.getHeight() * 3;
            long requiredPixels = ((long)fileBytes.length * 8 + 32);

//          Making sure if the size in within the size of carriers capacity.
            if (requiredPixels > totalPixels) {
                System.out.println("required pixels: " + requiredPixels);
                System.out.println("total pixels: " + totalPixels);
                System.err.println("ERROR: Secret file is too large for this carrier image!");
                return; // Stop the process
            }

            // --- ENCRYPTION & CLOAKING ---

            System.out.println("Encrypting and hiding message...");
            //Encrypting the data before the actual hiding.
            byte[] encryptedMessage = CipherManager.encrypt(fileBytes, password);

//            long startCloak = System.nanoTime();
            //hiding the encrypted data in the carrier.
            engine.cloak(original, encryptedMessage);
//            long endCloak = System.nanoTime();

            //saving the image.
            ImageManager.saveImage(original, "output.png");
            System.out.println("Success! Ghost image saved as 'output.png'");


            // --- REVEAL & DECRYPTION ---

            System.out.println("\nTesting Reveal from 'output.png'...");
            BufferedImage ghostImage = ImageManager.loadImage("output.png");

//            long startReveal = System.nanoTime();
            byte[] revealed = engine.reveal(ghostImage);
            //revealed will contain encrypted message.
//            long endReveal = System.nanoTime();

            // Decrypting using the password.
            byte[] finalDecrypted = CipherManager.decrypt(revealed, password);

            String op_filename = "Restored_file" + extension;
            Files.write(Paths.get("C:\\Projects\\ghost\\ghost\\" + op_filename), finalDecrypted);
            System.out.println("File restored!!..'" +  op_filename + "'");


        } catch (IOException e) {
            System.err.println("[FILE ERROR]: Could not find or save the image. Check your file paths.");
        } catch (BadPaddingException e) {
            System.err.println("[SECURITY ALERT]: Incorrect password! Decryption failed.");
        } catch (Exception e) {
            System.err.println("[SYSTEM ERROR]: An unexpected error occurred: " + e.getMessage());
        }
    }
}