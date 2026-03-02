package org.ghost.core;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

public class SteganoEngine {

    //    HIDING OF THE DATA
    public void cloak(BufferedImage image, byte[] data) {
        int width = image.getWidth();
        int height = image.getHeight();
        int byteIndex = 0;
        int bitIndex = 0;

//        No longer needed as data is processed in Byte stream
//        byte[] data = (message + "\0").getBytes(); //Terminating the message using Null character

        for(int y=0; y<height; ++y) {
            for(int x=0; x<width; ++x) {

                int pixel = image.getRGB(x, y); //Extracting the pixel

//              Bit Modification -->
                int bitToHide = (data[byteIndex] >> (7-bitIndex)) & 1;

                int clearedPixel = pixel & 0xFFFEFFFF;
                int newPixel = clearedPixel | (bitToHide << 16);

                //Saving the pixel
                image.setRGB(x, y, newPixel);

                bitIndex++;
                if (bitIndex == 8) {
                    bitIndex = 0;
                    byteIndex++;
                }
                if (byteIndex >= data.length) {
                    return;
                }
            }
        }
    }

//    RETRIEVAL OF DATA
    public byte[] reveal(BufferedImage image) {

        ByteArrayOutputStream buffer = new ByteArrayOutputStream(); //Dynamic bucket for our bytes

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

                    if (currentByte == 0) {
                        return buffer.toByteArray();
                    }

                    buffer.write(currentByte);

                    // Reset for the next character
                    currentByte = 0;
                    bitCount = 0;
                }
            }
        }
        return buffer.toByteArray();
    }
}
