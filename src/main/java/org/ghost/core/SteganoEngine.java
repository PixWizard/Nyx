package org.ghost.core;

import java.awt.image.BufferedImage;
//import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class SteganoEngine {

    //    HIDING OF THE DATA
    public void cloak(BufferedImage image, byte[] data) {
        int width = image.getWidth();
        int height = image.getHeight();
        int byteIndex = 0;
        int bitIndex = 0;

//      Storing the size of the data in the first 4 byte of the file (32bits for the dataLength)- >I15(RES)
        byte[] header = ByteBuffer.allocate(4).putInt(data.length).array();
        byte[] totalData = new byte[header.length + data.length]; //stored in the form of bytes

//      TOTAL LEN = DATA LEN + 4BYTE HEADER FOR DATA LENGTH
        System.arraycopy(header, 0, totalData, 0, header.length);
        System.arraycopy(data, 0, totalData, header.length, data.length);

//      Getting all the pixels into a Buffer for faster modification.
        int[] pixelBuffer = new int[width * height];
        image.getRGB(0, 0, width, height, pixelBuffer, 0, width); //Loading the image to the matrix

        for (int i = 0; i < pixelBuffer.length; ++i) {

            int pixel = pixelBuffer[i]; //Extracting the pixel

//          Bit Modification -->
            int bitToHide = (totalData[byteIndex] >> (7 - bitIndex)) & 1;

            int clearedPixel = pixel & 0xFFFEFFFF;
            pixelBuffer[i] = clearedPixel | (bitToHide << 16);

            bitIndex++;
            if (bitIndex == 8) {
                bitIndex = 0;
                byteIndex++;
            }
            if (byteIndex >= totalData.length) {
                break;
            }
        }
        //Saving the pixel at the end--> v3
        image.setRGB(0, 0, width, height, pixelBuffer, 0, width);

    }

//    RETRIEVAL OF DATA
    public byte[] reveal(BufferedImage image) {

//        ByteArrayOutputStream buffer = new ByteArrayOutputStream(); //Dynamic bucket for our bytes

        int width = image.getWidth();
        int height = image.getHeight();

        byte[] header = new byte[4];
        int currentByte = 0;
        int bitCount = 0;
        int headerIndex = 0;
        int i = 0;

        int[] pixelBuffer = new int[height * width];
        image.getRGB(0, 0, width, height, pixelBuffer, 0, width);

//      Loop for getting the header and traversing till the bit of the actual data.
        for(; i<32; ++i) {
            int pixel =  pixelBuffer[i];
            int bit  = (pixel >> 16) & 1;
            currentByte = (currentByte << 1) | bit;
            bitCount++;

            if(bitCount == 8) {
                header[headerIndex++] = (byte) currentByte;
                currentByte = 0;
                bitCount = 0;
            }
        }

        int dataLength = ByteBuffer.wrap(header).getInt();
        byte[] data = new byte[dataLength];

        int dataIndex = 0;

        for(; i<pixelBuffer.length; ++i) {
            int pixel = pixelBuffer[i];
            int bit = (pixel >> 16) & 1;

            currentByte = (currentByte << 1) | bit;
            bitCount++;

            if (bitCount == 8) {

                data[dataIndex++] = (byte) currentByte;

                if (dataIndex == dataLength) {
                    return data;
                }

                // Reset for the next character
                currentByte = 0;
                bitCount = 0;
            }
        }
        return data;
    }
}
