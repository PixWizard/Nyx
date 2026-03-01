package org.ghost;

import org.ghost.core.SteganoEngine; //contains core logic of hiding and retrieval
import org.ghost.utils.ImageManager; // For working the IO of images
import java.awt.image.BufferedImage; // For extra features for working with images
import java.io.IOException; // Basic Error Handling

public class Main {
    public static void main(String[] args) {
        SteganoEngine engine = new SteganoEngine(); // Initializing the engine
        try {
            System.out.println("Loading image...");
            // Path to the image
            BufferedImage original = ImageManager.loadImage("C:\\Projects\\ghost\\ghost\\TestInput.png");

//          Hiding the data
            String secret = "Nyx Project: Ghost Protocol Active";
            System.out.println("Hiding message: " + secret);
            engine.cloak(original, secret); // Main function behind hiding the data

            ImageManager.saveImage(original, "output.png");
            System.out.println("Success! Ghost image saved as 'output.png'");

//          Data retrieval
            System.out.println("\nTesting Reveal from 'output.png'...");
            BufferedImage ghostImage = ImageManager.loadImage("output.png");
            String revealed = engine.reveal(ghostImage); // Main function for retrieval

            System.out.println("Revealed Message: " + revealed);

        } catch (IOException e) {
            System.out.println("Error: Could not find 'input.png'. Please add it to your project folder.");
        }
    }
}