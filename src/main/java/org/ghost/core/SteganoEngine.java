package org.ghost.core;

import java.awt.image.BufferedImage;

public class SteganoEngine {


    //    HIDING OF THE DATA
    public void cloak(BufferedImage image, String message) {
        int width = image.getWidth();
        int height = image.getHeight();
        int charIndex = 0;
        int bitIndex = 0;

        byte[] data = (message + "\0").getBytes(); //Terminating the message using Null character


        for(int y=0; y<height; ++y) {
            for(int x=0; x<width; ++x) {

                int pixel = image.getRGB(x, y); //Extracting the pixel

//              Bit Modification -->
                int bitToHide = (data[charIndex] >> (7-bitIndex)) & 1;

                int clearedPixel = pixel & 0xFFFEFFFF;
                int newPixel = clearedPixel | (bitToHide << 16);

                //Saving the pixel
                image.setRGB(x, y, newPixel);

                bitIndex++;

                if (bitIndex == 8) {
                    bitIndex = 0;
                    charIndex++;
                }
                if (charIndex >= data.length) {
                    return;
                }
            }
        }
    }

//    RETRIEVAL OF DATA

    public String reveal(BufferedImage image) {
        StringBuilder message = new StringBuilder();
        int width = image.getWidth();
        int height = image.getHeight();
        int currentByte = 0;
        int bitCount = 0;

        for(int y=0; y<height; ++y) {

            for(int x=0; x<width; ++x) {

                int pixel = image.getRGB(x, y);
                int bit = (pixel >> 16) & 1;

                currentByte = (currentByte << 1) | bit;
                bitCount++;

                if (bitCount == 8) {
                    char c = (char) currentByte;

                    // If it's the terminator, we are done!
                    if (c == '\0') {
                        return message.toString();
                    }

                    message.append(c);

                    // Reset for the next character
                    currentByte = 0;
                    bitCount = 0;
                }
            }
        }
        return message.toString();
    }
}
