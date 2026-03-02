package org.ghost;

import org.ghost.core.SteganoEngine;
import org.ghost.crypto.CipherManager;
import org.ghost.utils.ImageManager;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.crypto.BadPaddingException;

//For files
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Main {
    public static void main(String[] args) {
        SteganoEngine engine = new SteganoEngine();

        try {
            // --- SETTINGS ---
            Path filePath = Paths.get("C:\\Projects\\ghost\\ghost\\demo.txt");
            byte[] fileBytes = Files.readAllBytes(filePath);
            System.out.println("Original File Size: " + fileBytes.length + " bytes");

            String password = "GhostProtocol123";
//            String secret = new String(fileBytes, StandardCharsets.UTF_8);
            String imagePath = "C:\\Projects\\ghost\\ghost\\TestInput.png";

            // --- ENCRYPTION & CLOAKING ---
            System.out.println("Loading image...");
            BufferedImage original = ImageManager.loadImage(imagePath);

            System.out.println("Encrypting and hiding message...");
            byte[] encryptedMessage = CipherManager.encrypt(fileBytes, password);

            //No need for array copying.Since the data termination is fixed by introducing the header.

            engine.cloak(original, encryptedMessage);
            ImageManager.saveImage(original, "output.png");
            System.out.println("Success! Ghost image saved as 'output.png'");

            // --- REVEAL & DECRYPTION ---
            System.out.println("\nTesting Reveal from 'output.png'...");
            BufferedImage ghostImage = ImageManager.loadImage("output.png");
            byte[] revealed = engine.reveal(ghostImage);

            // Decrypting using the password
            byte[] finalDecrypted = CipherManager.decrypt(revealed, password);
            System.out.println("DEBUG: Final Decrypted Length: " + finalDecrypted.length);
            Files.write(Paths.get("C:\\Projects\\ghost\\ghost\\Secrets.txt"), finalDecrypted);
            System.out.println("File restored!!..'Secrets.txt'");

            // --- UNCOMMENT BELOW TO TEST THE WRONG PASSWORD ---
            /*
            System.out.println("\nTesting with a wrong password...");
            String wrongPass = "Hacker123";
            CipherManager.decrypt(revealed, wrongPass);
             */


        } catch (IOException e) {
            System.err.println("[FILE ERROR]: Could not find or save the image. Check your file paths.");
        } catch (BadPaddingException e) {
            System.err.println("[SECURITY ALERT]: Incorrect password! Decryption failed.");
        } catch (Exception e) {
            System.err.println("[SYSTEM ERROR]: An unexpected error occurred: " + e.getMessage());
        }
    }
}