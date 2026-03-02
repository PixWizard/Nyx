package org.ghost.core;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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

//        No longer needed as data is processed in Byte stream ->I15
//        byte[] data = (message + "\0").getBytes(); //Terminating the message using Null character

        for(int y=0; y<height; ++y) {
            for(int x=0; x<width; ++x) {

                int pixel = image.getRGB(x, y); //Extracting the pixel

//              Bit Modification -->
                int bitToHide = (totalData[byteIndex] >> (7-bitIndex)) & 1;

                int clearedPixel = pixel & 0xFFFEFFFF;
                int newPixel = clearedPixel | (bitToHide << 16);

                //Saving the pixel
                image.setRGB(x, y, newPixel);

                bitIndex++;
                if (bitIndex == 8) {
                    bitIndex = 0;
                    byteIndex++;
                }
                if (byteIndex >= totalData.length) {
                    return;
                }
            }
        }
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
        int x = 0, y = 0;

        outer : for(y=0; y<height; ++y) {
            for(x=0; x<width; ++x) {
                int pixel =  image.getRGB(x, y);
                int bit  = (pixel >> 16) & 1;
                currentByte = (currentByte << 1) | bit;
                bitCount++;

                if(bitCount == 8) {
                    header[headerIndex++] = (byte) currentByte;
                    currentByte = 0;
                    bitCount = 0;
                    if(headerIndex == 4) break outer;
                }
            }
        }

        int dataLength = ByteBuffer.wrap(header).getInt();
        byte[] data = new byte[dataLength];

        int dataIndex = 0;
        x++;
        if (x >= width) {
            x = 0;
            y++;
        }

        for(; y<height; ++y) {

            for(; x<width; ++x) {

                int pixel = image.getRGB(x, y);
                int bit = (pixel >> 16) & 1;

                currentByte = (currentByte << 1) | bit;
                bitCount++;

                if (bitCount == 8) {

                    if (dataIndex == dataLength) {
                        return data;
                    }

                    data[dataIndex++] = (byte) currentByte;

                    // Reset for the next character
                    currentByte = 0;
                    bitCount = 0;
                }
            }
            x = 0; //resetting for next row
        }
        return data;
    }
}
